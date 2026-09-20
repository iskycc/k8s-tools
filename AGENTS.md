# AGENTS.md

本文件适用于整个仓库。用户使用说明见 [README.md](README.md)；这里记录维护项目时需要遵循的结构、兼容性与验证约定。

## 项目定位与入口

- 项目是单模块 Maven Java 库与 CLI，坐标为 `io.github.iskycc:k8s-tools`，目标运行时为 Java 8。Maven `groupId` 与 Java 包名独立，现有 Java 包名为 `com.iskycc.k8s`。
- 主链路：`Main` → `ServiceTokenFetcher` → `SshExecutor` 执行远端 `kubectl` → `MasterInfo` → `K8sApiClient` 发起 Bearer Token REST 请求；已有 API 凭据可直接构造客户端。
- `src/main/java/com/iskycc/k8s/ssh/` 负责 SSH 配置、命令执行和凭据获取；`api/` 负责 HTTP/TLS、异常、Discovery、通用资源 CRUD 和分页；`api/model/` 放置 Gson 资源模型。
- `src/test/java/com/iskycc/k8s/mock/` 提供模拟 SSH master、HTTPS API Server 和测试证书。模拟服务不运行真实 `kubectl`。
- 依赖、插件和编译设置以 `pom.xml` 为准；当前使用 Apache MINA SSHD 2.19.0、Apache HttpClient 5（Java 8 下的 PATCH 支持）、Gson、JUnit 4。排除 SSHD 引入但未使用的 jcl-over-slf4j；保留必需的 SLF4J API。SLF4J NOP 为 optional，不强制传递给库的使用者。JUnit、Hamcrest、Bouncy Castle 仅为测试依赖。
- `.github/workflows/ci.yml` 在 Java 8、21 上构建；`publish.yml` 使用 Java 21 签名并发布正式版到 Central Portal；`publish-snapshot.yml` 从 `main` 手动发布快照。发布操作说明见 [docs/publishing.md](docs/publishing.md)。

## 开发与构建

在仓库根目录执行命令，使用已安装的 Maven；仓库没有 `mvnw`。

```bash
# 全部单元测试与模拟 E2E
mvn test

# 指定范围验证
mvn -Dtest=K8sApiClientTest test
mvn -Dtest=K8sToolsE2ETest test
mvn -Dtest=MainDemoTest test

# 打包并准备 CLI 的运行时依赖（package 会运行测试）
mvn -B package dependency:copy-dependencies -DincludeScope=runtime

# 无集群的 CLI 启动检查
java -cp 'target/k8s-tools-1.2.1-SNAPSHOT.jar:target/dependency/*' \
  com.iskycc.k8s.Main --help
```

需要 JDK 8+、Maven 3.6.3+；当前构建配置使用 `maven.compiler.release=8`，可在 JDK 21 上构建。较新 JDK 构建成功不等于已完成 Java 8 运行时验证。应用 jar 不包含第三方依赖，运行时需提供 `target/dependency/*`。

## 编码约定

- 保持 Java 8 语法与标准库兼容，不引入 `var`、record、文本块、`List.of` 或较新 JDK 的 HTTP 客户端。
- 延续现有包结构、4 空格缩进、同行左花括号与显式 import；面向使用者的文档、注释和 CLI 文案以中文为主。
- 配置沿用 `SshConfig.Builder`、`ServiceTokenFetcher.Options`、`K8sApiClient.Builder`，不要为小范围功能引入应用框架或另一套配置机制。
- 资源模型字段对应 Kubernetes JSON 字段；新增便利 getter 时延续现有缺失字段和空列表处理方式。
- 外部执行与网络失败保留异常原因；HTTP 错误继续通过 `K8sApiException` 暴露状态码和响应体，网络错误状态码为 `-1`。
- 保持资源释放：`ServiceTokenFetcher.fetch()` 在 `finally` 中关闭 SSH；直接使用 `SshExecutor` 时负责关闭；HTTP 请求结束后断开连接。不要并发共享 `SshExecutor`。
- 文档整理只改文档；发现实现问题时记录具体证据与限制，不顺带改写业务行为。

## 修改前核对的行为

以下是当前实现的行为边界；涉及变更时同步调整调用方、文档和相关测试：

- 凭据获取会写入集群，默认使用 `kube-system/k8s-tools` SA、`k8s-tools-token` Secret 和指向 `cluster-admin` 的 `k8s-tools` ClusterRoleBinding。
- 已有 SA 依次尝试自动 Secret 和手动 Secret；均无法读取 token 时默认删除并重建 SA。关闭 `recreateSaWhenTokenUnobtainable` 后直接抛异常。重试数表示首次读取之后的次数。
- 已存在的 ClusterRoleBinding 不会校验或修正角色和主体；不要把“存在”描述成“权限已验证”。
- 新建 token Secret 必须提交带 SA 注解的完整 JSON；AlreadyExists 时补注解。mock 会拒绝缺少注解的旧创建命令，并检查清单字段，不能弱化校验。
- API 地址发现顺序为显式覆盖 → 当前 kubeconfig → `admin.conf` → SSH 主机的 `6443` 端口。`kubeConfigPath` 只用于提取地址，不控制所有远端 `kubectl` 的配置。
- `fromMasterInfo` 无 CA 时默认直接跳过 TLS 校验；Builder 未提供 CA 时初始使用 JVM 信任库。两者的 GET/HEAD 默认允许 TLS 失败后自动降级，降级对同一客户端后续请求（含写入）持续生效；写请求自身不触发自动降级或重放。
- 严格 TLS 需要同时保持 `insecureSkipTlsVerify(false)` 和 `tlsAutoFallback(false)`；`fromMasterInfo(info, false)` 不会关闭自动降级。
- CLI 的 `--namespace` 只影响资源查询。`null`、空白或 `all` 表示全命名空间；CLI 没有公开全部 Java 配置项。
- token 来自 Secret，当前没有自动续期；不要承诺“永久有效”。`MasterInfo.toString()` 掩码 token，不要新增记录完整 token、密码或私钥的日志。
- SSH 当前接受任意主机密钥。SA/Secret/RBAC 资源名和重试参数在连接前校验，远端绝对路径及 Secret JSON 使用 shell 引号转义；新命令仍需保证外部输入不被当作 shell 代码。
- SSH 仅配置密码时自动使用密码模式；`SshConfig.Builder.passwordOnly(true)` / CLI `--password-only` 强制忽略显式私钥与口令、本地 `.ssh/config`、默认私钥和 SSH agent。仅保留 password / 单密码 keyboard-interactive，不回退到用户密钥签名认证；混合凭据未强制时保留原先行为。此功能从 `1.2.0` 起提供，`1.1.0` 不包含这些改动，源码更新也不表示远端快照已经更新。

## 通用资源 API 约定

- Maven 使用方配置见 [docs/maven-usage.md](docs/maven-usage.md)，公共 API 与示例见 [docs/library-api.md](docs/library-api.md)，可运行的查询示例在 [docs/examples/K8sReadExample.java](docs/examples/K8sReadExample.java)，能力边界见 [docs/api-completeness.md](docs/api-completeness.md)。通用 CRUD 接口从正式版 `1.1.0` 起提供，`1.0.0` 只有查询接口；当前源码开发构建仍为 `1.2.1-SNAPSHOT`。
- `ResourceDefinition` 明确 apiVersion、plural、Kind 和作用域；`K8sResources` 是常用常量，不是完整 API 清单。CRD 和其他资源用 Discovery 或显式定义，禁止推测 Kind 的复数。
- 写入使用完整 Gson JSON，保留未知字段并复制输入；原有简化 POJO 只用于读取，不能拿它们做完整 PUT。PUT 要求 `metadata.resourceVersion`，冲突交由调用方合并。
- 集群资源不能指定 namespace；命名空间资源的单对象读写和集合删除必须有具体 namespace。只有旧 list 快捷方法把字符串 `all` 解释为跨命名空间，新入口的 `all` 是真实命名空间。
- Discovery verbs 是 API 能力，不是 RBAC 权限。不要把通用 CRUD 描述成每个资源都有全部动作；子资源保留自己的 Kind，动作与准入以服务端为准。
- HTTP 自动重试和重定向关闭；请求和响应资源在调用结束释放，不要求客户端 close。保持错误状态、正文和响应头，异常默认消息不包含可能敏感的正文。
- 单页查询保留 continue/resourceVersion；自动分页遇到 410、重复 token、混合版本直接失败，不静默重启。删除成功不表示 finalizer 已完成。
- 新增流式功能不能复用当前缓冲整个响应的 request 方式；watch/exec/attach/port-forward 尚未支持。

## 验证与测试维护

- 日常验证使用本地模拟服务，无需真实集群、Docker 或本地 `kubectl`。只有任务本身涉及真实环境时才接入相应集群；运行 CLI 或 `fetch()` 包含资源创建、授权及可能的删除操作。
- 测试使用 JUnit 4。新增测试服务绑定 `127.0.0.1` 的随机端口，并可靠关闭服务器和临时资源。避免依赖测试执行顺序；带状态的 SA 场景按需使用独立 mock 实例。
- SSH 命令或 token 流程变更：核对 `MockK8sMasterServer` 的匹配规则，验证复用、新建、重建、禁用重建和异常路径。模拟器的成功响应不能作为真实 Kubernetes 接受命令的证据。
- SSH 认证变更：运行 `SshExecutorTest` 与 CLI/E2E 回归。使用临时私钥、独立 home 和 loopback SSH 验证本地配置隔离、错误密码不回退、交互密码与私钥兼容；不得修改或读取真实用户的 `.ssh` 身份文件作为测试数据。
- API 路由或模型变更：同步 `MockK8sApiServer` 的路由、fixture 和行为断言，覆盖指定命名空间、全命名空间和集群资源。写 API 需核对真实 HTTP 方法、query 编码、Content-Type、正文保留、冲突/权限错误和不重放行为。
- TLS、认证、异常处理变更：验证对应的成功与失败行为，包括严格模式和自动降级；CLI 参数或输出变更同步核对 `MainDemoTest` 与 `Main.usage()`。
- 按改动范围先运行相关测试。Java 代码、依赖或构建配置变更交付前运行 `mvn test`；若已运行成功的 `mvn package`，其中的测试无需重复执行。纯文档改动核对命令、链接和实际默认值即可，无需新增测试。
- 查看 `target/surefire-reports/` 确认结果。说明实际运行了哪些检查；未执行、被环境阻断或仅由 mock 覆盖的部分如实列出。
- 依赖或打包配置变更：检查主 jar、sources、Javadoc 中没有测试类、mock、测试资源或内嵌依赖；不发布 tests 附件。用只引用本库的独立 Maven 项目核对实际传递依赖，并在不带测试依赖的 classpath 下验证必要运行功能；不能仅凭本仓库的测试 classpath 判断使用方依赖完整性。
- 运行与测试依赖选择支持 Java 8 的最新稳定版本；核对 Central 元数据和上游最低 JDK，不将 alpha/beta/RC/milestone 当作正式版。记录日期和选择依据于 `docs/dependency-versions.md`，扫描实际依赖 jar 的基础字节码并在真实 JDK 8 上验证。JUnit 保留 4.x API，Hamcrest 单独使用最新兼容版本；Gson 静态分析注解不向下游传递，SLF4J API 与 optional NOP 版本保持一致。

## 文档与交付

- Maven Central 正式版配置集中在 POM 的 `release` profile：正式版本检查、sources/Javadoc 附件、GPG 签名、Central Portal 部署。`snapshot` profile 使用 Maven Deploy 插件上传到 `https://central.sonatype.com/repository/maven-snapshots/`，同样生成附件和签名。两个 profile 分别使用，普通构建不需要发布凭据，不在默认构建中启用上传。
- 正式版保留 `autoPublish=true`、`waitUntil=uploaded`，Actions 完成构建、测试、签名和上传后结束。不要把上传成功描述为已可从 Central 下载；后续校验、发布结果以 Portal 为准。
- 正式版发布仅接受 `vX.Y.Z` 标签；标签中的 POM 版本必须为 `X.Y.Z` 或 `X.Y.Z-SNAPSHOT`，工作流在临时检出目录改为正式版本，不回写 Git。快照从 `main` 手动运行，输入版本必须为 `X.Y.Z-SNAPSHOT` 且与 POM 一致，不创建正式版标签。修改发布逻辑时保留版本检查及 `contents: read` 权限，表达式输入通过环境变量传给 shell。
- Actions 使用固定提交 SHA。升级时核对对应 action 的真实输入参数，尤其 `setup-java` 的凭据环境变量名与 GPG 配置。
- 发布配置变更需验证 sources/Javadoc 生成和签名流程。在独立工作副本中执行 `mvn -Prelease verify` 可生成签名产物而不上传；测试密钥使用临时 keyring，不上传公钥、不提交私钥。`-Dcentral.skipPublishing=true` 会跳过部署及产物暂存，不保证生成 bundle。仅测试包生成时可用 `-Dgpg.skip=true verify`，但这不验证签名或远端发布。

- `README.md` 面向使用者，记录环境、启动方式、配置、实际副作用和已知限制；`AGENTS.md` 面向维护者，记录结构与工作约定。避免复制大段实现或容易过期的测试数量、行号。
- 修改 CLI、默认配置、依赖、打包方式或行为时同步相关文档；新增 CLI 参数时同时更新解析逻辑、帮助文案和验证。
- 文档用仓库相对链接，示例使用占位凭据、文档地址或 loopback 地址。不要提交真实集群信息、token、SSH 密钥、`.env`、`target/` 或 IDE 文件。
- 交付前检查 `git diff --check` 和 `git status --short`，保留用户已有改动。交付说明包含变更内容、验证结果及仍存在的具体限制。

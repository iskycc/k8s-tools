# 工具库完整性核对

核对对象是单模块 Java 库及 CLI，通用资源接口从 `1.1.0` 起提供，`1.2.0` 增加仅密码 SSH 模式。当前源码的开发构建版本为 `1.5.5-SNAPSHOT`。

## 1.5.5 Pod Exec 与全局调试开关

`K8sApiClient.exec` / `execShell` 返回 stdout、stderr 和退出码；有 SSH 配置时，AUTO 在执行前探测版本，低于 1.31 使用 SSH/kubectl，其他版本使用 WebSocket；无 SSH 配置则直连 WebSocket。支持容器选择、总超时、输出上限和中断，不在失败后切换通道重放命令，不触发 TLS 降级。无 stdin/TTY 交互功能，详见 [调用指南](pod-exec.md)。

`K8sLogging` 默认关闭 DEBUG，可通过 JVM 属性或运行时方法切换；INFO/WARN/ERROR 保留，每次选定 Exec 通道打印一行 INFO。配置见 [日志指南](logging.md)。

## 1.5.0 客户端托管 Redis

`K8sApiClient.builder().redisUrl(...).refreshCache(...).fromSsh(ssh)` 内部管理缓存判断、SSH 获取和 Redis 连接关闭，业务方无需直接操作 Jedis 或缓存类。保留外部缓存配置入口兼容，新增 [完整查询 main 示例](examples/all-queries.md)。带密码 URL 支持默认用户、ACL 与百分号编码。

## 1.3.0 Redis 凭据缓存与自动接入

通过 Jedis 5.2.0 保存 master 的 token 和 API 地址两个 String，命中后跳过 SSH，提供删除与强制刷新。新增 `fromSsh` 自动发现 API 地址并直接跳过 TLS 证书校验；CLI 支持 Redis、刷新与严格 TLS 参数。缓存不自动验证 token、不自动重放失败请求，详细边界见 [Redis 接入指南](redis-cache.md)。上述改动从 1.3.0 起提供，1.2.1 不包含。

## 1.2.1 依赖更新与发布范围

升级至核对日支持 Java 8 的最新稳定依赖，并排除未使用的日志桥接和静态分析注解。测试组件保持 test scope，日志实现保持 optional；主 jar、sources 与 Javadoc 仅包含生产内容。组件清单见 [README](../README.md#开源组件与依赖范围)，版本依据及兼容性见[依赖版本核对](dependency-versions.md)。

## 1.2.0 SSH 认证补齐

仅配置密码时自动禁用用户密钥认证，`passwordOnly(true)` / `--password-only` 可强制忽略显式私钥、本地 SSH 配置、默认身份文件和 SSH agent。支持 password 与单密码 keyboard-interactive；混合凭据未强制时保留原有密钥优先行为。`SshExecutorTest` 使用 loopback SSH 和独立 home，验证默认私钥确实可用但密码模式不使用它、错误密码不回退、显式私钥仍可登录；CLI 测试覆盖强制模式与缺失密码检查。

## 本次补齐

| 环节 | 原有缺口 | 当前实现 / 验证 |
| --- | --- | --- |
| 公共调用入口 | 仅版本与少量资源列表 GET | `K8sApiClient.resource` 统一入口，常用资源便捷方法；保留已有 POJO 查询 |
| 通用资源范围 | 硬编码 Pod/Service/Deployment 等路径 | GVR + 作用域描述、常用内置资源常量、API Discovery、任意 served CRD / 聚合资源 |
| 资源读写 | 没有 POST/PUT/PATCH/DELETE | 真实 HTTP CRUD、三种 Patch、Server-Side Apply、单对象与集合删除 |
| 审查类资源 | 不能提交无名称的审查请求 | TokenReview、SelfSubjectAccessReview 等 create-only 资源不强制 name/generateName |
| 完整数据 | POJO 只映射少量字段，无法无损回写 | 写接口使用完整 JSON，保留未知字段，对传入正文做副本与身份校验 |
| 并发更新 | 没有 resourceVersion 约定 | PUT 要求 resourceVersion，保留 409；Patch 支持 JSON test；删除支持 UID/version 前置条件 |
| 查询条件 | 缺少选择器与分页 | label/field selector、limit、continue、resourceVersion、timeoutSeconds；单页或自动分页 |
| 删除语义 | 缺少宽限期与级联策略 | DeleteOptions、dry-run、Foreground/Background/Orphan；明确不自动等待 finalizer |
| 子资源 | 无公共入口 | status、scale、eviction、token 等 JSON REST 子资源的 get/create/replace/patch；scale 便捷方法 |
| HTTP 协议 | Java 8 原生客户端不能 PATCH | Apache HttpClient 5，关闭自动重试与重定向，请求完成释放连接 |
| 错误信息 | 只有状态码和正文，默认消息含正文 | 增加 Kubernetes reason/message、多值响应头，默认消息不输出正文 |
| 命名空间 | 字符串直接拼路径 | 显式作用域、路径段编码/校验，阻止名字改变 URL 结构；保留旧 all 查询语义 |
| TLS | 不支持多个 CA，所有请求按 GET 设计 | 支持 PEM CA bundle；写入不自动降级/重放；保留旧读请求兼容行为 |
| SSH 凭据初始化 | token Secret 创建时缺少必需 SA 注解 | 提交带注解的完整 Secret JSON；AlreadyExists 时补注解；mock 拒绝旧创建命令 |
| SSH 输入 | 资源名和路径直接拼命令 | 资源名/重试参数先校验，远端绝对路径与 JSON 正文做 shell 引号处理 |
| 下游依赖 | 库传递依赖带入 NOP 日志实现 | 日志实现保持 optional；1.5.2 的 CLI 使用 Simple，使用者自行选择 provider |
| 使用文档 | 只有 CLI 和简化列表查询 | [Java API 指南](library-api.md)，包含 CRUD、分页、CRD、Apply、子资源示例 |

## “所有资源”的准确范围

通用接口可寻址 Kubernetes 已提供的资源，使用其真实 apiVersion、复数名称和 namespace/cluster 作用域。新增资源类型不要求本库新增 POJO；自定义字段通过 JSON 保留。资源的有效 API 版本、verbs、RBAC 和准入约束由服务器决定，工具不会承诺每个资源都支持所有动作。

例如：CRD 的版本以集群安装内容为准；指标 API 通常只读；Namespace 的删除存在 finalizer；工作负载的 scale 子资源和主体版本可能不同；CRD 不支持 Strategic Merge Patch。Discovery 的 verbs 是能力列表，不是权限检查。手动定义资源时需传入真实 plural，不能简单把 Kind 转为小写加 s。

## 尚未提供的能力

以下能力不属于普通 JSON REST CRUD，目前仍需调用方或其他客户端提供：

| 能力 | 当前边界 |
| --- | --- |
| Watch / Informer | 无事件流、断线恢复、缓存和 resourceVersion 续接 |
| 交互式 exec / attach / port-forward / 日志跟随 | 支持 WebSocket / SSH-kubectl 非交互式 exec；无 stdin/TTY、Java 原生 SPDY 或其他流式能力；raw 请求仍缓冲整个响应 |
| kubeconfig / 集群内自动配置 | 目前直接 API 地址 + Bearer Token + CA，或 SSH 获取 MasterInfo；不解析 kubeconfig 的多 context、exec 插件、客户端证书 |
| token 自动轮换 | 可通过 token 子资源请求短期 token，但不自动更新客户端凭据；SSH Secret token 也不自动续期 |
| YAML 多文档与批处理事务 | 写接口接收 JSON；无 YAML 解析器，无跨资源事务或失败回滚 |
| 模式级校验与强类型 Builder | 没有为全部 Kubernetes OpenAPI 字段生成模型；字段有效性由服务器校验 |
| 高吞吐连接池、异步和可取消调用 | 当前逐请求关闭连接，以兼容已有无 close 的客户端使用方式；不定位为控制器运行时 |
| SSH known_hosts 与 RBAC 调整 | 仍接受任意 SSH 主机密钥；默认 cluster-admin 和 SA 重建策略保留；已有绑定不核对角色/主体 |
| 默认 TLS 策略 | CLI/fromSsh 默认跳过校验；原有 Builder 保留读取自动降级。严格模式需显式配置 CA 并关闭降级 |

## 验证方法与证据边界

```bash
# 公共配置与协议测试
mvn -Dtest=K8sApiClientTest,K8sResourceClientTest test

# SSH 初始化、旧 API 和 CLI 回归
mvn -Dtest=K8sToolsE2ETest,MainDemoTest test

# 完整构建（分别在 JDK 8、21 执行）
mvn clean verify
```

`K8sResourceClientTest` 使用真实 loopback HTTPS 连接和预设服务器响应，核对 HTTP 方法、路径、查询编码、媒体类型、完整 JSON 正文、分页和异常传播。覆盖 core/group API、namespace/cluster 范围、CRD Discovery、三种 Patch、Apply、删除参数和 JSON 子资源，同时验证写请求断线后不会重发、重定向不转发 token、未信任 TLS 写入不降级。

SSH 模拟器检查 Secret 清单在创建时包含 SA 注解，覆盖新建、复用、重建、AlreadyExists 和禁用重建；其验证范围仍是本地模拟，测试 fixture 不是版本兼容认证。新增的 [真实 Kubernetes E2E](e2e.md) 在 kind 中验证代表性的 schema、RBAC、token/Deployment 控制器和 REST 调用；结果以对应 Actions 作业为准，不覆盖所有准入配置、资源或 API 版本。

后续发布前需在独立副本将版本设为正式值，生成 sources/Javadoc 并使用临时测试 keyring 验证签名。实际运行结果应以 `target/surefire-reports` 和发布流水线为准；已经发布的正式版本不可覆盖。

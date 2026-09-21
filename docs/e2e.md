# GitHub Actions 真实 Kubernetes E2E

[Real Kubernetes E2E](../.github/workflows/e2e.yml) 在 GitHub 的临时 Ubuntu runner 上创建真实 Kubernetes API Server、etcd、控制器和工作节点，验证本库与真实服务的协议和状态变化。它使用 [kind](https://kind.sigs.k8s.io/docs/user/quick-start/)，不依赖已有集群或仓库发布 Secrets。

## 两种验证各自证明什么

| 流程 | 服务 | 证据边界 |
| --- | --- | --- |
| Maven CI / 普通 `mvn test` | loopback 模拟 SSH、HTTPS、RESP Redis | 参数、路径、异常、重试边界和确定性故障场景；不能证明 Kubernetes 接受某个清单 |
| Real Kubernetes E2E / `real-e2e` profile | kind Kubernetes、OpenSSH、真实 Redis | API 校验、真实持久化、ServiceAccount token controller、RBAC、Deployment 控制器以及库的接入链路 |

kind 是在容器中运行的 Kubernetes，提供真实 API；mock API Server 是本项目已有的模拟实现。二者保留并分别运行。

## 环境与触发

- runner：`ubuntu-24.04`；Java 8、21 两个独立作业，每个作业创建自己的集群。
- kind `v0.33.0`、Kubernetes `v1.37.0`，二进制 SHA256 和节点镜像 digest 固定，来源见 [kind 发布说明](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0)。kubectl 从该节点镜像复制，版本与 API Server 一致。
- OpenSSH 和 Redis 使用 runner 的 Ubuntu 软件包，Redis 实际版本输出到准备步骤日志；测试工作负载使用 `registry.k8s.io/pause:3.10`，Exec 测试使用含 shell 的 `busybox:1.37.0`。
- 推送 `main`、PR 或手动 `workflow_dispatch` 均可触发。可在 Actions → **Real Kubernetes E2E** → **Run workflow** 手动执行。
- 只使用 `contents: read` 权限，不发布 Maven 版本，不访问生产集群。

SSH 服务监听 `127.0.0.1:22222`，使用临时用户 `k8se2e`；Redis 监听 `127.0.0.1:16379`，启用临时密码。SSH 登录后执行的是实际 kubectl，使用临时 kind kubeconfig；SSH 服务在 runner 上，API Server 和节点在 kind 容器中。它验证远程执行链路，不模拟独立物理 master 的网络拓扑。

凭据、SSH 主机密钥和 kubeconfig 在 runner 临时目录生成，密码通过环境变量传入并在 Actions 中掩码。Java 报告不打印真实 token；上传产物只包含测试报告、查询摘要和资源状态表，不包含 Secret、kubeconfig、Redis 数据或私钥。流程最后清理集群及临时服务，runner 也会被 GitHub 销毁。

## 覆盖的功能

[RealKubernetesIT](../src/test/java/com/iskycc/k8s/e2e/RealKubernetesIT.java) 通过真实 API 创建独立测试 namespace，执行后删除：

| 场景 | 验证 |
| --- | --- |
| SSH 初始化 | 通过真实 OpenSSH 执行 kubectl，创建 SA、带注解的 token Secret、ClusterRoleBinding，自动发现 API 地址 |
| Redis | 带密码认证；token/API 地址为 String、无 TTL；错误 SSH 密码下仍可命中缓存；401 后显式刷新；Redis 认证失败明确抛错 |
| `queryTypedModels` 对应接口 | 版本、Node、Namespace、Pod、Service、Deployment；指定及跨 namespace 查询 |
| 控制器与子资源 | 等待 Deployment 的 Pod Ready，scale 到 2 个 Ready 副本，读取 Pod status 和 Deployment scale |
| CRUD | Namespace、ConfigMap、Secret、Service、自定义资源；用 kubectl 独立核对 ConfigMap 和 Deployment 的实际状态 |
| Patch / Apply | JSON Patch、Merge Patch、Strategic Merge Patch、Server-Side Apply；dry-run 不落库 |
| 选择器与分页 | label/field selector、limit、continue、自动分页、跨 namespace list、按标签集合删除 |
| 搜索 SDK | 两个 namespace 创建同名 Pod、ConfigMap、Service、Deployment，以 limit=1 搜索；简易与详细版均返回全部匹配，核对容器名称、配置数据、端口、副本数及无匹配空列表 |
| Discovery / CRD | 从 Discovery 定位新建 CRD；schema 校验、未知字段保留、资源 CRUD、status 子资源 |
| 错误与权限 | 401、403、404、409、严格字段校验 400、CRD schema 校验 422；受限 SA 的 TokenRequest 和 SelfSubjectAccessReview |
| TLS | 默认忽略自签名证书；发现的 CA 可用于严格校验；JVM 不信任且关闭降级时请求失败 |
| Pod Exec | 双容器 BusyBox、严格 TLS、argv 参数保留、shell、stdout/stderr、非零退出码、输出上限、超时、404/403 和无重复写入；WebSocket 直连 API Server，另强制 SSH 验证参数、退出码及受限 token 不会借用 master 管理员身份 |
| 使用方入口 | 实际编译并运行 [完整查询 main Demo](examples/all-queries.md) 和 CLI |

这证明代表性 JSON REST 与非交互式 exec 能力可在上述 Kubernetes 版本工作，不表示已经逐个测试所有资源、所有 verbs 或所有 Kubernetes 版本。未验证的边界包括云厂商扩展、实际跨机网络、Service 流量转发/Ingress、Redis Cluster、Redis TLS 和持久化重启、SSH known_hosts，以及本库尚未实现的 watch/交互式 exec/attach/port-forward。410 和断线不重放等可控故障仍由模拟测试覆盖。

## 结果与维护

成功标准是 Actions 作业成功、Failsafe 测试没有失败/错误，以及 Demo/CLI 正常退出；没有测试报告不能算通过。作业 Summary 列出每个真实集群测试的结果，`real-e2e-java-8` / `real-e2e-java-21` 附件保留 7 天。

环境准备与清理脚本分别为 [setup-kind.sh](../scripts/e2e/setup-kind.sh)、[cleanup-kind.sh](../scripts/e2e/cleanup-kind.sh)，诊断脚本为 [diagnostics.sh](../scripts/e2e/diagnostics.sh)。它们创建 Linux 用户、安装包和启动服务，限制在独立 GitHub Linux runner 使用。

普通本地验证仍用：

```bash
mvn test
```

在工作流已经准备好的一次性环境中执行：

```bash
mvn --batch-mode --no-transfer-progress -Preal-e2e clean verify
```

真实测试通过 Failsafe 的 `*IT` 入口隔离，默认 `mvn test`、普通 CI 和 Maven 发布工作流不会连接 kind。这些测试基础设施仅位于 `src/test`、脚本及文档，不进入主 jar、sources、Javadoc，也不向库使用方增加依赖。

# 从 SSH、Redis 到全部查询接口的 main 示例

入口文件：[K8sAllQueriesExample.java](K8sAllQueriesExample.java)。兼容 Java 8，依赖 `io.github.iskycc:k8s-tools:1.5.2`，使用从 `1.5.0` 起提供的客户端托管 Redis 接口；`1.3.0` 不包含此入口。也可直接在本仓库编译运行。

示例执行顺序：配置 SSH → 将 Redis URL 传给客户端 Builder → `fromSsh` 内部读取缓存或通过 SSH 获取凭据并发现 API 地址 → 执行查询。密码模式优先；设置了非空 SSH 密码后不会使用本地私钥。API 地址、token 和证书均无需手工输入。

## 运行

在本仓库根目录执行：

```bash
# 编译库并准备运行时依赖；示例仍留在 docs，不放入库的发布 jar。
mvn --batch-mode --no-transfer-progress compile dependency:copy-dependencies -DincludeScope=runtime
mkdir -p target/examples
javac -encoding UTF-8 -source 8 -target 8 \
  -cp 'target/classes:target/dependency/*' \
  -d target/examples docs/examples/K8sAllQueriesExample.java

# 帮助模式不读取凭据、不连接 SSH、Redis 或 Kubernetes。
java -cp 'target/examples:target/classes:target/dependency/*' K8sAllQueriesExample --help

# 替换以下占位值；只有 master IP、SSH 凭据和 Redis 连接配置是必需的。
export K8S_MASTER_IP='192.0.2.10'
export K8S_SSH_PORT='22'
export K8S_SSH_USER='root'
export K8S_SSH_PASSWORD='<SSH 密码>'
export K8S_TOOLS_REDIS_URL='redis://:example-password@127.0.0.1:6379/0'
# ACL 用户：redis://app-user:example-password@127.0.0.1:6379/0
# 无密码：redis://127.0.0.1:6379/0
export K8S_NAMESPACE='default'

java -cp 'target/examples:target/classes:target/dependency/*' K8sAllQueriesExample

# 凭据或地址失效时，检查原因后删除 Redis 缓存并重新通过 SSH 获取。
java -cp 'target/examples:target/classes:target/dependency/*' K8sAllQueriesExample --refresh-cache
```

在其他 Maven 项目运行时，使用 [Maven 配置指南](../maven-usage.md#从空项目运行一个查询示例)中的 POM，依赖版本使用 `1.5.2`；将示例复制到 `src/main/java/K8sAllQueriesExample.java`，运行 `mvn compile dependency:copy-dependencies -DincludeScope=runtime`，再使用 `java -cp 'target/classes:target/dependency/*' K8sAllQueriesExample`。Windows 的 classpath 分隔符改为 `;`，并使用双引号。

## 配置

| 环境变量 | 默认值 / 用途 |
| --- | --- |
| `K8S_MASTER_IP` | 必填，SSH master IP，也是 Redis key 前缀 |
| `K8S_SSH_PORT` / `K8S_SSH_USER` | `22` / `root` |
| `K8S_SSH_PASSWORD` | 密码认证；非空时强制仅密码模式 |
| `K8S_SSH_KEY` | 无密码时必填，显式私钥文件路径 |
| `K8S_SSH_KEY_PASSPHRASE` | 私钥口令，可选 |
| `K8S_TOOLS_REDIS_URL` | 必填，例如 `redis://127.0.0.1:6379/0`；支持带认证的 URI 和 `rediss://` |
| `K8S_NAMESPACE` | `default`，须为具体命名空间；字符串 `all` 在这里是命名空间的实际名称 |
| `K8S_PAGE_SIZE` | `100`，允许 `1` 至 `1000` |
| `K8S_LABEL_SELECTOR` | 可选，例如 `app=nginx`，仅用于选择器查询段 |
| `K8S_FIELD_SELECTOR` | `status.phase=Running`，仅用于 Pod 选择器查询段 |
| `K8S_RESOURCE_API_VERSION` | `apps/v1`；可改为 CRD 实际提供的版本，例如 `example.com/v1` |
| `K8S_RESOURCE_PLURAL` | `deployments`；可改为实际 CRD plural，例如 `widgets` |

有密码时使用 `redis://:密码@主机:端口/数据库`，ACL 认证使用 `redis://用户名:密码@主机:端口/数据库`；特殊字符需要百分号编码，见 [Redis URL 带密码的格式](../redis-cache.md#redis-url-带密码的格式)。

环境变量由此示例读取，不代表库自动读取这些变量。示例不引用 Jedis、缓存类或 `ServiceTokenFetcher`；Redis 连接在客户端 `fromSsh` 内创建并关闭，业务查询期间无需保持连接。

## 查询覆盖

“全部查询”指覆盖库提供的查询接口种类，并通过 Discovery 遍历资源类型；不是一次导出集群全部对象或全部子资源。

| 示例方法 | 演示接口与行为 |
| --- | --- |
| `queryTypedModels` | `getVersion`、`listNodes`、`listNamespaces`、`listPods`、`listServices`、`listDeployments`；后面三项同时演示指定与跨命名空间查询 |
| `queryResources` / `queryResource` | `nodes`、`namespaces`、`pods`、`services`、`deployments`、`configMaps`、`secrets`；列表非空时用首个真实名称演示 `exists`、`get` |
| `queryResources` | `resource(K8sResources...)` 查询 SA、CRD；`inNamespace` / `inAllNamespaces`；Pod status、Deployment status/scale 的 `subresource(...).get()` |
| `queryPaginationAndSelectors` | `list(ListOptions)`、label/field selector、limit、timeout；`listAll` 自动分页；`withContinueToken` 手动逐页遍历及重复 token / resourceVersion 检查 |
| `queryDiscoveryAndCustomResources` | `discoverApiVersions`、`discoverResources`、`ApiResource.toDefinition`；遍历所有已发现 API 版本的可 list 资源，包含内置资源、CRD 和聚合资源 |
| `queryDiscoveryAndCustomResources` | `resource(apiVersion, plural)`，由 Discovery 确定 Kind 和作用域，无需为 CRD 创建 Java 模型 |
| `queryRawApi` | `getRaw`、`request("GET", ...)`、HTTP 状态码、响应头及原始 JSON 解析 |

`list()`、`listAll()` 是不带查询参数的重载；本例统一使用带 `ListOptions` 的形式以展示页大小。各通用资源入口共享同一组查询方法，无需为 StatefulSet、Job、Ingress、Role 等重复编写类。

常用资源与 Discovery 遍历默认只读首页，并输出 `hasMore`；**只有 Pod 的两段分页演示会遍历所有页**，其中 `listAll` 把全部结果放入内存。旧 POJO 快捷方法也不自动翻页。所有列表只展示最多十条摘要，Secret/ConfigMap/CRD 只输出 Kind、namespace、name，不输出数据、注解或完整正文。子资源查询在没有对应 Pod/Deployment 时跳过。

Discovery 的 verbs 是 API 支持的动作，不是 RBAC 权限。示例跳过不支持 list 的资源及子资源集合，遇到 403/404 记录并继续；其他查询错误计入失败，最后退出码为 1。401 立即中止并提示刷新缓存，不自动刷新或重放请求。分页 410、重复 token 或混合版本也会报失败，不从第一页静默重来。`watch`、`exec`、`attach`、`port-forward` 尚非本库支持的查询能力。

## SSH 初始化、缓存和 TLS 行为

缓存命中时不连接 SSH，也不探测 token 有效性。缺失或刷新时通过 SSH 创建/复用 `kube-system/k8s-tools` SA、token Secret 和指向 `cluster-admin` 的绑定；已有 SA 无法读取 token 时默认可能删除重建。**后续查询阶段只发 GET，SSH 初始化阶段仍可能修改集群资源。**

Redis 使用 `<masterIP>ServiceToken`、`<masterIP>ApiServerUrl` 两个 String，以及配套的 `<masterIP>ServiceTokenMetadata`。再次运行主程序会复用缓存，`--refresh-cache` 先删除后重新获取，详见 [Redis 缓存指南](../redis-cache.md)。缓存不保证 token 永久有效；服务端 Secret 本身失效时需修复凭据，单纯删除 Redis 可能再次读到相同 token。

本例使用 `fromSsh`，默认跳过 HTTPS 证书与主机名校验；SSH 主机密钥也沿用当前库的接受策略。需要严格 TLS 时，在客户端 Builder 的 `.fromSsh(ssh)` 前增加 `.insecureSkipTlsVerify(false).tlsAutoFallback(false)`，使用发现/缓存的 CA 或 JVM 信任库。完整配置见 [Redis 接入指南](../redis-cache.md)。

该 main Demo 也由 [真实 Kubernetes E2E](../e2e.md) 工作流编译并运行；`queryTypedModels` 的对应接口另有实际资源与控制器状态断言。

## 日志排查

`1.5.2` 增加连接、缓存、凭据获取及 API 请求日志。本仓库运行时默认输出 INFO；在其他 Maven 项目中需有 SLF4J 2.x provider。只针对 `com.iskycc.k8s` 开启 DEBUG 可查看每次请求的路径、状态码、requestId、Audit-ID 和耗时，配置示例见[日志与排障](../logging.md)。

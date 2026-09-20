# Java 工具库：资源增删查改

本文使用正式版坐标 `io.github.iskycc:k8s-tools:1.2.0`，发布完成后可从 Maven Central 引用，无需配置额外仓库。通用资源 CRUD 从 `1.1.0` 起提供，仅密码 SSH 模式从 `1.2.0` 起提供；旧版 `1.0.0` 只有查询接口。当前开发构建为 `1.2.0-SNAPSHOT`，可在本仓库运行 `mvn clean install` 安装到本地；本次不发布新快照，远端快照使用规则见[发布指南](publishing.md#发布与使用快照)。

首次接入先阅读 [Maven 坐标与接入配置](maven-usage.md)，其中提供完整 POM 和可编译运行的[查询示例](examples/K8sReadExample.java)。本文的 Java 代码块是按场景选择的调用片段，放入业务方法中使用；后续片段复用连接示例中的 `client` 和 `configMaps`。创建、删除等示例会修改目标集群，不应把全文作为一个脚本顺序执行。

| 要完成的任务 | 对应章节 |
| --- | --- |
| 配置地址、token、CA 或 SSH 初始化 | [连接与公共入口](#连接与公共入口) |
| 查版本、节点、Pod 等现有资源 | [查询与资源入口选择](#查询与资源入口选择) |
| 创建、更新、删除资源 | [CRUD 示例](#创建读取修改删除示例)、[Deployment 与 Service](#deployment-与-service-示例) |
| 选择器、分页、跨命名空间 | [选择器、分页和作用域](#选择器分页和作用域) |
| 合并更新、声明式管理 | [Patch 与 Apply](#patch-与-apply) |
| 自定义资源与权限检查 | [资源覆盖与 CRD](#资源覆盖与-crd) |
| 扩缩容、status、短期 token、原始响应 | [子资源和底层调用](#子资源和底层调用) |
| 参数与错误处理 | [请求参数速查](#请求参数速查)、[错误与兼容性](#错误与兼容性) |

## 连接与公共入口

已有 API Server 地址和 token 时，直接创建客户端，无需 SSH，也不会创建 ServiceAccount 或 RBAC：

```java
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.K8sResourceClient;
import com.iskycc.k8s.api.K8sResources;
import com.iskycc.k8s.api.ListOptions;
import com.iskycc.k8s.api.WriteOptions;
import com.iskycc.k8s.api.DeleteOptions;
import com.iskycc.k8s.api.PatchType;
import com.iskycc.k8s.api.ResourceDefinition;
import com.iskycc.k8s.api.ApiResponse;
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.api.model.K8sList;

K8sApiClient client = K8sApiClient.builder()
        .apiServer("https://192.0.2.10:6443")
        .token("<ServiceAccount token>")
        .caCertPem("<集群 CA 的 PEM 文本>")
        .insecureSkipTlsVerify(false)
        .tlsAutoFallback(false)
        .connectTimeoutMs(10000)
        .readTimeoutMs(30000)
        .build();

K8sResourceClient configMaps = client.configMaps("default");
// 等价于 client.resource(K8sResources.CONFIG_MAPS).inNamespace("default")
```

保留 `K8sApiClient.fromMasterInfo(info)`，可复用 SSH 获取的凭据。SSH 获取流程的权限、副作用和默认 TLS 行为见 [README](../README.md)。

`client.pods(ns)`、`services(ns)`、`deployments(ns)`、`configMaps(ns)`、`secrets(ns)` 是常用资源入口；`namespaces()`、`nodes()` 是集群资源入口。其他资源统一使用 `resource(K8sResources.XXX)` 或 Discovery。客户端每次请求后释放连接，不要求调用方 `close()`；它不是连接池客户端。

| Builder 参数 | 要传入的值 / 默认行为 |
| --- | --- |
| `apiServer` | API Server 基础地址，例如 `https://192.0.2.10:6443`，不包含资源路径、凭据、query 或 fragment |
| `token` | 原始 Bearer token，不要自行添加 `Bearer ` 前缀 |
| `caCertPem` | PEM 文本或多证书 PEM bundle，**不是文件路径**；未提供时使用 JVM 信任库 |
| `insecureSkipTlsVerify` | 默认 false；true 会直接跳过证书与主机名校验 |
| `tlsAutoFallback` | 默认 true；严格 TLS 必须设为 false，避免读请求失败后降级 |
| `connectTimeoutMs` / `readTimeoutMs` | 默认分别为 10000 / 30000 毫秒；可按网络条件设置正值 |

读取 CA 文件的方式见完整查询示例。若 API 证书由 JVM 信任的 CA 签发，可以省略 `caCertPem`，仍应设置 `tlsAutoFallback(false)`。客户端不会自动刷新 token；凭据更新后用新 token 构造客户端。

### 通过 SSH 获取凭据后严格连接

已有 API 凭据时优先使用前面的直接连接方式。只有需要通过 master 获取凭据时才调用 `fetch()`：它默认创建 SA、token Secret 和指向 `cluster-admin` 的绑定。下面显式关闭 SA 重建，但仍可能创建缺失资源；已有绑定不会核对角色或主体。

```java
com.iskycc.k8s.ssh.SshConfig sshConfigForApi = com.iskycc.k8s.ssh.SshConfig.builder()
        .host("192.0.2.10")
        .port(22)
        .username("<SSH 用户名>")
        .privateKeyPath("/absolute/path/to/id_rsa")
        .connectTimeoutMs(15000)
        .build();
com.iskycc.k8s.ssh.ServiceTokenFetcher.Options fetchOptions =
        new com.iskycc.k8s.ssh.ServiceTokenFetcher.Options()
                .recreateSaWhenTokenUnobtainable(false)
                .apiServerOverride("https://192.0.2.10:6443");
com.iskycc.k8s.ssh.MasterInfo fetchedInfo =
        new com.iskycc.k8s.ssh.ServiceTokenFetcher(sshConfigForApi, fetchOptions).fetch();

if (fetchedInfo.getCaCertPem() == null || fetchedInfo.getCaCertPem().trim().isEmpty()) {
    throw new IllegalStateException("未取得集群 CA，请先提供可信 CA");
}
K8sApiClient strictSshClient = K8sApiClient.builder()
        .apiServer(fetchedInfo.getApiServerUrl())
        .token(fetchedInfo.getToken())
        .caCertPem(fetchedInfo.getCaCertPem())
        .insecureSkipTlsVerify(false)
        .tlsAutoFallback(false)
        .build();
```

私钥有口令时用 `privateKeyPassphrase(...)`；密码认证使用 `password(...)`，示例中的值应从应用配置中提供。`fetch()` 在成功或失败后都会关闭 SSH。SSH 主机密钥校验和默认副作用见 [README](../README.md#默认行为与配置)。`fromMasterInfo(info, false)` 只控制缺 CA 时的初始模式，不会关闭 TLS 自动降级。

### 仅使用密码登录 SSH 机器

**本节的 `passwordOnly(true)` 和密码隔离行为从 `1.2.0` 起提供。** `1.1.0` 及之前的远端快照不包含这些改动；使用方应引用 `1.2.0`，或从当前源码执行 `mvn clean install` 后引用本地 `1.2.0-SNAPSHOT`。

```java
com.iskycc.k8s.ssh.SshConfig passwordSshConfig = com.iskycc.k8s.ssh.SshConfig.builder()
        .host("192.0.2.10")
        .port(22)
        .username("root")
        .password("<从应用配置取得的 SSH 密码>")
        .passwordOnly(true)
        .connectTimeoutMs(15000)
        .build();

com.iskycc.k8s.ssh.MasterInfo passwordMasterInfo =
        new com.iskycc.k8s.ssh.ServiceTokenFetcher(passwordSshConfig,
                new com.iskycc.k8s.ssh.ServiceTokenFetcher.Options()
                        .recreateSaWhenTokenUnobtainable(false)
                        .apiServerOverride("https://192.0.2.10:6443"))
                .fetch();
```

将 `passwordMasterInfo` 按上一节的 CA 检查与 Builder 配置构造成严格 TLS 的 API 客户端。`fetch()` 仍可能创建 SA、Secret 和 cluster-admin 绑定；只执行普通 SSH 命令时可直接在 try-with-resources 中使用 `SshExecutor`。

| 配置 | 认证行为 |
| --- | --- |
| 只设置 `password`，无私钥路径 | 自动使用密码模式 |
| `passwordOnly(true)` + 非空密码 | 强制密码模式，忽略同时设置的 `privateKeyPath`、`privateKeyPassphrase` |
| 只设置 `privateKeyPath` | 保留私钥认证 |
| 同时设置密码与私钥，未强制密码模式 | 保留先尝试密钥的原有行为 |

`isPasswordOnly()` 返回实际是否使用密码模式。该模式不读取本地 `~/.ssh/config` 和默认私钥、不使用 SSH agent，也不会回退到用户公钥签名认证；需要明确指定真实地址、端口和用户名，不能使用仅存在于本地 SSH 配置中的别名。支持 password 和单密码 keyboard-interactive，不支持 OTP 等多因素交互。密码必须非空；空格可能是密码的一部分，不会被 trim。SSH 握手仍使用服务器主机密钥签名；此配置不修改 HTTPS TLS 或 Maven GPG 签名行为。

## 查询与资源入口选择

```java
com.iskycc.k8s.api.model.VersionInfo versionInfo = client.getVersion();
java.util.List<com.iskycc.k8s.api.model.Node> nodeSummaries = client.listNodes();
java.util.List<com.iskycc.k8s.api.model.Pod> podSummaries = client.listPods("default");

// 新入口返回完整 JSON，可读取未知字段，也可用于后续更新。
JsonObject podDocument = client.pods("default").get("web-pod");
boolean podExists = client.pods("default").exists("web-pod");
K8sList<JsonObject> namespacePage = client.namespaces().list();

// 非快捷入口的资源使用常量，操作方法与 ConfigMap 相同。
K8sResourceClient jobs = client.resource(K8sResources.JOBS).inNamespace("default");
K8sResourceClient persistentVolumes = client.resource(K8sResources.PERSISTENT_VOLUMES);
```

| 资源 | 入口示例 | 作用域 |
| --- | --- | --- |
| Pod / Service / Deployment | `client.pods(ns)` / `services(ns)` / `deployments(ns)` | 指定命名空间 |
| ConfigMap / Secret | `client.configMaps(ns)` / `secrets(ns)` | 指定命名空间；Secret 正文不要直接输出到日志 |
| StatefulSet / DaemonSet / Job / CronJob | `client.resource(K8sResources.STATEFUL_SETS)` 等，再 `.inNamespace(ns)` | 指定命名空间 |
| PVC / Ingress / RoleBinding | `PERSISTENT_VOLUME_CLAIMS` / `INGRESSES` / `ROLE_BINDINGS` 常量入口 | 指定命名空间 |
| Namespace / Node | `client.namespaces()` / `nodes()` | 集群 |
| PV / StorageClass / ClusterRole | `PERSISTENT_VOLUMES` / `STORAGE_CLASSES` / `CLUSTER_ROLES` 常量入口 | 集群 |
| CRD 定义本身 | `client.resource(K8sResources.CUSTOM_RESOURCE_DEFINITIONS)` | 集群 |
| CRD 的实例 | `client.resource("sample.example/v1", "widgets")` | 由 Discovery 决定 |

`exists()` 会发起 GET；只有 404 返回 false，401/403 和网络错误仍抛出异常。先 `exists()` 再 `create()` 不构成原子操作，其他调用者可能在两次请求之间创建同名资源，需处理创建时的 409。

## 公共方法与 Kubernetes 动作

| Java 方法 | HTTP / Kubernetes 动作 | 返回与说明 |
| --- | --- | --- |
| `get(name)` | GET / get | 完整 `JsonObject` |
| `exists(name)` | GET / get | 仅 404 返回 false，其余错误抛出 |
| `list()` / `list(options)` | GET 集合 / list | `K8sList<JsonObject>`，含分页元数据 |
| `listAll(options)` | 连续 GET / list | 收集剩余所有页到 `List<JsonObject>` |
| `create(json, options)` | POST / create | 支持 name、generateName 及无名称的审查资源，必需字段由服务端校验 |
| `replace(name, json, options)` | PUT / update | 完整替换，必须提供 resourceVersion |
| `patch(name, type, json, options)` | PATCH / patch | JSON Patch、Merge Patch、Strategic Merge Patch |
| `apply(json, options, force)` | PATCH / patch | Server-Side Apply，必须指定 fieldManager |
| `delete(name, options)` | DELETE / delete | 返回 Status 或资源对象；204 返回 null |
| `deleteIfExists(name, options)` | DELETE / delete | 仅 404 返回 false |
| `deleteCollection(selection, options)` | DELETE 集合 / deletecollection | 删除当前作用域中匹配选择器的资源 |
| `subresource(name, subresource)` | REST 子资源 | 支持 get/create/replace/patch |
| `scale(name, replicas)` | PATCH scale / patch | 对支持 scale 的工作负载设置副本数 |

`create`、`replace`、`patch`、`delete` 均有省略 options 的重载；`listAll()` 可省略列表参数。写入的完整资源使用 Gson `JsonObject`，创建或替换时会补齐缺失的 apiVersion、kind 和 namespace，并检查它们与入口是否一致；不会修改调用方传入的 JSON。

原有 `listPods`、`listServices`、`listDeployments` 等返回简化 POJO 的方法继续保留，适合读取摘要。**不要把这些字段不完整的 POJO 序列化后用于 PUT**，否则会丢失未映射字段。更新完整资源时使用新接口的 `get()` 返回值。

## 创建、读取、修改、删除示例

```java
JsonObject manifest = JsonParser.parseString(
        "{\"metadata\":{\"name\":\"tool-settings\"},"
        + "\"data\":{\"feature\":\"off\"}}")
        .getAsJsonObject();

// 先让 API Server 执行校验，不实际保存。
configMaps.create(manifest, WriteOptions.builder().dryRun(true).build());
JsonObject created = configMaps.create(manifest);

// GET 保留完整 JSON；PUT 同时携带服务端返回的 resourceVersion。
JsonObject current = configMaps.get("tool-settings");
current.getAsJsonObject("data").addProperty("feature", "on");
JsonObject updated = configMaps.replace("tool-settings", current);

// 删除时验证 UID，避免同名对象被重建后误删新对象。
configMaps.delete("tool-settings", DeleteOptions.builder()
        .uid(updated.getAsJsonObject("metadata").get("uid").getAsString())
        .propagationPolicy(DeleteOptions.PropagationPolicy.Background)
        .build());
```

PUT 是完整替换，遗漏字段可能被清除。409 表示版本冲突，应重新 GET 并根据业务规则合并后再提交；客户端不会自动覆盖。资源不可变字段、准入策略和 RBAC 都由服务端校验。相关语义见 [Kubernetes API Concepts](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources)。

DELETE 成功表示服务端接受删除请求；finalizer、宽限期和级联删除可能使对象继续存在。客户端没有自动等待最终消失。`gracePeriodSeconds(0)`、Foreground/Background/Orphan 和 resourceVersion/UID 前置条件均可通过 `DeleteOptions` 指定。

忽略不存在的对象，或按标签删除一组资源：

```java
boolean deleteAccepted = configMaps.deleteIfExists("obsolete-settings", null);

// dryRun(true) 仅请求服务端校验；改为 false 才会实际删除匹配的 Job。
JsonObject deletePreview = client.resource(K8sResources.JOBS).inNamespace("default")
        .deleteCollection(ListOptions.builder().labelSelector("cleanup-group=demo").build(),
                DeleteOptions.builder().dryRun(true)
                        .propagationPolicy(DeleteOptions.PropagationPolicy.Background).build());
```

`deleteIfExists` 返回 true 表示服务端接受删除，false 表示 404；它不等待对象消失。集合删除要求资源支持 `deletecollection`，且应明确提供选择器。

## Deployment 与 Service 示例

在已有 `default` 命名空间中声明一个工作负载和对应 Service。先替换示例镜像地址，确保 token 具有相应的 `patch` 权限及准入许可。以下两个 Apply 会分别写入资源，不构成跨资源事务。

```java
JsonObject deploymentManifest = JsonParser.parseString(
        "{\"metadata\":{\"name\":\"web\"},\"spec\":{\"replicas\":2,"
        + "\"selector\":{\"matchLabels\":{\"app\":\"web\"}},"
        + "\"template\":{\"metadata\":{\"labels\":{\"app\":\"web\"}},"
        + "\"spec\":{\"containers\":[{\"name\":\"web\","
        + "\"image\":\"registry.example.com/team/web:1.0.0\","
        + "\"ports\":[{\"containerPort\":8080}]}]}}}}")
        .getAsJsonObject();
client.deployments("default").apply(deploymentManifest, "my-java-tool", false);

JsonObject serviceManifest = JsonParser.parseString(
        "{\"metadata\":{\"name\":\"web\"},\"spec\":{\"selector\":{\"app\":\"web\"},"
        + "\"ports\":[{\"name\":\"http\",\"port\":80,\"targetPort\":8080}]}}")
        .getAsJsonObject();
client.services("default").apply(serviceManifest, "my-java-tool", false);

client.deployments("default").scale("web", 3);
```

通用入口补齐 apiVersion、kind 和 namespace；spec 等资源专属字段仍由调用方提供。Apply 返回只表示请求完成，不会等待 Deployment 就绪、Pod 拉取镜像成功或 Service 拥有可用端点。`force=false` 遇到字段管理冲突会抛出 409。

## 选择器、分页和作用域

```java
ListOptions options = ListOptions.builder()
        .labelSelector("app in (api,web),environment=production")
        .fieldSelector("status.phase=Running")
        .limit(100)
        .timeoutSeconds(30)
        .build();

K8sResourceClient pods = client.resource(K8sResources.PODS).inAllNamespaces();
K8sList<JsonObject> first = pods.list(options);
String next = first.getMetadata().getContinueToken();
if (next != null && !next.isEmpty()) {
    K8sList<JsonObject> second = pods.list(options.withContinueToken(next));
}

// 或直接收集所有页。资源很多时应使用上面的逐页方式降低内存占用。
java.util.List<JsonObject> all = pods.listAll(options);
```

选择器和 continue token 按原文传入，不要手动 URL 编码。列表保留 `resourceVersion`、`continue`、`remainingItemCount`。自动分页遇到 410 会抛出异常，不会悄悄重启并混合不同快照；遇到重复 continue token 或不同页的 resourceVersion 改变也会失败。

新资源入口的命名空间规则：

- 命名空间资源通过 `inNamespace("default")` 指定作用域；跨命名空间仅用于 list。
- 单对象读写和集合删除必须指定具体命名空间，不从正文推断目标。
- 集群资源（Namespace、Node、PV、ClusterRole 等）不能指定命名空间。
- 新接口的 `inNamespace("all")` 是实际名为 all 的命名空间；只有旧 `listPods("all")` 等快捷查询保留 all 表示跨命名空间的兼容约定。
- `deleteCollection` 不传选择器会删除当前作用域的整个集合，且要求服务端支持 `deletecollection`。不要把列表中的 `limit` 当作删除数量的通用上限。

## Patch 与 Apply

```java
// JSON Merge Patch：对象字段合并，null 表示删除；数组通常整体替换。
configMaps.patch("tool-settings", PatchType.MERGE_PATCH,
        JsonParser.parseString("{\"data\":{\"feature\":\"on\",\"obsolete\":null}}"));

// JSON Patch：按 RFC 6902 操作数组，可用 test 做乐观并发控制。
client.deployments("default").patch("web", PatchType.JSON_PATCH,
        JsonParser.parseString("[{\"op\":\"replace\",\"path\":\"/spec/replicas\",\"value\":3}]"));

// Strategic Merge Patch：按服务端定义的合并键更新内置资源列表。
client.deployments("default").patch("web", PatchType.STRATEGIC_MERGE_PATCH,
        JsonParser.parseString("{\"spec\":{\"template\":{\"spec\":{\"containers\":["
                + "{\"name\":\"web\",\"image\":\"registry.example.com/team/web:1.0.1\"}]}}}}"));

// Apply：传入希望管理的字段，使用稳定的 fieldManager。
configMaps.apply(manifest, "my-java-tool", false);
// 也可传 WriteOptions，支持 dryRun、fieldManager、fieldValidation。
configMaps.apply(manifest, WriteOptions.builder()
        .fieldManager("my-java-tool").fieldValidation("Strict").dryRun(true).build(), false);
```

`STRATEGIC_MERGE_PATCH` 适用于服务端支持的内置资源，CRD 不支持这种合并方式。Apply 使用 `application/apply-patch+yaml`，正文发送 JSON（YAML 的子集），因此没有额外 YAML 解析依赖。`force=true` 会接管冲突字段的管理权，调用方必须显式选择；默认示例使用 false。见 [Server-Side Apply](https://kubernetes.io/docs/reference/using-api/server-side-apply/)。

## 资源覆盖与 CRD

`K8sResources` 提供常见内置资源常量，采用明确版本，不按 Kind 猜复数：

| 类别 | 常量覆盖 |
| --- | --- |
| 核心与配置 | Namespace、Node、Pod、Service、ConfigMap、Secret、ServiceAccount、Endpoints、Event、ResourceQuota、LimitRange、ReplicationController |
| 工作负载 | Deployment、StatefulSet、DaemonSet、ReplicaSet、ControllerRevision、Job、CronJob |
| 网络 | Ingress、IngressClass、NetworkPolicy、EndpointSlice |
| 存储 | PV、PVC、StorageClass、CSIDriver、CSINode、CSIStorageCapacity、VolumeAttachment |
| 权限 | Role、RoleBinding、ClusterRole、ClusterRoleBinding |
| 认证与授权审查 | TokenReview、SubjectAccessReview、SelfSubjectAccessReview、LocalSubjectAccessReview、SelfSubjectRulesReview |
| 策略与调度 | HPA、PodDisruptionBudget、PriorityClass、RuntimeClass、Lease |
| 扩展与准入 | CRD、APIService、CertificateSigningRequest、MutatingWebhookConfiguration、ValidatingWebhookConfiguration |

常量不是完整资源清单，也不保证目标集群安装了该版本。对于其他内置资源、聚合 API、Gateway API、监控 CRD 或未来版本，可通过 Discovery 获取定义。自定义复数名称、Kind 和版本名可包含合法的连字符，规则参考 [Kubernetes CRD 校验实现](https://github.com/kubernetes/apiextensions-apiserver/blob/master/pkg/apis/apiextensions/validation/validation.go)。

```java
java.util.List<String> versions = client.discoverApiVersions();
java.util.List<com.iskycc.k8s.api.ApiResource> resources = client.discoverResources("sample.example/v1");

// 通过 Discovery 识别 Widget 的作用域、Kind 和 verbs。
K8sResourceClient widgets = client.resource("sample.example/v1", "widgets")
        .inNamespace("default");
widgets.create(JsonParser.parseString(
        "{\"metadata\":{\"name\":\"sample\"},\"spec\":{\"size\":3}}")
        .getAsJsonObject());

// 已知定义时也可直接构造，不要求对 Discovery 端点的访问权限。
ResourceDefinition widget = ResourceDefinition.namespaced("sample.example/v1", "widgets", "Widget");
K8sResourceClient explicit = client.resource(widget).inNamespace("default");
```

Discovery 入口会在请求前拒绝服务端未声明的动作；手动定义和静态常量交给服务端判断。Discovery 的 verbs 反映 API 能力，**不代表当前 token 拥有 RBAC 权限**。资源的实际 CRUD 能力取决于服务端，例如只读指标资源无法被工具变成可写资源。Discovery 包含子资源条目，但它们必须通过 `subresource()` 访问。

审查类资源通常只支持 create，不要求资源名称。例如 [SelfSubjectAccessReview](https://kubernetes.io/docs/reference/access-authn-authz/authorization/) 可检查当前身份是否拥有指定权限，结果从响应的 `status.allowed` 读取。

```java
JsonObject accessReview = client.resource(K8sResources.SELF_SUBJECT_ACCESS_REVIEWS)
        .create(JsonParser.parseString(
                "{\"spec\":{\"resourceAttributes\":{\"namespace\":\"default\","
                + "\"group\":\"apps\",\"resource\":\"deployments\",\"verb\":\"patch\"}}}")
                .getAsJsonObject());
boolean allowed = accessReview.getAsJsonObject("status").get("allowed").getAsBoolean();
```

该审查需要有权限调用对应的审查 API。查询权限的结果不替代实际操作时的服务端校验，也不保证稍后的请求一定成功。

## 子资源和底层调用

```java
client.deployments("default").scale("web", 3);
JsonObject scale = client.deployments("default").subresource("web", "scale").get();

explicit.subresource("sample", "status").patch(PatchType.MERGE_PATCH,
        JsonParser.parseString("{\"status\":{\"ready\":true}}"), null);

// POST serviceaccounts/{name}/token；调用方负责到期前更新 token。
JsonObject tokenRequest = JsonParser.parseString(
        "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"TokenRequest\","
        + "\"spec\":{\"audiences\":[\"<集群接受的 audience>\"],\"expirationSeconds\":3600}}")
        .getAsJsonObject();
JsonObject result = client.resource(K8sResources.SERVICE_ACCOUNTS).inNamespace("default")
        .subresource("reader", "token").create(tokenRequest, null);
```

子资源正文保留自己的 Kind，例如 Scale、Eviction、TokenRequest；工具不强制改为父资源的 Kind。子资源权限、可用动作以及是否需要 resourceVersion 由服务端决定。

底层 `client.request(method, path, query, body, contentType)` 支持 GET、HEAD、OPTIONS、POST、PUT、PATCH、DELETE，返回 `ApiResponse` 的状态码、正文和多值响应头，可读取 Warning 等信息。`getRaw(path)` 保持兼容。该接口是缓冲整个响应的同步 REST 接口，不适合 watch、日志跟随、exec/attach、port-forward 等长连接或协议升级操作。

```java
java.util.Map<String, String> rawQuery = new java.util.LinkedHashMap<String, String>();
rawQuery.put("labelSelector", "app=web");
rawQuery.put("limit", "20");
ApiResponse rawResponse = client.request("GET", "/api/v1/namespaces/default/pods",
        rawQuery, null, null);
int httpStatus = rawResponse.getStatusCode();
java.util.List<String> warnings = rawResponse.getHeader("Warning");
JsonObject rawDocument = JsonParser.parseString(rawResponse.getBody()).getAsJsonObject();
```

query 值按原文传入，不预先编码；GET/HEAD 的 body 必须为 null，有正文时必须提供 Content-Type。原始接口不会按资源定义补齐或验证正文身份，调用方负责路径和正文一致。非 2xx 响应直接抛出异常，不会返回 `ApiResponse`。

## 请求参数速查

参数对象均通过 `builder().…build()` 构造。未传 options 或传入 null 使用服务器默认行为；`apply` 例外，必须通过字符串重载或 WriteOptions 提供 fieldManager。

| 参数对象 | 方法 | 作用与约束 |
| --- | --- | --- |
| `ListOptions` | `labelSelector` / `fieldSelector` | 标签或字段筛选，字段支持范围由资源类型决定 |
| `ListOptions` | `limit(long)` / `continueToken(String)` | 页大小与不透明续页 token；limit 非负 |
| `ListOptions` | `resourceVersion` / `resourceVersionMatch` | 版本条件；match 为 Exact 或 NotOlderThan，必须同时提供 resourceVersion |
| `ListOptions` | `timeoutSeconds(long)` | 服务端请求超时，必须大于 0；客户端 readTimeoutMs 独立配置 |
| `WriteOptions` | `dryRun(true)` | 只请求服务端校验，不保存资源 |
| `WriteOptions` | `fieldManager(String)` | 字段管理者标识，不能为空且最长 128 字符；Apply 必填 |
| `WriteOptions` | `fieldValidation(String)` | Ignore、Warn 或 Strict，是否支持取决于服务端版本 |
| `DeleteOptions` | `uid` / `resourceVersion` | 删除前置条件，防止删除已被替换或更新的对象 |
| `DeleteOptions` | `gracePeriodSeconds(long)` | 非负宽限期，具体支持取决于资源 |
| `DeleteOptions` | `propagationPolicy(...)` | 枚举 Foreground、Background、Orphan |
| `DeleteOptions` | `dryRun(true)` | 只校验删除请求，不实际删除 |

`subresource().create/replace/patch` 的 options 参数不能省略，不需要附加参数时传 null。子资源没有通用 `delete` 方法，特殊 API 可使用底层 `request` 并遵循服务端协议。

## 错误与兼容性

```java
try {
    configMaps.patch("tool-settings", PatchType.MERGE_PATCH,
            JsonParser.parseString("{\"data\":{\"feature\":\"on\"}}"));
} catch (K8sApiException e) {
    switch (e.getStatusCode()) {
        case 401:
            throw new IllegalStateException("凭据无效或过期，请更新 token 后重建客户端", e);
        case 403:
            throw new IllegalStateException("当前身份没有目标资源操作权限，请检查 RBAC", e);
        case 409:
            throw new IllegalStateException("发生冲突，请重新读取资源并按业务规则合并", e);
        case -1:
            throw new IllegalStateException("网络或 TLS 失败；写入结果可能未知，请核对资源状态", e);
        default:
            throw e;
    }
}
```

| 状态 | 调用方处理方式 |
| --- | --- |
| 404 | 检查对象名、命名空间及 API 版本；仅 exists/deleteIfExists 将它转为 false |
| 409 | 根据 create、PUT 或 Apply 区分同名、版本或字段所有权冲突；不要无条件覆盖 |
| 410 | 列表续页 token 过期；调用方决定是否重新开始整个列表读取 |
| 422 | 检查字段、必需值或准入规则，按需读取 `getReason()`、`getStatusMessage()` |
| 429 | 检查 `getResponseHeaders().get("retry-after")`，由调用方安排限速与重试 |
| -1 | 检查 cause、网络、CA 和主机名；写请求断线不代表服务端一定未执行 |

错误正文可能包含敏感数据，默认不要直接打印 `getResponseBody()` 或完整 Secret。参数/作用域不合法会抛 `IllegalArgumentException`；Discovery 明确不支持的动作抛 `UnsupportedOperationException`；解析或分页异常可表现为 `K8sToolsException`，不会全部转为 HTTP 状态码。

- HTTP 非 2xx 抛出 `K8sApiException`，可读取 `getStatusCode()`、`getReason()`、`getStatusMessage()`、`getResponseBody()` 和 `getResponseHeaders()`；网络错误状态码为 -1，并保留 cause。
- 409、410、422、429 等均交给调用方处理；不会自动重试写请求、吞掉权限错误或自动覆盖版本冲突。
- 异常默认消息只包含 HTTP 状态码，避免日志自动输出 Secret 等正文；确有需要时由调用方读取错误详情。
- HTTP 重定向不跟随，避免 Bearer Token 被转发到其他地址。
- 兼容原有 GET/HEAD 的 TLS 自动降级；写入不触发自动降级或重试。若同一客户端此前已被 GET 降级，后续请求仍使用降级后的配置。写入应用建议显式配置 CA 并关闭 `tlsAutoFallback`。
- `slf4j-nop` 已改为 optional，不会作为库的传递依赖强制关闭下游日志。HTTP 采用兼容 Java 8 的 [Apache HttpClient 5.6.4](https://hc.apache.org/httpcomponents-client-5.6.x/5.6.4/httpclient5/summary.html)，解决 Java 8 原生 `HttpURLConnection` 无法发送 PATCH 的限制。

能力核对和测试边界见 [工具库完整性核对](api-completeness.md)。

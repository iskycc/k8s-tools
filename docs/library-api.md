# Java 工具库：资源增删查改

本文适用于正式版 `io.github.iskycc:k8s-tools:1.1.0`，可直接从 Maven Central 引用，无需配置额外仓库。旧版 `1.0.0` 只有只读查询接口，不包含本文的 CRUD API。开发构建 `1.1.0-SNAPSHOT` 可通过 [Central Portal 快照仓库](publishing.md#发布与使用快照)获取，也可在本仓库运行 `mvn clean install` 安装到本地。

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
import com.iskycc.k8s.api.model.K8sList;

K8sApiClient client = K8sApiClient.builder()
        .apiServer("https://192.0.2.10:6443")
        .token("<ServiceAccount token>")
        .caCertPem("<集群 CA 的 PEM 文本>")
        .tlsAutoFallback(false)
        .connectTimeoutMs(10000)
        .readTimeoutMs(30000)
        .build();

K8sResourceClient configMaps = client.configMaps("default");
// 等价于 client.resource(K8sResources.CONFIG_MAPS).inNamespace("default")
```

保留 `K8sApiClient.fromMasterInfo(info)`，可复用 SSH 获取的凭据。SSH 获取流程的权限、副作用和默认 TLS 行为见 [README](../README.md)。

`client.pods(ns)`、`services(ns)`、`deployments(ns)`、`configMaps(ns)`、`secrets(ns)` 是常用资源入口；`namespaces()`、`nodes()` 是集群资源入口。其他资源统一使用 `resource(K8sResources.XXX)` 或 Discovery。客户端每次请求后释放连接，不要求调用方 `close()`；它不是连接池客户端。

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

## 错误与兼容性

- HTTP 非 2xx 抛出 `K8sApiException`，可读取 `getStatusCode()`、`getReason()`、`getStatusMessage()`、`getResponseBody()` 和 `getResponseHeaders()`；网络错误状态码为 -1，并保留 cause。
- 409、410、422、429 等均交给调用方处理；不会自动重试写请求、吞掉权限错误或自动覆盖版本冲突。
- 异常默认消息只包含 HTTP 状态码，避免日志自动输出 Secret 等正文；确有需要时由调用方读取错误详情。
- HTTP 重定向不跟随，避免 Bearer Token 被转发到其他地址。
- 兼容原有 GET/HEAD 的 TLS 自动降级；写入不触发自动降级或重试。若同一客户端此前已被 GET 降级，后续请求仍使用降级后的配置。写入应用建议显式配置 CA 并关闭 `tlsAutoFallback`。
- `slf4j-nop` 已改为 optional，不会作为库的传递依赖强制关闭下游日志。HTTP 采用兼容 Java 8 的 [Apache HttpClient 5.6.4](https://hc.apache.org/httpcomponents-client-5.6.x/5.6.4/httpclient5/summary.html)，解决 Java 8 原生 `HttpURLConnection` 无法发送 PATCH 的限制。

能力核对和测试边界见 [工具库完整性核对](api-completeness.md)。

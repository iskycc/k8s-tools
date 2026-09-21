# 跨全部 namespace 搜索资源（1.6.0 起）

入口是 `K8sApiClient`，不需要传 namespace。每种资源都有简易版和详细版，均返回全部名称匹配项的 `List`，没有匹配时返回空 List。先按 [Maven 配置](maven-usage.md)引用 `io.github.iskycc:k8s-tools:1.6.0`，通过已有 API 凭据或 [SSH/Redis](redis-cache.md)创建 `client`。

## 最小调用

```java
import com.iskycc.k8s.api.model.PodSummary;
import com.iskycc.k8s.api.model.ResourceSummary;
import com.iskycc.k8s.api.model.ResourceDetails;
import java.util.List;

// 简易版：每个 Pod 只有 namespace、name、containerNames。
List<PodSummary> pods = client.searchPods("nginx");
for (PodSummary pod : pods) {
    System.out.println(pod.getNamespace() + "/" + pod.getPodName()
            + " containers=" + pod.getContainerNames());
}

// ConfigMap、Service 等简易结果只有 namespace、name，不携带完整正文。
List<ResourceSummary> configs = client.searchConfigMaps("app-config");
List<ResourceSummary> services = client.searchServices("gateway");

// 详细版：身份、常用元数据和完整的资源字段。
List<ResourceDetails> podDetails = client.searchPodsDetailed("nginx");
List<ResourceDetails> configDetails = client.searchConfigMapsDetailed("app-config");
List<ResourceDetails> serviceDetails = client.searchServicesDetailed("gateway");
```

`getPodName()` 是 `getName()` 的别名，底层只保存一份名称。`getContainerNames()` 只包含 `spec.containers` 的普通容器，不包含 init/ephemeral 容器。简易结果不保留原始 JSON，容器名称列表不可修改；结果的外层 List 可由调用方自行排序或筛选。

## 公共方法清单

下列方法都有 `(String keyword)` 和 `(String keyword, ListOptions options)` 两个重载。详细版统一返回 `List<ResourceDetails>`。

| 资源 | 简易版 | 详细版 | 简易 List 元素 |
| --- | --- | --- | --- |
| Pod | `searchPods` | `searchPodsDetailed` | `PodSummary` |
| ConfigMap | `searchConfigMaps` | `searchConfigMapsDetailed` | `ResourceSummary` |
| Service | `searchServices` | `searchServicesDetailed` | `ResourceSummary` |
| Deployment | `searchDeployments` | `searchDeploymentsDetailed` | `ResourceSummary` |
| StatefulSet | `searchStatefulSets` | `searchStatefulSetsDetailed` | `ResourceSummary` |
| DaemonSet | `searchDaemonSets` | `searchDaemonSetsDetailed` | `ResourceSummary` |
| ReplicaSet | `searchReplicaSets` | `searchReplicaSetsDetailed` | `ResourceSummary` |
| Job | `searchJobs` | `searchJobsDetailed` | `ResourceSummary` |
| CronJob | `searchCronJobs` | `searchCronJobsDetailed` | `ResourceSummary` |
| Ingress | `searchIngresses` | `searchIngressesDetailed` | `ResourceSummary` |
| PersistentVolumeClaim | `searchPersistentVolumeClaims` | `searchPersistentVolumeClaimsDetailed` | `ResourceSummary` |
| Secret | `searchSecrets` | `searchSecretsDetailed` | `ResourceSummary` |
| ServiceAccount | `searchServiceAccounts` | `searchServiceAccountsDetailed` | `ResourceSummary` |
| NetworkPolicy | `searchNetworkPolicies` | `searchNetworkPoliciesDetailed` | `ResourceSummary` |

其他命名空间资源或 CRD 使用通用方法，必须显式提供正确的资源定义：

```java
ResourceDefinition widgets = ResourceDefinition.namespaced("sample.example/v1", "widgets", "Widget");
List<ResourceSummary> summaries = client.searchResources(widgets, "demo");
List<ResourceDetails> details = client.searchResourcesDetailed(widgets, "demo");
// 两个通用方法也支持第三个 ListOptions 参数。
```

导入 `com.iskycc.k8s.api.ResourceDefinition`。通用简易版始终只返回 namespace/name，即使定义是 Pod；需要容器名称时使用 `searchPods`。Node、Namespace、PV 等集群资源不属于跨 namespace 搜索范围，传入会拒绝，应使用原来的资源列表接口。

## 详细结果怎么读取

`ResourceDetails` 保留服务端完整资源字段，不受旧 POJO 字段覆盖范围限制。[Kubernetes 集合响应](https://kubernetes.io/docs/reference/using-api/api-concepts/#collections)中的单个对象可能省略 apiVersion/kind，搜索入口会用明确的 ResourceDefinition 补齐这两个字段，不覆盖服务端已有值。常用入口如下：

| getter | 内容 |
| --- | --- |
| `getNamespace()` / `getName()` | namespace 和资源名 |
| `getApiVersion()` / `getKind()` | 资源版本、类型 |
| `getUid()` / `getResourceVersion()` | 唯一 ID、资源版本 |
| `getCreationTimestamp()` / `getDeletionTimestamp()` | 创建/删除时间，ISO 字符串；不存在时为 null |
| `getLabels()` / `getAnnotations()` | 不可修改的 `Map<String, String>`；缺失时为空 Map |
| `getMetadata()` | 完整 metadata，含 ownerReferences、finalizers 等 |
| `getSpec()` | Pod 容器、镜像、节点；Service 类型、IP、端口、selector；工作负载副本数、模板等 |
| `getStatus()` | Pod phase/IP/容器状态、工作负载 readyReplicas/conditions 等 |
| `getData()` / `getBinaryData()` | ConfigMap/Secret 数据，base64 值保持编码，不自动解码 |
| `toJson()` | 完整 Gson `JsonObject`，包含 type、immutable 及自定义/未知字段 |

对象类型的 getter 返回独立 `JsonObject` 副本，缺失时为空对象；字符串缺失时返回 null。返回 JSON 的修改不会影响同一结果的后续读取。使用具体字段前仍需检查是否存在，例如 Pending Pod 可能还没有 podIP：

```java
for (ResourceDetails pod : client.searchPodsDetailed("nginx")) {
    com.google.gson.JsonObject status = pod.getStatus();
    String phase = status.has("phase") ? status.get("phase").getAsString() : "Unknown";
    System.out.println(pod.getNamespace() + "/" + pod.getName() + " phase=" + phase);
    // pod.getSpec().getAsJsonArray("containers")：名称、镜像、端口、资源要求等。
}
// service.getSpec().getAsJsonArray("ports")：port、targetPort、protocol 等。
// config.getData()：ConfigMap 键值。避免将配置内容或 Secret 正文写入日志。
```

`toString()` 只显示资源身份；对对象进行 JSON 序列化或调用 `toJson()` 会包含详细数据。详细结果是查询快照，写入时仍需遵守 [resourceVersion 与并发更新规则](library-api.md#创建读取修改删除示例)。

## 匹配、分页与权限

- 只对 `metadata.name` 做区分大小写的字面子串匹配；不是正则表达式，也不搜索 namespace、label 或配置内容。`"web"` 会同时返回 `web`、`web-api`，不同 namespace 的同名对象都保留。
- SDK 不优先返回完整名称，不要求唯一命中，也不默认过滤 Pending/Failed/删除中的 Pod。空白关键词拒绝；列出所有对象使用 `client.resource(definition).inAllNamespaces().listAll(...)`。
- 每次调用只搜索一种资源。返回顺序沿用服务端分页顺序，不承诺固定排序。简易和详细方法是各自独立的查询，两次调用之间资源可能发生变化。
- 无 options 或传 null 时请求每页 100 条，并自动跟随 continue，返回所有匹配项。`limit` 仅控制每页数量，不限制最终结果数量；显式 options 未设 limit 时由服务端决定页大小。禁止传入 continueToken，避免从中间页搜索导致漏项。
- 名称关键词在客户端过滤；简易版也需要从服务端读取完整资源。当前实现先收集全部页，再筛选，内存和请求开销与选择器命中的资源总量有关。大集群优先加 label/field selector，或自行使用逐页接口。
- 需要目标资源的集群范围 `list` 权限（通常由 ClusterRoleBinding 授予）。403/404/410、网络失败、重复 continue 或跨页 resourceVersion 不一致会抛错，不把失败当成空列表、不返回部分匹配，也不自动刷新凭据或从首页重来。

例如只搜指定标签的 Running Pod，仍然跨所有 namespace：

```java
ListOptions options = ListOptions.builder()
        .labelSelector("app=nginx")
        .fieldSelector("status.phase=Running")
        .limit(100)
        .build();
List<PodSummary> pods = client.searchPods("nginx", options);
List<ResourceDetails> detailedPods = client.searchPodsDetailed("nginx", options);
```

导入 `com.iskycc.k8s.api.ListOptions`。字段选择器是否支持取决于对应的资源和 API Server，不能把 Pod 的 `status.phase` 选择器直接套给其他资源。

查到 Pod 后可将同一结果的 namespace/name 和选定容器传给 `exec` / `execShell`。完整流程见 [Pod 搜索并执行 main 示例](examples/pod-search-exec.md)；该示例为了执行命令额外要求唯一目标，SDK 搜索本身始终返回全部匹配结果。

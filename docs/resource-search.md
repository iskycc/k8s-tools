# 跨全部 namespace 搜索资源（1.6.0 起）

入口是 `K8sApiClient`，不需要传 namespace。每种资源都有简易版和详细版，均返回全部名称匹配项的 `List`，没有匹配时返回空 List。通过已有 API 凭据或 [SSH/Redis](redis-cache.md)创建 `client`。

**`1.6.2` 新增类型化详细结果，`1.6.1` 不包含这些子类。** 按 [Maven 配置](maven-usage.md)引用 `io.github.iskycc:k8s-tools:1.6.2`，发布完成后可下载；也可本地 `mvn install` 后引用 `1.6.2-SNAPSHOT`。`1.6.0`、`1.6.1` 的具体资源详细搜索均返回 `List<ResourceDetails>`；`1.6.2` 改为 `List<PodDetails>` 等具体类型。旧代码的 `List<ResourceDetails>` 变量需改为对应子类列表，或 `List<? extends ResourceDetails>`；不能将 `List<PodDetails>` 直接赋给 `List<ResourceDetails>`。通用方法 `searchResourcesDetailed` 的返回类型保持不变。

## 最小调用

```java
import com.iskycc.k8s.api.model.PodSummary;
import com.iskycc.k8s.api.model.ResourceSummary;
import com.iskycc.k8s.api.model.ResourceDetails;
import com.iskycc.k8s.api.model.PodDetails;
import com.iskycc.k8s.api.model.ConfigMapDetails;
import com.iskycc.k8s.api.model.ServiceDetails;
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
List<PodDetails> podDetails = client.searchPodsDetailed("nginx");
List<ConfigMapDetails> configDetails = client.searchConfigMapsDetailed("app-config");
List<ServiceDetails> serviceDetails = client.searchServicesDetailed("gateway");
```

`getPodName()` 是 `getName()` 的别名，底层只保存一份名称。`getContainerNames()` 只包含 `spec.containers` 的普通容器，不包含 init/ephemeral 容器。简易结果不保留原始 JSON，容器名称列表不可修改；结果的外层 List 可由调用方自行排序或筛选。

## 公共方法清单

下列方法都有 `(String keyword)` 和 `(String keyword, ListOptions options)` 两个重载。详细版返回对应子类的 List，全部子类继承 `ResourceDetails`；Deployment、StatefulSet、DaemonSet、ReplicaSet、Job 还共享中间类 `WorkloadDetails` 的 Pod 模板读取方法。

| 资源 | 简易版 | 详细版 | 简易 List 元素 | 详细 List 元素 |
| --- | --- | --- | --- | --- |
| Pod | `searchPods` | `searchPodsDetailed` | `PodSummary` | `PodDetails` |
| ConfigMap | `searchConfigMaps` | `searchConfigMapsDetailed` | `ResourceSummary` | `ConfigMapDetails` |
| Service | `searchServices` | `searchServicesDetailed` | `ResourceSummary` | `ServiceDetails` |
| Deployment | `searchDeployments` | `searchDeploymentsDetailed` | `ResourceSummary` | `DeploymentDetails` |
| StatefulSet | `searchStatefulSets` | `searchStatefulSetsDetailed` | `ResourceSummary` | `StatefulSetDetails` |
| DaemonSet | `searchDaemonSets` | `searchDaemonSetsDetailed` | `ResourceSummary` | `DaemonSetDetails` |
| ReplicaSet | `searchReplicaSets` | `searchReplicaSetsDetailed` | `ResourceSummary` | `ReplicaSetDetails` |
| Job | `searchJobs` | `searchJobsDetailed` | `ResourceSummary` | `JobDetails` |
| CronJob | `searchCronJobs` | `searchCronJobsDetailed` | `ResourceSummary` | `CronJobDetails` |
| Ingress | `searchIngresses` | `searchIngressesDetailed` | `ResourceSummary` | `IngressDetails` |
| PersistentVolumeClaim | `searchPersistentVolumeClaims` | `searchPersistentVolumeClaimsDetailed` | `ResourceSummary` | `PersistentVolumeClaimDetails` |
| Secret | `searchSecrets` | `searchSecretsDetailed` | `ResourceSummary` | `SecretDetails` |
| ServiceAccount | `searchServiceAccounts` | `searchServiceAccountsDetailed` | `ResourceSummary` | `ServiceAccountDetails` |
| NetworkPolicy | `searchNetworkPolicies` | `searchNetworkPoliciesDetailed` | `ResourceSummary` | `NetworkPolicyDetails` |

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

公共基类只包含元数据和原有的 JSON 入口，不加入 Pod 或 Service 专属 getter；ConfigMap 不会读取 Pod 状态。新增 `getGeneration()`、`getFinalizers()`、`getOwnerReferences()` 分别读取 metadata 中的代数、终结器和属主引用。

子类的字符串、数值和布尔字段缺失时为 `null`；数值与布尔值使用包装类型，明确区分“未上报”和 `0` / `false`。List/Map 缺失时为空且不可修改；JSON 对象/数组返回独立副本，缺失时为空。修改副本不影响后续 getter。不会将 Pod 创建时间代替启动时间，也不会为读取 getter 发起额外网络请求。Pending Pod 可能还没有 IP、节点或启动时间。

```java
for (PodDetails pod : client.searchPodsDetailed("nginx")) {
    System.out.println(pod.getNamespace() + "/" + pod.getPodName()
            + " podIP=" + pod.getPodIP() + " hostIP=" + pod.getHostIP()
            + " node=" + pod.getNodeName() + " startTime=" + pod.getStartTime()
            + " phase=" + pod.getPhase() + " containers=" + pod.getContainerNames());
    for (com.iskycc.k8s.api.model.ContainerStatusDetails container : pod.getContainerStatuses()) {
        System.out.println(container.getName() + " ready=" + container.getReady()
                + " restarts=" + container.getRestartCount()
                + " state=" + container.getState().getType()
                + " startedAt=" + container.getStartedAt());
    }
}
// ServiceDetails：service.getPorts().get(0).getPort() / getTargetPort() / getNodePort()
// ConfigMapDetails：config.getDataMap().get("application.yaml")，无需解析 JSON。
// 避免将配置内容、Secret 数据、容器环境变量、命令或错误 message 写入日志。
```

### 各资源的常用 getter

| 详细类型 | 常用直接读取方法 |
| --- | --- |
| `PodDetails` | `getPodName`、`getPodIP/getPodIPs`、`getHostIP/getHostIPs`、`getNodeName`、`getStartTime`、`getPhase`、`getReason/getMessage`、`getQosClass`、`getServiceAccountName`、`getContainerNames/getInitContainerNames/getEphemeralContainerNames`、`getContainers/getInitContainers/getEphemeralContainers`、`getContainerStatuses/getInitContainerStatuses/getEphemeralContainerStatuses`、`getConditions`、`getVolumes`、`getNodeSelector`、`getAffinity`、`getTolerations` |
| `ConfigMapDetails` | `getDataMap`、`getBinaryDataMap`、`getImmutable` |
| `SecretDetails` | `getType`、`getDataMap`、`getImmutable`；base64 值保持编码 |
| `ServiceDetails` | `getType`、`getClusterIP/getClusterIPs`、`getExternalIPs/getExternalName`、`getSelector`、`getPorts`、`getLoadBalancerIPs/getLoadBalancerHostnames`、流量策略和 IP family |
| `DeploymentDetails` | `getReplicas`（期望）、`getCurrentReplicas`（status.replicas）、`getReadyReplicas/getAvailableReplicas/getUnavailableReplicas/getUpdatedReplicas`、`getPaused/getStrategy` |
| `StatefulSetDetails` | `getReplicas`（期望）、`getObservedReplicas`（status.replicas）、`getCurrentReplicas`（当前修订）、就绪/可用/更新副本、`getCurrentRevision/getUpdateRevision`、`getServiceName`、`getVolumeClaimTemplates` |
| `DaemonSetDetails` | `getDesiredNumberScheduled/getCurrentNumberScheduled`、`getNumberReady/getNumberAvailable/getNumberUnavailable/getNumberMisscheduled`、`getUpdatedNumberScheduled/getUpdateStrategy` |
| `ReplicaSetDetails` | `getReplicas/getCurrentReplicas/getReadyReplicas/getAvailableReplicas/getFullyLabeledReplicas` |
| `JobDetails` | `getParallelism/getCompletions/getBackoffLimit`、`getActive/getSucceeded/getFailed/getReady`、`getStartTime/getCompletionTime`、`getSuspend/getActiveDeadlineSeconds` |
| `CronJobDetails` | `getSchedule/getTimeZone/getConcurrencyPolicy/getSuspend`、`getLastScheduleTime/getLastSuccessfulTime`、`getActiveJobs/getJobTemplate/getContainerNames/getContainers` |
| `IngressDetails` | `getIngressClassName/getHosts/getRules/getTls/getDefaultBackend`、`getLoadBalancerIPs/getLoadBalancerHostnames` |
| `PersistentVolumeClaimDetails` | `getPhase/getVolumeName/getStorageClassName/getVolumeMode/getAccessModes`、`getRequestedStorage/getCapacityStorage/getConditions`；容量保持 `1Gi` 等 quantity 字符串 |
| `ServiceAccountDetails` | `getAutomountServiceAccountToken/getSecretNames/getImagePullSecretNames/getSecrets` |
| `NetworkPolicyDetails` | `getPodSelector/getMatchLabels/getPolicyTypes/getIngress/getEgress` |

`WorkloadDetails` 提供 `getTemplate/getSelector/getMatchLabels/getObservedGeneration/getConditions/getContainerNames/getContainers/getInitContainers/getImages`。这些容器来自 Pod 模板，不代表实际运行状态；CronJob 使用自己更深一层的 Job 模板路径。

`ContainerDetails` 可直接读取名称、镜像、拉取策略、工作目录、command/args、资源 requests/limits；端口、env/envFrom、挂载和探针保留 JSON。`ContainerStatusDetails` 提供 name、ready、started、restartCount、imageID、containerID，以及当前 `getState()` 和上一次 `getLastState()`；两者返回 `ContainerStateDetails`，可继续获取 type（waiting/running/terminated）、reason、message、startedAt、finishedAt、exitCode。容器失败后重启时不会把上一次退出码混入当前状态。

`getStartTime()` 对应 Pod 的 `status.startTime`，表示 Kubelet 接受 Pod 的时间；容器启动时间使用 `container.getStartedAt()`，与资源创建时间 `getCreationTimestamp()` 分开。[字段定义见 Kubernetes Pod API](https://kubernetes.io/docs/reference/kubernetes-api/core/pod-v1/)。`ResourceCondition.getStatus()` 保留 `True/False/Unknown`；`ServicePortDetails.getTargetPort()` 将端口名或数值都表示为 String，不丢失命名端口。

未提供专属 getter 的字段继续通过 `getSpec()`、`getStatus()`、`toJson()` 读取，未知字段完整保留。通用查询得到 `ResourceDetails` 后，已确认类型是 Pod 时也可使用 `new PodDetails(resource.toJson())` 创建具体视图；不要把任意 CRD 当成 Pod。

`searchPodsDetailed` 的结果还绑定原客户端，选定一个 Pod 后可直接 `K8sTools.execShell(pod, "ls -al /tmp")` 或 `pod.exec(...)`，无需再次初始化；多容器需指定 `PodExecOptions.container`。手动创建/JSON 重建的 PodDetails 只含数据，需显式 `client.exec(pod, ...)`。绑定不进入 JSON 或日志，刷新客户端后需要重新查询，详见 [PodDetails 执行](pod-exec.md#pod-details-exec)。

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
List<PodDetails> detailedPods = client.searchPodsDetailed("nginx", options);
```

导入 `com.iskycc.k8s.api.ListOptions`。字段选择器是否支持取决于对应的资源和 API Server，不能把 Pod 的 `status.phase` 选择器直接套给其他资源。

查到 Pod 后可将同一结果的 namespace/name 和选定容器传给 `exec` / `execShell`。完整流程见 [Pod 搜索并执行 main 示例](examples/pod-search-exec.md)；该示例为了执行命令额外要求唯一目标，SDK 搜索本身始终返回全部匹配结果。

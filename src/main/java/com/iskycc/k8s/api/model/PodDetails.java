package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.PodExecOptions;
import com.iskycc.k8s.api.PodExecResult;

import java.util.List;
import java.util.Map;

/** Pod 详细结果。普通、初始化和临时容器分别返回，不静默选择某个容器。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class PodDetails extends ResourceDetails {
    // 仅内存绑定，无公开 getter；Gson/Java 序列化不携带客户端及其凭据。
    private final transient K8sApiClient execClient;

    /** 从 JSON 创建未绑定的读取快照；执行时需显式使用 client.exec(pod, ...)。 */
    public PodDetails(JsonObject resource) { this(resource, null); }

    /** 绑定已有客户端，不连接 Redis/SSH、不重新初始化；查询入口自动完成绑定。 */
    public PodDetails(JsonObject resource, K8sApiClient client) {
        super(resource, "v1", "Pod");
        execClient = client;
    }

    /** 复用查询时的客户端执行 argv；多个普通容器时必须用 options 指定。 */
    public PodExecResult exec(String... command) {
        return exec(PodExecOptions.builder().build(), command);
    }

    public PodExecResult exec(PodExecOptions options, String... command) {
        if (execClient == null) {
            throw new IllegalStateException("PodDetails has no client; obtain it from searchPodsDetailed or use client.exec(pod, ...)");
        }
        return exec(execClient, options, command);
    }

    /**
     * 用显式客户端执行未绑定快照；已绑定对象必须使用原客户端，避免跨集群误执行。
     * client.exec(pod, options, command) 复用此入口；不会修改 options 或自动重放命令。
     */
    public PodExecResult exec(K8sApiClient client, PodExecOptions options, String... command) {
        if (client == null) { throw new IllegalArgumentException("client is required"); }
        if (execClient != null && execClient != client) {
            throw new IllegalArgumentException("PodDetails belongs to a different client; query the Pod using the intended client");
        }
        if (options == null) { throw new IllegalArgumentException("options is required"); }
        if (!"Pod".equals(getKind()) || !"v1".equals(getApiVersion())) {
            throw new IllegalArgumentException("Pod exec requires a v1 Pod resource");
        }
        String container = options.getContainer();
        List<String> names = getContainerNames();
        if (container == null) {
            if (names.size() != 1) {
                throw new IllegalArgumentException("Pod must have exactly one regular container, otherwise specify options.container");
            }
            container = names.get(0);
        } else {
            List<String> initNames = getInitContainerNames();
            List<String> ephemeralNames = getEphemeralContainerNames();
            if ((!names.isEmpty() || !initNames.isEmpty() || !ephemeralNames.isEmpty())
                    && !names.contains(container) && !initNames.contains(container) && !ephemeralNames.contains(container)) {
                throw new IllegalArgumentException("Selected container is not present in the PodDetails snapshot");
            }
        }
        PodExecOptions selected = PodExecOptions.builder().container(container).transport(options.getTransport())
                .timeoutMs(options.getTimeoutMs()).maxOutputBytes(options.getMaxOutputBytes()).build();
        return client.exec(getNamespace(), getPodName(), selected, command);
    }

    /** 整条 shell 命令；容器需有 /bin/sh。 */
    public PodExecResult execShell(String command) {
        return execShell(PodExecOptions.builder().build(), command);
    }

    public PodExecResult execShell(PodExecOptions options, String command) {
        if (command == null || command.trim().isEmpty()) { throw new IllegalArgumentException("command is required"); }
        return exec(options, "/bin/sh", "-c", command);
    }

    /** Pod 名称，与 getName 相同。 */
    public String getPodName() { return getName(); }
    /** status.phase；不等同于容器 Ready 状态。 */
    public String getPhase() { return fields.string("status", "phase"); }
    public String getPodIP() { return fields.string("status", "podIP"); }
    public List<String> getPodIPs() { return fields.propertyList("ip", "status", "podIPs"); }
    public String getHostIP() { return fields.string("status", "hostIP"); }
    public List<String> getHostIPs() { return fields.propertyList("ip", "status", "hostIPs"); }
    public String getNodeName() { return fields.string("spec", "nodeName"); }
    /** Kubelet 接受 Pod 的 RFC 3339 时间；不是容器启动时间，也不是 metadata 创建时间。 */
    public String getStartTime() { return fields.string("status", "startTime"); }
    public String getReason() { return fields.string("status", "reason"); }
    public String getMessage() { return fields.string("status", "message"); }
    public String getQosClass() { return fields.string("status", "qosClass"); }
    public String getServiceAccountName() { return fields.string("spec", "serviceAccountName"); }
    public String getRestartPolicy() { return fields.string("spec", "restartPolicy"); }
    public String getSchedulerName() { return fields.string("spec", "schedulerName"); }
    public String getPriorityClassName() { return fields.string("spec", "priorityClassName"); }
    public Integer getPriority() { return fields.integer("spec", "priority"); }
    public Boolean getHostNetwork() { return fields.bool("spec", "hostNetwork"); }
    public String getDnsPolicy() { return fields.string("spec", "dnsPolicy"); }
    public Long getTerminationGracePeriodSeconds() { return fields.longNumber("spec", "terminationGracePeriodSeconds"); }
    public Map<String, String> getNodeSelector() { return fields.stringMap("spec", "nodeSelector"); }
    /** 全部普通容器名称，保持服务端顺序；不包含 init/ephemeral 容器。 */
    public List<String> getContainerNames() { return fields.propertyList("name", "spec", "containers"); }
    public List<String> getInitContainerNames() { return fields.propertyList("name", "spec", "initContainers"); }
    public List<String> getEphemeralContainerNames() { return fields.propertyList("name", "spec", "ephemeralContainers"); }
    public List<String> getImages() { return fields.propertyList("image", "spec", "containers"); }
    public List<ContainerDetails> getContainers() { return fields.objects(ContainerDetails::new, "spec", "containers"); }
    public List<ContainerDetails> getInitContainers() { return fields.objects(ContainerDetails::new, "spec", "initContainers"); }
    public List<ContainerDetails> getEphemeralContainers() { return fields.objects(ContainerDetails::new, "spec", "ephemeralContainers"); }
    public List<ContainerStatusDetails> getContainerStatuses() { return fields.objects(ContainerStatusDetails::new, "status", "containerStatuses"); }
    public List<ContainerStatusDetails> getInitContainerStatuses() { return fields.objects(ContainerStatusDetails::new, "status", "initContainerStatuses"); }
    public List<ContainerStatusDetails> getEphemeralContainerStatuses() { return fields.objects(ContainerStatusDetails::new, "status", "ephemeralContainerStatuses"); }
    public List<ResourceCondition> getConditions() { return fields.objects(ResourceCondition::new, "status", "conditions"); }
    public JsonArray getVolumes() { return fields.array("spec", "volumes"); }
    public JsonArray getTolerations() { return fields.array("spec", "tolerations"); }
    public JsonObject getAffinity() { return fields.object("spec", "affinity"); }
}

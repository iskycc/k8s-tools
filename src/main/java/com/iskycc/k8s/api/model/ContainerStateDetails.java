package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

/** 容器 waiting/running/terminated 状态及时间；启动时间与 Pod 的 startTime 不同。 */
public final class ContainerStateDetails {
    private final JsonFields fields;
    private final JsonFields active;
    private final String type;

    public ContainerStateDetails(JsonObject value) {
        fields = new JsonFields(value);
        String selected = null;
        for (String candidate : new String[]{"waiting", "running", "terminated"}) {
            if (value.has(candidate) && value.get(candidate).isJsonObject()) {
                // API 只允许一种状态；非法多状态不任意挑选，但 toJson 仍保留原文。
                if (selected != null) { selected = null; break; }
                selected = candidate;
            }
        }
        type = selected;
        active = new JsonFields(type == null ? new JsonObject() : fields.object(type));
    }

    /** waiting / running / terminated；未上报或非法多状态时为 null。 */
    public String getType() { return type; }
    public String getReason() { return active.string("reason"); }
    public String getMessage() { return active.string("message"); }
    public String getStartedAt() { return active.string("startedAt"); }
    public String getFinishedAt() { return active.string("finishedAt"); }
    public Integer getExitCode() { return active.integer("exitCode"); }
    public Integer getSignal() { return active.integer("signal"); }
    public String getContainerID() { return active.string("containerID"); }
    public JsonObject toJson() { return fields.object(); }

    @Override public String toString() { return "ContainerStateDetails"; }
}

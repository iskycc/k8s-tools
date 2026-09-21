package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

/** 单个容器的当前状态；缺失数值或布尔字段返回 null，不推测默认值。 */
public final class ContainerStatusDetails {
    private final JsonFields fields;

    public ContainerStatusDetails(JsonObject value) { fields = new JsonFields(value); }

    public String getName() { return fields.string("name"); }
    public String getImage() { return fields.string("image"); }
    public String getImageID() { return fields.string("imageID"); }
    public String getContainerID() { return fields.string("containerID"); }
    public Boolean getReady() { return fields.bool("ready"); }
    public Boolean getStarted() { return fields.bool("started"); }
    public Integer getRestartCount() { return fields.integer("restartCount"); }
    public ContainerStateDetails getState() { return new ContainerStateDetails(fields.object("state")); }
    public ContainerStateDetails getLastState() { return new ContainerStateDetails(fields.object("lastState")); }
    public String getStartedAt() { return getState().getStartedAt(); }
    public String getFinishedAt() { return getState().getFinishedAt(); }
    public String getReason() { return getState().getReason(); }
    public String getMessage() { return getState().getMessage(); }
    public Integer getExitCode() { return getState().getExitCode(); }
    public JsonObject toJson() { return fields.object(); }

    @Override public String toString() { return "ContainerStatusDetails"; }
}

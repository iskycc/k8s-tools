package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

/** 资源 Condition；status 保留 True/False/Unknown 字符串，不折叠 Unknown。 */
public final class ResourceCondition {
    private final JsonFields fields;

    public ResourceCondition(JsonObject value) { fields = new JsonFields(value); }

    public String getType() { return fields.string("type"); }
    public String getStatus() { return fields.string("status"); }
    public String getReason() { return fields.string("reason"); }
    public String getMessage() { return fields.string("message"); }
    public String getLastTransitionTime() { return fields.string("lastTransitionTime"); }
    public String getLastProbeTime() { return fields.string("lastProbeTime"); }
    public String getLastUpdateTime() { return fields.string("lastUpdateTime"); }
    public Long getObservedGeneration() { return fields.longNumber("observedGeneration"); }
    public JsonObject toJson() { return fields.object(); }

    @Override public String toString() { return "ResourceCondition"; }
}

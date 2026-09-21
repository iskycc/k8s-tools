package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

/** DaemonSet 的调度节点数量和就绪状态。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class DaemonSetDetails extends WorkloadDetails {
    public DaemonSetDetails(JsonObject resource) { super(resource, "apps/v1", "DaemonSet"); }

    public Integer getDesiredNumberScheduled() { return fields.integer("status", "desiredNumberScheduled"); }
    public Integer getCurrentNumberScheduled() { return fields.integer("status", "currentNumberScheduled"); }
    public Integer getNumberReady() { return fields.integer("status", "numberReady"); }
    public Integer getNumberAvailable() { return fields.integer("status", "numberAvailable"); }
    public Integer getNumberUnavailable() { return fields.integer("status", "numberUnavailable"); }
    public Integer getNumberMisscheduled() { return fields.integer("status", "numberMisscheduled"); }
    public Integer getUpdatedNumberScheduled() { return fields.integer("status", "updatedNumberScheduled"); }
    public JsonObject getUpdateStrategy() { return fields.object("spec", "updateStrategy"); }
}

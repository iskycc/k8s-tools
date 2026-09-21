package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

/** Deployment 的期望副本和实际状态分别读取。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class DeploymentDetails extends WorkloadDetails {
    public DeploymentDetails(JsonObject resource) { super(resource, "apps/v1", "Deployment"); }

    /** spec.replicas：期望副本数。 */
    public Integer getReplicas() { return fields.integer("spec", "replicas"); }
    /** status.replicas：控制器已观测的副本数。 */
    public Integer getCurrentReplicas() { return fields.integer("status", "replicas"); }
    public Integer getReadyReplicas() { return fields.integer("status", "readyReplicas"); }
    public Integer getAvailableReplicas() { return fields.integer("status", "availableReplicas"); }
    public Integer getUnavailableReplicas() { return fields.integer("status", "unavailableReplicas"); }
    public Integer getUpdatedReplicas() { return fields.integer("status", "updatedReplicas"); }
    public Boolean getPaused() { return fields.bool("spec", "paused"); }
    public JsonObject getStrategy() { return fields.object("spec", "strategy"); }
}

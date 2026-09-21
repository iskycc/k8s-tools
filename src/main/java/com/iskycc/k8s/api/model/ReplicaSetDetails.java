package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

/** ReplicaSet 的期望副本、实际副本和就绪副本。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class ReplicaSetDetails extends WorkloadDetails {
    public ReplicaSetDetails(JsonObject resource) { super(resource, "apps/v1", "ReplicaSet"); }

    public Integer getReplicas() { return fields.integer("spec", "replicas"); }
    public Integer getCurrentReplicas() { return fields.integer("status", "replicas"); }
    public Integer getReadyReplicas() { return fields.integer("status", "readyReplicas"); }
    public Integer getAvailableReplicas() { return fields.integer("status", "availableReplicas"); }
    public Integer getFullyLabeledReplicas() { return fields.integer("status", "fullyLabeledReplicas"); }
}

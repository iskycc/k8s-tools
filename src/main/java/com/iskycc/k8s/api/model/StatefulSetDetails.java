package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

/** StatefulSet 的副本、更新版本和存储模板。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class StatefulSetDetails extends WorkloadDetails {
    public StatefulSetDetails(JsonObject resource) { super(resource, "apps/v1", "StatefulSet"); }

    public Integer getReplicas() { return fields.integer("spec", "replicas"); }
    /** status.currentReplicas：当前修订的副本数。 */
    public Integer getCurrentReplicas() { return fields.integer("status", "currentReplicas"); }
    /** status.replicas：已创建的总副本数。 */
    public Integer getObservedReplicas() { return fields.integer("status", "replicas"); }
    public Integer getReadyReplicas() { return fields.integer("status", "readyReplicas"); }
    public Integer getAvailableReplicas() { return fields.integer("status", "availableReplicas"); }
    public Integer getUpdatedReplicas() { return fields.integer("status", "updatedReplicas"); }
    public String getCurrentRevision() { return fields.string("status", "currentRevision"); }
    public String getUpdateRevision() { return fields.string("status", "updateRevision"); }
    public String getServiceName() { return fields.string("spec", "serviceName"); }
    public String getPodManagementPolicy() { return fields.string("spec", "podManagementPolicy"); }
    public JsonObject getUpdateStrategy() { return fields.object("spec", "updateStrategy"); }
    public JsonArray getVolumeClaimTemplates() { return fields.array("spec", "volumeClaimTemplates"); }
}

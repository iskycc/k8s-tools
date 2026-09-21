package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

import java.util.List;

/** PVC 的绑定、容量和访问模式；容量保持 Kubernetes quantity 字符串。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class PersistentVolumeClaimDetails extends ResourceDetails {
    public PersistentVolumeClaimDetails(JsonObject resource) { super(resource, "v1", "PersistentVolumeClaim"); }

    public String getPhase() { return fields.string("status", "phase"); }
    public String getVolumeName() { return fields.string("spec", "volumeName"); }
    public String getStorageClassName() { return fields.string("spec", "storageClassName"); }
    public String getVolumeMode() { return fields.string("spec", "volumeMode"); }
    public List<String> getAccessModes() { return fields.strings("spec", "accessModes"); }
    public List<String> getCurrentAccessModes() { return fields.strings("status", "accessModes"); }
    public String getRequestedStorage() { return fields.string("spec", "resources", "requests", "storage"); }
    public String getCapacityStorage() { return fields.string("status", "capacity", "storage"); }
    public List<ResourceCondition> getConditions() { return fields.objects(ResourceCondition::new, "status", "conditions"); }
}

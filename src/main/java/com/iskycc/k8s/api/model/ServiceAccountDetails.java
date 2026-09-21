package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.List;

/** ServiceAccount 的 token 挂载设置与 Secret 引用。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class ServiceAccountDetails extends ResourceDetails {
    public ServiceAccountDetails(JsonObject resource) { super(resource, "v1", "ServiceAccount"); }

    public Boolean getAutomountServiceAccountToken() { return fields.bool("automountServiceAccountToken"); }
    public List<String> getSecretNames() { return fields.propertyList("name", "secrets"); }
    public List<String> getImagePullSecretNames() { return fields.propertyList("name", "imagePullSecrets"); }
    public JsonArray getSecrets() { return fields.array("secrets"); }
}

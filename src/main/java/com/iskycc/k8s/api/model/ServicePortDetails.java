package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

/** Service 端口；targetPort 可能是名称或数字，统一以 String 返回，缺失时为 null。 */
public final class ServicePortDetails {
    private final JsonFields fields;

    public ServicePortDetails(JsonObject value) { fields = new JsonFields(value); }

    public String getName() { return fields.string("name"); }
    public Integer getPort() { return fields.integer("port"); }
    public String getTargetPort() { return fields.string("targetPort"); }
    public Integer getNodePort() { return fields.integer("nodePort"); }
    public String getProtocol() { return fields.string("protocol"); }
    public String getAppProtocol() { return fields.string("appProtocol"); }
    public JsonObject toJson() { return fields.object(); }

    @Override public String toString() { return "ServicePortDetails"; }
}

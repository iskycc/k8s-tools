package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.List;
import java.util.Map;

/** NetworkPolicy 的选择器和入站/出站规则。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class NetworkPolicyDetails extends ResourceDetails {
    public NetworkPolicyDetails(JsonObject resource) { super(resource, "networking.k8s.io/v1", "NetworkPolicy"); }

    public JsonObject getPodSelector() { return fields.object("spec", "podSelector"); }
    public Map<String, String> getMatchLabels() { return fields.stringMap("spec", "podSelector", "matchLabels"); }
    public List<String> getPolicyTypes() { return fields.strings("spec", "policyTypes"); }
    public JsonArray getIngress() { return fields.array("spec", "ingress"); }
    public JsonArray getEgress() { return fields.array("spec", "egress"); }
}

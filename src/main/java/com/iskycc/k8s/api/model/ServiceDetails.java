package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.List;
import java.util.Map;

/** Service 详细结果，直接读取服务地址、端口和选择器。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class ServiceDetails extends ResourceDetails {
    public ServiceDetails(JsonObject resource) { super(resource, "v1", "Service"); }

    public String getType() { return fields.string("spec", "type"); }
    /** Headless Service 的值可能为字符串 None。 */
    public String getClusterIP() { return fields.string("spec", "clusterIP"); }
    public List<String> getClusterIPs() { return fields.strings("spec", "clusterIPs"); }
    public List<String> getExternalIPs() { return fields.strings("spec", "externalIPs"); }
    public String getExternalName() { return fields.string("spec", "externalName"); }
    public String getExternalTrafficPolicy() { return fields.string("spec", "externalTrafficPolicy"); }
    public String getInternalTrafficPolicy() { return fields.string("spec", "internalTrafficPolicy"); }
    public String getSessionAffinity() { return fields.string("spec", "sessionAffinity"); }
    public List<String> getIpFamilies() { return fields.strings("spec", "ipFamilies"); }
    public String getIpFamilyPolicy() { return fields.string("spec", "ipFamilyPolicy"); }
    public Map<String, String> getSelector() { return fields.stringMap("spec", "selector"); }
    public List<ServicePortDetails> getPorts() { return fields.objects(ServicePortDetails::new, "spec", "ports"); }
    public JsonArray getLoadBalancerIngress() { return fields.array("status", "loadBalancer", "ingress"); }
    public List<String> getLoadBalancerIPs() { return fields.propertyList("ip", "status", "loadBalancer", "ingress"); }
    public List<String> getLoadBalancerHostnames() { return fields.propertyList("hostname", "status", "loadBalancer", "ingress"); }
}

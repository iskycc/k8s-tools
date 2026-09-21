package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.List;

/** Ingress 的域名、路由、TLS 与负载均衡地址。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class IngressDetails extends ResourceDetails {
    public IngressDetails(JsonObject resource) { super(resource, "networking.k8s.io/v1", "Ingress"); }

    public String getIngressClassName() { return fields.string("spec", "ingressClassName"); }
    public List<String> getHosts() { return fields.propertyList("host", "spec", "rules"); }
    public JsonArray getRules() { return fields.array("spec", "rules"); }
    public JsonArray getTls() { return fields.array("spec", "tls"); }
    public JsonObject getDefaultBackend() { return fields.object("spec", "defaultBackend"); }
    public JsonArray getLoadBalancerIngress() { return fields.array("status", "loadBalancer", "ingress"); }
    public List<String> getLoadBalancerIPs() { return fields.propertyList("ip", "status", "loadBalancer", "ingress"); }
    public List<String> getLoadBalancerHostnames() { return fields.propertyList("hostname", "status", "loadBalancer", "ingress"); }
}

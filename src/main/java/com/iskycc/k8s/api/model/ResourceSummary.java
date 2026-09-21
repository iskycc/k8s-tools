package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.iskycc.k8s.K8sToolsException;

/** 搜索的简易结果，仅包含资源身份；namespace + name 一起标识命名空间资源。 */
public class ResourceSummary {
    private final String namespace;
    private final String name;

    public ResourceSummary(String namespace, String name) {
        if (namespace == null || namespace.isEmpty() || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("namespace and name are required");
        }
        this.namespace = namespace;
        this.name = name;
    }

    public String getNamespace() { return namespace; }
    public String getName() { return name; }

    /** 不保留原始资源正文。 */
    public static ResourceSummary fromJson(JsonObject resource) {
        return new ResourceSummary(identity(resource, "namespace"), identity(resource, "name"));
    }

    protected static String identity(JsonObject resource, String field) {
        if (resource == null || !resource.has("metadata") || !resource.get("metadata").isJsonObject()) {
            throw new K8sToolsException("Search result has no resource metadata");
        }
        JsonObject metadata = resource.getAsJsonObject("metadata");
        if (!metadata.has(field) || !metadata.get(field).isJsonPrimitive()
                || !metadata.get(field).getAsJsonPrimitive().isString() || metadata.get(field).getAsString().isEmpty()) {
            throw new K8sToolsException("Search result is missing metadata." + field);
        }
        return metadata.get(field).getAsString();
    }

    @Override public String toString() { return "ResourceSummary{" + namespace + "/" + name + "}"; }
}

package com.iskycc.k8s.api.model;

import java.util.Map;

/**
 * 对应 k8s ObjectMeta 的常用字段子集。
 */
public class ObjectMeta {
    private String name;
    private String namespace;
    private String uid;
    private String resourceVersion;
    private String creationTimestamp;
    private Map<String, String> labels;
    private Map<String, String> annotations;

    public String getName() { return name; }
    public String getNamespace() { return namespace; }
    public String getUid() { return uid; }
    public String getResourceVersion() { return resourceVersion; }
    public String getCreationTimestamp() { return creationTimestamp; }
    public Map<String, String> getLabels() { return labels; }
    public Map<String, String> getAnnotations() { return annotations; }

    @Override
    public String toString() {
        return (namespace != null ? namespace + "/" : "") + name;
    }
}

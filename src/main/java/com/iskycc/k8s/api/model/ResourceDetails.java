package com.iskycc.k8s.api.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * 详细搜索结果：资源身份、常用元数据与完整 JSON，保留服务端返回的未知字段。
 * 返回的 JSON 均为副本，不可用本对象代替写入时所需的版本检查。
 * ConfigMap/Secret 的 data 和正文可能敏感；toString 只展示资源身份。
 */
public class ResourceDetails extends ResourceSummary {
    final JsonFields fields;

    public ResourceDetails(JsonObject resource) {
        this(resource, null, null);
    }

    /** 列表中的对象可能省略类型字段；搜索入口用明确的资源定义补齐，不覆盖服务端已有值。 */
    public ResourceDetails(JsonObject resource, String defaultApiVersion, String defaultKind) {
        super(identity(resource, "namespace"), identity(resource, "name"));
        JsonObject copy = resource.deepCopy();
        fillMissingType(copy, "apiVersion", defaultApiVersion);
        fillMissingType(copy, "kind", defaultKind);
        this.fields = new JsonFields(copy);
    }

    public String getApiVersion() { return fields.string("apiVersion"); }
    public String getKind() { return fields.string("kind"); }
    public String getUid() { return fields.string("metadata", "uid"); }
    public String getResourceVersion() { return fields.string("metadata", "resourceVersion"); }
    public String getCreationTimestamp() { return fields.string("metadata", "creationTimestamp"); }
    public String getDeletionTimestamp() { return fields.string("metadata", "deletionTimestamp"); }
    public Long getGeneration() { return fields.longNumber("metadata", "generation"); }
    public Map<String, String> getLabels() { return fields.stringMap("metadata", "labels"); }
    public Map<String, String> getAnnotations() { return fields.stringMap("metadata", "annotations"); }
    public List<String> getFinalizers() { return fields.strings("metadata", "finalizers"); }
    public JsonArray getOwnerReferences() { return fields.array("metadata", "ownerReferences"); }

    /** 完整 metadata 的副本，含 ownerReferences、finalizers 等字段。 */
    public JsonObject getMetadata() { return fields.object("metadata"); }
    /** Pod 容器/节点、Service 类型/端口/选择器、工作负载模板等资源规格。缺失时为空对象。 */
    public JsonObject getSpec() { return fields.object("spec"); }
    /** phase、IP、容器状态、就绪副本等服务端状态。缺失时为空对象。 */
    public JsonObject getStatus() { return fields.object("status"); }
    /** ConfigMap/Secret 等的 data 副本；Secret 的值保持服务端 base64 编码，不自动解码。 */
    public JsonObject getData() { return fields.object("data"); }
    /** ConfigMap 的 binaryData 副本，保持服务端 base64 编码。 */
    public JsonObject getBinaryData() { return fields.object("binaryData"); }
    /** 完整资源副本，包含 type、immutable、自定义资源字段等。 */
    public JsonObject toJson() { return fields.object(); }

    private static void fillMissingType(JsonObject resource, String field, String fallback) {
        if (fallback != null && (!resource.has(field) || resource.get(field).isJsonNull())) {
            resource.addProperty(field, fallback);
        }
    }

}

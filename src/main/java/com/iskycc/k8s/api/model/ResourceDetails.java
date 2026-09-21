package com.iskycc.k8s.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 详细搜索结果：资源身份、常用元数据与完整 JSON，保留服务端返回的未知字段。
 * 返回的 JSON 均为副本，不可用本对象代替写入时所需的版本检查。
 * ConfigMap/Secret 的 data 和正文可能敏感；toString 只展示资源身份。
 */
public final class ResourceDetails extends ResourceSummary {
    private final JsonObject resource;

    public ResourceDetails(JsonObject resource) {
        this(resource, null, null);
    }

    /** 列表中的对象可能省略类型字段；搜索入口用明确的资源定义补齐，不覆盖服务端已有值。 */
    public ResourceDetails(JsonObject resource, String defaultApiVersion, String defaultKind) {
        super(identity(resource, "namespace"), identity(resource, "name"));
        this.resource = resource.deepCopy();
        fillMissingType("apiVersion", defaultApiVersion);
        fillMissingType("kind", defaultKind);
    }

    public String getApiVersion() { return string(resource, "apiVersion"); }
    public String getKind() { return string(resource, "kind"); }
    public String getUid() { return string(resource.getAsJsonObject("metadata"), "uid"); }
    public String getResourceVersion() { return string(resource.getAsJsonObject("metadata"), "resourceVersion"); }
    public String getCreationTimestamp() { return string(resource.getAsJsonObject("metadata"), "creationTimestamp"); }
    public String getDeletionTimestamp() { return string(resource.getAsJsonObject("metadata"), "deletionTimestamp"); }
    public Map<String, String> getLabels() { return metadataMap("labels"); }
    public Map<String, String> getAnnotations() { return metadataMap("annotations"); }

    /** 完整 metadata 的副本，含 ownerReferences、finalizers 等字段。 */
    public JsonObject getMetadata() { return object("metadata"); }
    /** Pod 容器/节点、Service 类型/端口/选择器、工作负载模板等资源规格。缺失时为空对象。 */
    public JsonObject getSpec() { return object("spec"); }
    /** phase、IP、容器状态、就绪副本等服务端状态。缺失时为空对象。 */
    public JsonObject getStatus() { return object("status"); }
    /** ConfigMap/Secret 等的 data 副本；Secret 的值保持服务端 base64 编码，不自动解码。 */
    public JsonObject getData() { return object("data"); }
    /** ConfigMap 的 binaryData 副本，保持服务端 base64 编码。 */
    public JsonObject getBinaryData() { return object("binaryData"); }
    /** 完整资源副本，包含 type、immutable、自定义资源字段等。 */
    public JsonObject toJson() { return resource.deepCopy(); }

    private void fillMissingType(String field, String fallback) {
        if (fallback != null && (!resource.has(field) || resource.get(field).isJsonNull())) {
            resource.addProperty(field, fallback);
        }
    }

    private JsonObject object(String field) {
        JsonElement element = resource.get(field);
        return element != null && element.isJsonObject() ? element.getAsJsonObject().deepCopy() : new JsonObject();
    }

    private Map<String, String> metadataMap(String field) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        JsonElement element = resource.getAsJsonObject("metadata").get(field);
        if (element != null && element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) { values.put(entry.getKey(), entry.getValue().getAsString()); }
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}

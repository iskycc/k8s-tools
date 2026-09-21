package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

import java.util.Map;

/** ConfigMap 详细结果；数据 getter 不记录日志。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class ConfigMapDetails extends ResourceDetails {
    public ConfigMapDetails(JsonObject resource) { super(resource, "v1", "ConfigMap"); }

    public Boolean getImmutable() { return fields.bool("immutable"); }
    public Map<String, String> getDataMap() { return fields.stringMap("data"); }
    /** 保留 base64 编码，不自动解码。 */
    public Map<String, String> getBinaryDataMap() { return fields.stringMap("binaryData"); }
}

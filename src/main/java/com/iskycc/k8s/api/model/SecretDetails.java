package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

import java.util.Map;

/** Secret 详细结果；数据可能包含凭据，不要序列化到日志。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class SecretDetails extends ResourceDetails {
    public SecretDetails(JsonObject resource) { super(resource, "v1", "Secret"); }

    public String getType() { return fields.string("type"); }
    public Boolean getImmutable() { return fields.bool("immutable"); }
    /** 保留服务端 base64 值，不自动解码。 */
    public Map<String, String> getDataMap() { return fields.stringMap("data"); }
}

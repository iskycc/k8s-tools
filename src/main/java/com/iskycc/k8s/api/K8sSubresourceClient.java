package com.iskycc.k8s.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 单个资源的 REST 子资源。正文可以是 Scale、Eviction、TokenRequest 等不同 Kind。 */
public final class K8sSubresourceClient {
    private final K8sApiClient client;
    private final String path;

    K8sSubresourceClient(K8sApiClient client, String path) {
        this.client = client;
        this.path = path;
    }

    public JsonObject get() { return K8sApiClient.parseObject(client.getRaw(path)); }
    public JsonObject create(JsonObject body, WriteOptions options) { return write("POST", body, "application/json", options); }
    public JsonObject replace(JsonObject body, WriteOptions options) { return write("PUT", body, "application/json", options); }
    public JsonObject patch(PatchType type, JsonElement body, WriteOptions options) {
        K8sResourceClient.validatePatch(type, body);
        return write("PATCH", body, type.getContentType(), options);
    }

    private JsonObject write(String method, JsonElement body, String contentType, WriteOptions options) {
        if (body == null) { throw new IllegalArgumentException("body is required"); }
        String response = client.request(method, path, K8sResourceClient.query(options), body.toString(), contentType).getBody();
        return response.trim().isEmpty() ? null : K8sApiClient.parseObject(response);
    }
}

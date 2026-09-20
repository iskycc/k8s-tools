package com.iskycc.k8s.api;

import com.iskycc.k8s.K8sToolsException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 调用 k8s API 失败（非 2xx 响应或网络错误）。
 */
public class K8sApiException extends K8sToolsException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String responseBody;
    private final Map<String, List<String>> responseHeaders;

    public K8sApiException(int statusCode, String responseBody) {
        this(new ApiResponse(statusCode, responseBody, Collections.<String, List<String>>emptyMap()));
    }

    public K8sApiException(ApiResponse response) {
        super("k8s api error: HTTP " + response.getStatusCode());
        this.statusCode = response.getStatusCode();
        this.responseBody = response.getBody();
        this.responseHeaders = response.getHeaders();
    }

    public K8sApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseBody = "";
        this.responseHeaders = Collections.emptyMap();
    }

    /** HTTP 状态码；网络级错误为 -1。 */
    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Map<String, List<String>> getResponseHeaders() { return responseHeaders; }

    /** Kubernetes Status.reason，如 Conflict、Forbidden；非 Status 响应返回 null。 */
    public String getReason() { return statusField("reason"); }
    public String getStatusMessage() { return statusField("message"); }

    private String statusField(String name) {
        try {
            JsonElement value = JsonParser.parseString(responseBody);
            if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                if (object.has(name) && object.get(name).isJsonPrimitive()) { return object.get(name).getAsString(); }
            }
        } catch (RuntimeException ignored) { /* 非 JSON 错误正文仍可通过 getResponseBody 获取。 */ }
        return null;
    }
}

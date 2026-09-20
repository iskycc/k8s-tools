package com.iskycc.k8s.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 原始 HTTP 成功响应。保留状态码、响应正文和 Warning 等多值响应头。 */
public final class ApiResponse {
    private final int statusCode;
    private final String body;
    private final Map<String, List<String>> headers;

    ApiResponse(int statusCode, String body, Map<String, List<String>> headers) {
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
        Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            copy.put(header.getKey(), Collections.unmodifiableList(new ArrayList<String>(header.getValue())));
        }
        this.headers = Collections.unmodifiableMap(copy);
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
    public Map<String, List<String>> getHeaders() { return headers; }

    /** 大小写无关的响应头读取；不存在时返回空列表。 */
    public List<String> getHeader(String name) {
        List<String> values = new ArrayList<String>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                values.addAll(entry.getValue());
            }
        }
        return Collections.unmodifiableList(values);
    }
}

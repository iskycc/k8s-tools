package com.iskycc.k8s.api;

import com.iskycc.k8s.K8sToolsException;

/**
 * 调用 k8s API 失败（非 2xx 响应或网络错误）。
 */
public class K8sApiException extends K8sToolsException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String responseBody;

    public K8sApiException(int statusCode, String responseBody) {
        super("k8s api error: HTTP " + statusCode + ", body=" + abbreviate(responseBody));
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public K8sApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseBody = "";
    }

    /** HTTP 状态码；网络级错误为 -1。 */
    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}

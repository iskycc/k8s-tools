package com.iskycc.k8s.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 内部日志格式化工具；不记录凭据、正文、query 或异常消息。 */
public final class LogSupport {
    private LogSupport() { }

    public static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /** 限长且移除控制字符，避免外部名称伪造日志行。 */
    public static String field(String value) {
        if (value == null) { return "-"; }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(value.length(), 256); i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? '_' : c);
        }
        if (value.length() > 256) { out.append("..."); }
        return out.toString();
    }

    /** 仅输出协议、主机和端口；不输出 URI 中的密码、路径或查询参数。 */
    public static String endpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null) { return "-"; }
            return field(uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
        } catch (IllegalArgumentException | NullPointerException e) {
            return "-";
        }
    }

    public static String errorType(Throwable error) {
        Throwable cause = error;
        for (int i = 0; i < 8 && cause.getCause() != null && cause.getCause() != cause; i++) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    /** 只接受 Kubernetes Audit-ID 的 UUID 格式，不将任意响应头写入日志。 */
    public static String auditId(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if ("audit-id".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
                String id = header.getValue().get(0);
                if (id != null && id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
                    return id;
                }
            }
        }
        return "-";
    }
}

package com.iskycc.k8s.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模拟 kube-apiserver 的 HTTPS 服务器（E2E 用）。
 *
 * <p>行为：
 * <ul>
 *   <li>使用动态生成的自签名证书（SAN: localhost/127.0.0.1），其 PEM 即模拟的集群 CA</li>
 *   <li>校验 {@code Authorization: Bearer <token>}，不匹配返回 401 Status</li>
 *   <li>提供 /version、/api/v1/nodes、/api/v1/namespaces、pods/services/deployments
 *       （支持指定命名空间与全命名空间两种路径），其余路径返回 404 Status</li>
 * </ul>
 */
public class MockK8sApiServer implements Closeable {

    private static final Pattern PODS_NS =
            Pattern.compile("^/api/v1/namespaces/([^/]+)/pods$");
    private static final Pattern SERVICES_NS =
            Pattern.compile("^/api/v1/namespaces/([^/]+)/services$");
    private static final Pattern DEPLOYMENTS_NS =
            Pattern.compile("^/apis/apps/v1/namespaces/([^/]+)/deployments$");

    private final String expectedToken;
    private final CertUtil.CertBundle cert;
    private final HttpsServer server;

    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<String>();
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<Reply>();
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<RecordedRequest>());

    /** 为协议测试提供按顺序返回的响应；无预设时仍使用原有只读 fixture。 */
    public void enqueueResponse(int code, String body) {
        enqueueResponse(code, body, Collections.<String, String>emptyMap());
    }

    public void enqueueResponse(int code, String body, Map<String, String> headers) {
        replies.add(new Reply(code, body, new LinkedHashMap<String, String>(headers)));
    }

    /** 模拟服务端在收到请求后断开连接，用于验证写请求不被自动重放。 */
    public void enqueueDisconnect() { replies.add(new Reply(-1, "", Collections.<String, String>emptyMap())); }

    public List<RecordedRequest> getRequests() {
        synchronized (requests) { return new ArrayList<RecordedRequest>(requests); }
    }

    public static final class RecordedRequest {
        public final String method;
        public final String path;
        public final String rawQuery;
        public final Map<String, String> query;
        public final String body;
        public final String contentType;

        private RecordedRequest(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            rawQuery = exchange.getRequestURI().getRawQuery();
            query = new LinkedHashMap<String, String>();
            if (rawQuery != null) {
                for (String item : rawQuery.split("&")) {
                    String[] parts = item.split("=", 2);
                    query.put(URLDecoder.decode(parts[0], "UTF-8"),
                            parts.length == 1 ? "" : URLDecoder.decode(parts[1], "UTF-8"));
                }
            }
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            int count;
            while ((count = exchange.getRequestBody().read(bytes)) != -1) { output.write(bytes, 0, count); }
            body = new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class Reply {
        private final int code;
        private final String body;
        private final Map<String, String> headers;
        private Reply(int code, String body, Map<String, String> headers) {
            this.code = code; this.body = body; this.headers = headers;
        }
    }

    public MockK8sApiServer(String expectedToken) throws Exception {
        this.expectedToken = expectedToken;
        this.cert = CertUtil.generateSelfSigned("kubernetes",
                new String[]{"localhost"}, new String[]{"127.0.0.1"});

        KeyStore ks = cert.toKeyStore("server", "changeit".toCharArray());
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server.createContext("/", this::handle);
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public String getBaseUrl() {
        return "https://127.0.0.1:" + getPort();
    }

    /** 模拟 master 上 /etc/kubernetes/pki/ca.crt 的内容。 */
    public String getCaCertPem() {
        return cert.getPem();
    }

    public int getRequestCount() {
        return requestCount.get();
    }

    public String getLastAuthorization() {
        return lastAuthorization.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        lastAuthorization.set(auth);
        try {
            requests.add(new RecordedRequest(ex));
            if (!("Bearer " + expectedToken).equals(auth)) {
                send(ex, 401, statusJson(401, "Unauthorized"));
                return;
            }
            Reply reply = replies.poll();
            if (reply != null) {
                if (reply.code == -1) { return; }
                for (Map.Entry<String, String> header : reply.headers.entrySet()) {
                    ex.getResponseHeaders().set(header.getKey(), header.getValue());
                }
                send(ex, reply.code, reply.body);
                return;
            }
            String path = ex.getRequestURI().getPath();
            if ("/version".equals(path)) {
                send(ex, 200, versionJson());
            } else if ("/api/v1/nodes".equals(path)) {
                send(ex, 200, nodeListJson());
            } else if ("/api/v1/namespaces".equals(path)) {
                send(ex, 200, namespaceListJson());
            } else if ("/api/v1/pods".equals(path)) {
                send(ex, 200, podListJson(null));
            } else if ("/api/v1/services".equals(path)) {
                send(ex, 200, serviceListJson(null));
            } else if ("/apis/apps/v1/deployments".equals(path)) {
                send(ex, 200, deploymentListJson(null));
            } else if (PODS_NS.matcher(path).matches()) {
                send(ex, 200, podListJson(nsOf(PODS_NS, path)));
            } else if (SERVICES_NS.matcher(path).matches()) {
                send(ex, 200, serviceListJson(nsOf(SERVICES_NS, path)));
            } else if (DEPLOYMENTS_NS.matcher(path).matches()) {
                send(ex, 200, deploymentListJson(nsOf(DEPLOYMENTS_NS, path)));
            } else {
                send(ex, 404, statusJson(404, "the server could not find the requested resource"));
            }
        } finally {
            ex.close();
        }
    }

    private static String nsOf(Pattern p, String path) {
        Matcher m = p.matcher(path);
        return m.matches() ? m.group(1) : null;
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        if (code == 204 || "HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(code, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.flush();
    }

    // ==================== 固定数据(fixtures) ====================

    static String statusJson(int code, String message) {
        return "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"metadata\":{},"
                + "\"status\":\"Failure\",\"message\":\"" + message + "\","
                + "\"reason\":\"" + (code == 401 ? "Unauthorized" : "NotFound") + "\","
                + "\"code\":" + code + "}";
    }

    static String versionJson() {
        return "{\"major\":\"1\",\"minor\":\"28\",\"gitVersion\":\"v1.28.2\","
                + "\"gitCommit\":\"89fd4e3e50f3d8b9a6a1aaf7c5b0e3f5a4b9c1d2\","
                + "\"buildDate\":\"2026-09-01T08:00:00Z\",\"goVersion\":\"go1.20.7\","
                + "\"compiler\":\"gc\",\"platform\":\"linux/amd64\"}";
    }

    static String nodeListJson() {
        return "{\"kind\":\"NodeList\",\"apiVersion\":\"v1\","
                + "\"metadata\":{\"resourceVersion\":\"20481\"},\"items\":["
                + "{\"metadata\":{\"name\":\"node-1\",\"uid\":\"n-1\","
                + "\"labels\":{\"node-role.kubernetes.io/control-plane\":\"\"}},"
                + "\"status\":{\"conditions\":[{\"type\":\"MemoryPressure\",\"status\":\"False\"},"
                + "{\"type\":\"Ready\",\"status\":\"True\"}],"
                + "\"addresses\":[{\"type\":\"InternalIP\",\"address\":\"10.4.4.8\"},"
                + "{\"type\":\"Hostname\",\"address\":\"node-1\"}]}},"
                + "{\"metadata\":{\"name\":\"node-2\",\"uid\":\"n-2\"},"
                + "\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"False\"}],"
                + "\"addresses\":[{\"type\":\"InternalIP\",\"address\":\"10.4.4.10\"}]}}"
                + "]}";
    }

    static String namespaceListJson() {
        return "{\"kind\":\"NamespaceList\",\"apiVersion\":\"v1\","
                + "\"metadata\":{\"resourceVersion\":\"20482\"},\"items\":["
                + ns("default", "Active") + ","
                + ns("kube-system", "Active") + ","
                + ns("legacy-ns", "Terminating") + "]}";
    }

    private static String ns(String name, String phase) {
        return "{\"metadata\":{\"name\":\"" + name + "\",\"uid\":\"ns-" + name + "\"},"
                + "\"status\":{\"phase\":\"" + phase + "\"}}";
    }

    static String podListJson(String namespace) {
        StringBuilder items = new StringBuilder();
        if (namespace == null || "default".equals(namespace)) {
            items.append("{\"metadata\":{\"name\":\"nginx-7d9f8c6b5-x2k4p\",\"namespace\":\"default\","
                    + "\"uid\":\"p-1\",\"labels\":{\"app\":\"nginx\"}},"
                    + "\"spec\":{\"nodeName\":\"node-1\",\"serviceAccountName\":\"default\"},"
                    + "\"status\":{\"phase\":\"Running\",\"podIP\":\"10.244.1.5\","
                    + "\"hostIP\":\"10.4.4.8\",\"startTime\":\"2026-09-18T01:00:00Z\"}}");
        }
        if (namespace == null || "kube-system".equals(namespace)) {
            if (items.length() > 0) {
                items.append(",");
            }
            items.append("{\"metadata\":{\"name\":\"coredns-5b7c9d8f6-abcde\",\"namespace\":\"kube-system\","
                    + "\"uid\":\"p-2\",\"labels\":{\"k8s-app\":\"kube-dns\"}},"
                    + "\"spec\":{\"nodeName\":\"node-1\",\"serviceAccountName\":\"coredns\"},"
                    + "\"status\":{\"phase\":\"Running\",\"podIP\":\"10.244.1.9\","
                    + "\"hostIP\":\"10.4.4.8\"}}");
        }
        if (namespace == null || "legacy-ns".equals(namespace)) {
            if (items.length() > 0) {
                items.append(",");
            }
            items.append("{\"metadata\":{\"name\":\"old-job-9xk2\",\"namespace\":\"legacy-ns\","
                    + "\"uid\":\"p-3\"},"
                    + "\"spec\":{\"nodeName\":\"node-2\"},"
                    + "\"status\":{\"phase\":\"Pending\",\"podIP\":null,\"hostIP\":\"10.4.4.10\"}}");
        }
        return "{\"kind\":\"PodList\",\"apiVersion\":\"v1\","
                + "\"metadata\":{\"resourceVersion\":\"20483\"},\"items\":[" + items + "]}";
    }

    static String serviceListJson(String namespace) {
        StringBuilder items = new StringBuilder();
        if (namespace == null || "default".equals(namespace)) {
            items.append("{\"metadata\":{\"name\":\"kubernetes\",\"namespace\":\"default\",\"uid\":\"s-1\"},"
                    + "\"spec\":{\"type\":\"ClusterIP\",\"clusterIP\":\"10.96.0.1\","
                    + "\"ports\":[{\"name\":\"https\",\"protocol\":\"TCP\",\"port\":443,"
                    + "\"targetPort\":\"6443\"}]}}")
                    .append(",")
                    .append("{\"metadata\":{\"name\":\"nginx-svc\",\"namespace\":\"default\",\"uid\":\"s-2\"},"
                    + "\"spec\":{\"type\":\"NodePort\",\"clusterIP\":\"10.96.12.34\","
                    + "\"ports\":[{\"protocol\":\"TCP\",\"port\":80,\"targetPort\":8080,"
                    + "\"nodePort\":30080}]}}");
        }
        if (namespace == null || "kube-system".equals(namespace)) {
            if (items.length() > 0) {
                items.append(",");
            }
            items.append("{\"metadata\":{\"name\":\"kube-dns\",\"namespace\":\"kube-system\",\"uid\":\"s-3\"},"
                    + "\"spec\":{\"type\":\"ClusterIP\",\"clusterIP\":\"10.96.0.10\","
                    + "\"ports\":[{\"name\":\"dns\",\"protocol\":\"UDP\",\"port\":53,"
                    + "\"targetPort\":53}]}}");
        }
        return "{\"kind\":\"ServiceList\",\"apiVersion\":\"v1\","
                + "\"metadata\":{\"resourceVersion\":\"20484\"},\"items\":[" + items + "]}";
    }

    static String deploymentListJson(String namespace) {
        StringBuilder items = new StringBuilder();
        if (namespace == null || "default".equals(namespace)) {
            items.append("{\"metadata\":{\"name\":\"nginx\",\"namespace\":\"default\",\"uid\":\"d-1\"},"
                    + "\"spec\":{\"replicas\":3},"
                    + "\"status\":{\"replicas\":3,\"readyReplicas\":3,\"availableReplicas\":3,"
                    + "\"updatedReplicas\":3}}")
                    .append(",")
                    .append("{\"metadata\":{\"name\":\"busybox\",\"namespace\":\"default\",\"uid\":\"d-2\"},"
                    + "\"spec\":{\"replicas\":2},"
                    + "\"status\":{\"replicas\":2,\"readyReplicas\":1,\"availableReplicas\":1,"
                    + "\"updatedReplicas\":2}}");
        }
        return "{\"kind\":\"DeploymentList\",\"apiVersion\":\"apps/v1\","
                + "\"metadata\":{\"resourceVersion\":\"20485\"},\"items\":[" + items + "]}";
    }
}

package com.iskycc.k8s.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.iskycc.k8s.K8sToolsException;
import com.iskycc.k8s.api.model.Deployment;
import com.iskycc.k8s.api.model.K8sList;
import com.iskycc.k8s.api.model.Namespace;
import com.iskycc.k8s.api.model.Node;
import com.iskycc.k8s.api.model.Pod;
import com.iskycc.k8s.api.model.Service;
import com.iskycc.k8s.api.model.VersionInfo;
import com.iskycc.k8s.ssh.MasterInfo;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * 使用 ServiceAccount Bearer Token 调用 k8s API 的工具类（JDK8 原生 HttpURLConnection，无额外 HTTP 依赖）。
 *
 * <p>TLS 模式：
 * <ul>
 *   <li>提供集群 CA 证书 PEM（可由 {@code ServiceTokenFetcher} 从 master 带回）→ 严格校验</li>
 *   <li><b>自动降级（默认开启）</b>：CA 校验失败（自签名/证书过期/主机名不匹配等）时，
 *       自动改用 trust-all 重试，保证对内网自签名集群可用；{@code tlsAutoFallback(false)} 可关闭</li>
 *   <li>insecureSkipTlsVerify=true：直接跳过证书校验</li>
 *   <li>不带 CA：使用 JVM 默认信任库（配合自动降级同样能容忍自签名证书）</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>
 * MasterInfo info = new ServiceTokenFetcher(sshConfig).fetch();
 * K8sApiClient client = K8sApiClient.fromMasterInfo(info); // 无 CA 时自动 insecure
 * List&lt;Pod&gt; pods = client.listPods("default");
 * </pre>
 */
public class K8sApiClient {

    private static final String USER_AGENT = "iskycc-k8s-tools/1.0";

    private final String apiServer;
    private final String token;
    private final boolean tlsAutoFallback;
    private final boolean insecureConfigured;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final Gson gson = new Gson();

    /** 可运行期降级为 trust-all（忽略自签名证书）。 */
    private volatile SSLSocketFactory sslSocketFactory;
    private volatile HostnameVerifier hostnameVerifier;
    private volatile boolean degradedToInsecure;

    private K8sApiClient(Builder b) {
        this.apiServer = stripTrailingSlash(b.apiServer);
        this.token = b.token;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
        this.tlsAutoFallback = b.tlsAutoFallback;
        this.insecureConfigured = b.insecureSkipTlsVerify;
        if (this.apiServer.startsWith("https")) {
            this.sslSocketFactory = buildSslSocketFactory(b);
            this.hostnameVerifier = b.insecureSkipTlsVerify ? INSECURE_HOSTNAME_VERIFIER : null;
        } else {
            this.sslSocketFactory = null;
            this.hostnameVerifier = null;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 直接使用 SSH 获取到的 MasterInfo 构建客户端；
     * master 上没拿到 CA 证书时默认跳过 TLS 校验（忽略自签名证书），
     * 拿到 CA 时严格校验、失败自动降级（见 tlsAutoFallback）。
     */
    public static K8sApiClient fromMasterInfo(MasterInfo info, boolean insecureWhenNoCa) {
        if (info == null) {
            throw new IllegalArgumentException("MasterInfo is required");
        }
        return builder()
                .apiServer(info.getApiServerUrl())
                .token(info.getToken())
                .caCertPem(info.getCaCertPem())
                .insecureSkipTlsVerify(info.getCaCertPem() == null && insecureWhenNoCa)
                .build();
    }

    /** 无 CA 时默认 insecure（忽略自签名证书）。 */
    public static K8sApiClient fromMasterInfo(MasterInfo info) {
        return fromMasterInfo(info, true);
    }

    public String getApiServer() {
        return apiServer;
    }

    /** 是否已因 TLS 校验失败自动降级为 trust-all。 */
    public boolean isDegradedToInsecure() {
        return degradedToInsecure;
    }

    // ==================== 高层 API ====================

    /** GET /version */
    public VersionInfo getVersion() {
        return gson.fromJson(getRaw("/version"), VersionInfo.class);
    }

    /** GET /api/v1/namespaces */
    public List<Namespace> listNamespaces() {
        return parseList(getRaw("/api/v1/namespaces"), Namespace.class);
    }

    /** GET /api/v1/nodes */
    public List<Node> listNodes() {
        return parseList(getRaw("/api/v1/nodes"), Node.class);
    }

    /**
     * GET /api/v1/namespaces/{ns}/pods；namespace 传 null/空 表示所有命名空间。
     */
    public List<Pod> listPods(String namespace) {
        String path = isAllNamespaces(namespace)
                ? "/api/v1/pods"
                : "/api/v1/namespaces/" + namespace + "/pods";
        return parseList(getRaw(path), Pod.class);
    }

    /** GET /api/v1/namespaces/{ns}/services；namespace 传 null/空 表示所有命名空间。 */
    public List<Service> listServices(String namespace) {
        String path = isAllNamespaces(namespace)
                ? "/api/v1/services"
                : "/api/v1/namespaces/" + namespace + "/services";
        return parseList(getRaw(path), Service.class);
    }

    /** GET /apis/apps/v1/namespaces/{ns}/deployments；namespace 传 null/空 表示所有命名空间。 */
    public List<Deployment> listDeployments(String namespace) {
        String path = isAllNamespaces(namespace)
                ? "/apis/apps/v1/deployments"
                : "/apis/apps/v1/namespaces/" + namespace + "/deployments";
        return parseList(getRaw(path), Deployment.class);
    }

    // ==================== 通用 GET ====================

    /**
     * 对任意 API path 发起带 Bearer Token 的 GET 请求，返回原始 JSON 字符串。
     * TLS 校验失败（自签名证书等）且开启自动降级时，改用 trust-all 透明重试一次。
     *
     * @param path 以 / 开头的 API 路径，如 /api/v1/namespaces/default/pods
     */
    public String getRaw(String path) {
        try {
            return doGet(path);
        } catch (K8sApiException e) {
            if (tlsAutoFallback && !insecureConfigured && !degradedToInsecure
                    && e.getStatusCode() == -1 && isTlsError(e)) {
                degradeToInsecure();
                return doGet(path);
            }
            throw e;
        }
    }

    private synchronized void degradeToInsecure() {
        if (degradedToInsecure) {
            return;
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, null);
            this.sslSocketFactory = ctx.getSocketFactory();
            this.hostnameVerifier = INSECURE_HOSTNAME_VERIFIER;
            this.degradedToInsecure = true;
        } catch (GeneralSecurityException e) {
            throw new K8sToolsException("TLS 自动降级失败: " + e.getMessage(), e);
        }
    }

    private static boolean isTlsError(K8sApiException e) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLException
                    || t instanceof java.security.cert.CertificateException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("PKIX path building failed")
                    || msg.contains("unable to find valid certification path"))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private String doGet(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/': " + path);
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiServer + path);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection && sslSocketFactory != null) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(sslSocketFactory);
                HostnameVerifier verifier = hostnameVerifier;
                if (verifier != null) {
                    https.setHostnameVerifier(verifier);
                }
            }
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);

            int code = conn.getResponseCode();
            String body = readBody(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new K8sApiException(code, body);
            }
            return body;
        } catch (K8sApiException e) {
            throw e;
        } catch (IOException e) {
            throw new K8sApiException("请求 k8s api 失败: " + apiServer + path + " - " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private <T> List<T> parseList(String json, Class<T> itemType) {
        try {
            K8sList<T> list = gson.fromJson(json,
                    TypeToken.getParameterized(K8sList.class, itemType).getType());
            return list == null ? java.util.Collections.<T>emptyList() : list.getItems();
        } catch (JsonSyntaxException e) {
            throw new K8sToolsException("解析 k8s api 响应失败: " + e.getMessage(), e);
        }
    }

    private static boolean isAllNamespaces(String namespace) {
        return namespace == null || namespace.trim().isEmpty() || "all".equalsIgnoreCase(namespace.trim());
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    // ==================== TLS ====================

    private static SSLSocketFactory buildSslSocketFactory(Builder b) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            if (b.insecureSkipTlsVerify) {
                ctx.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, null);
                return ctx.getSocketFactory();
            }
            if (b.caCertPem != null && !b.caCertPem.trim().isEmpty()) {
                X509Certificate ca = parsePemCertificate(b.caCertPem);
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                ks.setCertificateEntry("k8s-ca", ca);
                TrustManagerFactory tmf =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                ctx.init(null, tmf.getTrustManagers(), null);
                return ctx.getSocketFactory();
            }
            return null; // 使用 JVM 默认
        } catch (GeneralSecurityException | IOException e) {
            throw new K8sToolsException("初始化 TLS 失败: " + e.getMessage(), e);
        }
    }

    static X509Certificate parsePemCertificate(String pem) throws GeneralSecurityException {
        String base64 = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = java.util.Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("CA 证书不是合法 PEM", e);
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private static final X509TrustManager TRUST_ALL_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private static final HostnameVerifier INSECURE_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private static String stripTrailingSlash(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("apiServer is required");
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    // ==================== Builder ====================

    public static final class Builder {
        private String apiServer;
        private String token;
        private String caCertPem;
        private boolean insecureSkipTlsVerify;
        /** TLS 校验失败时自动降级为 trust-all 重试（忽略自签名证书），默认开启。 */
        private boolean tlsAutoFallback = true;
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 30000;

        public Builder apiServer(String apiServer) { this.apiServer = apiServer; return this; }
        public Builder token(String token) { this.token = token; return this; }
        public Builder caCertPem(String caCertPem) { this.caCertPem = caCertPem; return this; }
        public Builder insecureSkipTlsVerify(boolean v) { this.insecureSkipTlsVerify = v; return this; }
        public Builder tlsAutoFallback(boolean v) { this.tlsAutoFallback = v; return this; }
        public Builder connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
        public Builder readTimeoutMs(int v) { this.readTimeoutMs = v; return this; }

        public K8sApiClient build() {
            if (apiServer == null || apiServer.trim().isEmpty()) {
                throw new IllegalArgumentException("apiServer is required");
            }
            if (token == null || token.trim().isEmpty()) {
                throw new IllegalArgumentException("token is required");
            }
            return new K8sApiClient(this);
        }
    }
}

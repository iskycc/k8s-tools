package com.iskycc.k8s.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.iskycc.k8s.K8sToolsException;
import com.iskycc.k8s.internal.LogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.iskycc.k8s.api.model.Deployment;
import com.iskycc.k8s.api.model.K8sList;
import com.iskycc.k8s.api.model.Namespace;
import com.iskycc.k8s.api.model.Node;
import com.iskycc.k8s.api.model.Pod;
import com.iskycc.k8s.api.model.PodSummary;
import com.iskycc.k8s.api.model.ResourceSummary;
import com.iskycc.k8s.api.model.ResourceDetails;
import com.iskycc.k8s.api.model.Service;
import com.iskycc.k8s.api.model.VersionInfo;
import com.iskycc.k8s.ssh.MasterInfo;
import com.iskycc.k8s.ssh.RedisServiceTokenCache;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;
import com.iskycc.k8s.ssh.SshConfig;
import redis.clients.jedis.JedisPool;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 使用 Bearer Token 调用 Kubernetes REST API，兼容 Java 8，支持通用资源增删查改与 Discovery。
 *
 * <p>TLS 模式：
 * <ul>
 *   <li>提供集群 CA 证书 PEM（可由 {@code ServiceTokenFetcher} 从 master 带回）→ 严格校验</li>
 *   <li><b>读取自动降级（默认开启）</b>：GET/HEAD 的 CA 校验失败（自签名/证书过期/主机名不匹配等）时，
 *       自动改用 trust-all 重试，保证对内网自签名集群可用；{@code tlsAutoFallback(false)} 可关闭</li>
 *   <li>insecureSkipTlsVerify=true：直接跳过证书校验</li>
 *   <li>不带 CA：使用 JVM 默认信任库（配合自动降级同样能容忍自签名证书）</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>
 * K8sApiClient client = K8sApiClient.builder()
 *         .redisUrl("redis://127.0.0.1:6379/0").fromSsh(sshConfig);
 * List&lt;Pod&gt; pods = client.listPods("default");
 * </pre>
 */
public class K8sApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(K8sApiClient.class);
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static final String USER_AGENT = "iskycc-k8s-tools/1.1";

    private final String apiServer;
    private final String token;
    private final SshConfig execSshConfig;
    private final String execCaCertPem;
    private volatile PodExecVersion execVersion;
    private final boolean tlsAutoFallback;
    private final boolean insecureConfigured;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final Gson gson = new Gson();

    /** 可运行期降级为 trust-all（忽略自签名证书）。 */
    private volatile TlsSettings tlsSettings;
    private volatile boolean degradedToInsecure;

    private K8sApiClient(Builder b) {
        this.apiServer = stripTrailingSlash(b.apiServer);
        this.token = b.token;
        this.execSshConfig = b.execSshConfig;
        this.execCaCertPem = b.caCertPem;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
        this.tlsAutoFallback = b.tlsAutoFallback;
        this.insecureConfigured = b.insecureSkipTlsVerify;
        this.tlsSettings = new TlsSettings(buildSslContext(b),
                b.insecureSkipTlsVerify ? INSECURE_HOSTNAME_VERIFIER : new DefaultHostnameVerifier());
        LOG.info("API 客户端就绪 server={} tls={} autoFallback={} connectTimeoutMs={} readTimeoutMs={}",
                LogSupport.endpoint(apiServer), insecureConfigured ? "insecure" : "verify",
                tlsAutoFallback, connectTimeoutMs, readTimeoutMs);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 在 Pod 中执行命令：有 SSH 配置时 AUTO 对低于 1.31 的集群选 SSH，其余使用 API WebSocket。
     * command 是 argv，每个参数独立传入，如 exec(ns, pod, "ls", "-l", "/tmp")；不隐式解析 shell。
     * 默认无 stdin/TTY，返回分离的 stdout、stderr 和退出码；30 秒超时、4 MiB 输出上限。
     * Exec 不自动重试、不因 TLS 失败降级，防止命令重复执行。
     */
    public PodExecResult exec(String namespace, String podName, String... command) {
        return exec(namespace, podName, PodExecOptions.builder().build(), command);
    }

    /** 指定容器、总超时及输出上限的 Pod Exec。 */
    public PodExecResult exec(String namespace, String podName, PodExecOptions options, String... command) {
        PodExecTransport.validate(namespace, podName, options, command);
        String[] args = command.clone();
        if (options.getTransport() == PodExecOptions.Transport.SSH && execSshConfig == null) {
            throw new IllegalStateException("SSH exec requires fromSsh or Builder.execSshConfig");
        }
        if (options.getTransport() == PodExecOptions.Transport.WEBSOCKET
                || (options.getTransport() == PodExecOptions.Transport.AUTO && execSshConfig == null)) {
            logExecTransport(PodExecOptions.Transport.WEBSOCKET, namespace, podName, options, null);
            return execWebSocket(namespace, podName, options, args);
        }
        long started = System.nanoTime();
        try {
            PodExecResult result = PodExecOperation.run(options, operation -> {
                boolean useSsh = options.getTransport() == PodExecOptions.Transport.SSH;
                PodExecVersion version = null;
                if (!useSsh) {
                    version = execVersion;
                    if (version == null) {
                        // 只探测，不执行命令；使用当前 TLS 设置，不触发读取自动降级。
                        int remaining = operation.remainingMs();
                        version = PodExecVersion.parse(doRequest("GET", requestUri("/version", null), null, null,
                                remaining, remaining, operation).getBody());
                        operation.check();
                        execVersion = version;
                        LogSupport.debug(LOG, "Pod exec 版本已识别 server={} version={}", LogSupport.endpoint(apiServer), version);
                    }
                    useSsh = version.useSsh();
                }
                operation.check();
                logExecTransport(useSsh ? PodExecOptions.Transport.SSH : PodExecOptions.Transport.WEBSOCKET,
                        namespace, podName, options, version);
                if (useSsh) {
                    TlsSettings tls = tlsSettings;
                    LogSupport.debug(LOG, "Pod exec SSH 开始 namespace={} pod={} container={}",
                            namespace, podName, options.getContainer());
                    return PodExecSshTransport.execute(execSshConfig, apiServer, token, execCaCertPem,
                            tls.verifier == INSECURE_HOSTNAME_VERIFIER, namespace, podName, options, args, operation);
                }
                PodExecOptions remaining = PodExecOptions.builder().container(options.getContainer())
                        .timeoutMs(operation.remainingMs()).maxOutputBytes(options.getMaxOutputBytes())
                        .transport(PodExecOptions.Transport.WEBSOCKET).build();
                TlsSettings tls = tlsSettings;
                return PodExecTransport.execute(apiServer, token, tls.context, tls.verifier != INSECURE_HOSTNAME_VERIFIER,
                        connectTimeoutMs, namespace, podName, remaining, args, operation);
            });
            LogSupport.debug(LOG, "Pod exec 调用完成 namespace={} pod={} exitCode={} elapsedMs={}",
                    namespace, podName, result.getExitCode(), LogSupport.elapsedMs(started));
            if (!result.isSuccess()) {
                LOG.warn("Pod exec 命令返回非零状态 namespace={} pod={} exitCode={}", namespace, podName, result.getExitCode());
            }
            return result;
        } catch (K8sApiException e) {
            LOG.warn("Pod exec 调用失败 namespace={} pod={} status={} failure={} elapsedMs={} errorType={}",
                    namespace, podName, e.getStatusCode(),
                    e instanceof PodExecException ? ((PodExecException) e).getFailureReason() : "HTTP",
                    LogSupport.elapsedMs(started), LogSupport.errorType(e));
            throw e;
        }
    }

    private void logExecTransport(PodExecOptions.Transport transport, String namespace, String podName,
                                  PodExecOptions options, PodExecVersion version) {
        LOG.info("Pod exec 执行通道 transport={} mode={} version={} server={} namespace={} pod={} container={}",
                transport, options.getTransport(), version == null ? "-" : version.toString(),
                LogSupport.endpoint(apiServer), LogSupport.field(namespace), LogSupport.field(podName),
                LogSupport.field(options.getContainer()));
    }

    private PodExecResult execWebSocket(String namespace, String podName, PodExecOptions options, String[] command) {
        TlsSettings tls = tlsSettings;
        return PodExecTransport.execute(apiServer, token, tls.context, tls.verifier != INSECURE_HOSTNAME_VERIFIER,
                connectTimeoutMs, namespace, podName, options, command);
    }

    /** 执行整条命令，如 execShell(ns, pod, "ls -al /tmp")；由容器内 /bin/sh -c 解释。 */
    public PodExecResult execShell(String namespace, String podName, String command) {
        return execShell(namespace, podName, PodExecOptions.builder().build(), command);
    }

    /** shell 文本来自调用方，避免拼接不可信输入；支持与 exec 相同的选项。 */
    public PodExecResult execShell(String namespace, String podName, PodExecOptions options, String command) {
        if (command == null || command.trim().isEmpty()) { throw new IllegalArgumentException("command is required"); }
        return exec(namespace, podName, options, "/bin/sh", "-c", command);
    }

    /** 自动发现 API 地址并直接跳过证书及主机名校验，首次写请求同样适用。 */
    public static K8sApiClient fromSsh(SshConfig config) {
        return fromSsh(config, new ServiceTokenFetcher.Options());
    }

    /**
     * 兼容外部缓存配置的接入入口，直接跳过 TLS 校验。
     * 由客户端管理 Redis 时使用 {@code builder().redisUrl(url).fromSsh(config)}。
     */
    public static K8sApiClient fromSsh(SshConfig config, ServiceTokenFetcher.Options options) {
        return builder().fromSsh(config, options);
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
                : "/api/v1/namespaces/" + K8sResourceClient.pathSegment(namespace) + "/pods";
        return parseList(getRaw(path), Pod.class);
    }

    /** GET /api/v1/namespaces/{ns}/services；namespace 传 null/空 表示所有命名空间。 */
    public List<Service> listServices(String namespace) {
        String path = isAllNamespaces(namespace)
                ? "/api/v1/services"
                : "/api/v1/namespaces/" + K8sResourceClient.pathSegment(namespace) + "/services";
        return parseList(getRaw(path), Service.class);
    }

    /** GET /apis/apps/v1/namespaces/{ns}/deployments；namespace 传 null/空 表示所有命名空间。 */
    public List<Deployment> listDeployments(String namespace) {
        String path = isAllNamespaces(namespace)
                ? "/apis/apps/v1/deployments"
                : "/apis/apps/v1/namespaces/" + K8sResourceClient.pathSegment(namespace) + "/deployments";
        return parseList(getRaw(path), Deployment.class);
    }

    // ==================== 跨全部 namespace 的关键词搜索（1.6.0 起） ====================

    /**
     * 名称包含关键词的全部匹配资源，简易结果仅保留 namespace/name。
     * 区分大小写，不做完整名称优先或状态过滤；默认每页 100 条，无匹配返回空 List。
     */
    public List<ResourceSummary> searchResources(ResourceDefinition definition, String keyword) {
        return searchResources(definition, keyword, null);
    }

    /** 自定义选择器和分页大小；自动遍历所有页，禁止从 continueToken 中途开始。 */
    public List<ResourceSummary> searchResources(ResourceDefinition definition, String keyword, ListOptions options) {
        return ResourceSearch.search(this, definition, keyword, options, ResourceSummary::fromJson);
    }

    /** 详细版保留完整 JSON 与常用元数据；仅接受命名空间资源定义。 */
    public List<ResourceDetails> searchResourcesDetailed(ResourceDefinition definition, String keyword) {
        return searchResourcesDetailed(definition, keyword, null);
    }

    /** 详细搜索与简易搜索使用同样的匹配和分页规则；错误不转换为空结果。 */
    public List<ResourceDetails> searchResourcesDetailed(ResourceDefinition definition, String keyword, ListOptions options) {
        return ResourceSearch.search(this, definition, keyword, options,
                item -> new ResourceDetails(item, definition.getApiVersion(), definition.getKind()));
    }

    /** 跨全部 namespace 搜索 Pod，返回全部简易结果（含普通容器名称）。 */
    public List<PodSummary> searchPods(String keyword) { return searchPods(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<PodSummary> searchPods(String keyword, ListOptions options) {
        return ResourceSearch.search(this, K8sResources.PODS, keyword, options, PodSummary::fromJson);
    }
    /** 跨全部 namespace 搜索 Pod，返回全部详细结果。 */
    public List<ResourceDetails> searchPodsDetailed(String keyword) { return searchPodsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchPodsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.PODS, keyword, options);
    }

    /** 跨全部 namespace 搜索 ConfigMap，返回全部简易结果。 */
    public List<ResourceSummary> searchConfigMaps(String keyword) { return searchConfigMaps(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchConfigMaps(String keyword, ListOptions options) {
        return searchResources(K8sResources.CONFIG_MAPS, keyword, options);
    }
    /** 跨全部 namespace 搜索 ConfigMap，返回全部详细结果。 */
    public List<ResourceDetails> searchConfigMapsDetailed(String keyword) { return searchConfigMapsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchConfigMapsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.CONFIG_MAPS, keyword, options);
    }

    /** 跨全部 namespace 搜索 Service，返回全部简易结果。 */
    public List<ResourceSummary> searchServices(String keyword) { return searchServices(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchServices(String keyword, ListOptions options) {
        return searchResources(K8sResources.SERVICES, keyword, options);
    }
    /** 跨全部 namespace 搜索 Service，返回全部详细结果。 */
    public List<ResourceDetails> searchServicesDetailed(String keyword) { return searchServicesDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchServicesDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.SERVICES, keyword, options);
    }

    /** 跨全部 namespace 搜索 Deployment，返回全部简易结果。 */
    public List<ResourceSummary> searchDeployments(String keyword) { return searchDeployments(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchDeployments(String keyword, ListOptions options) {
        return searchResources(K8sResources.DEPLOYMENTS, keyword, options);
    }
    /** 跨全部 namespace 搜索 Deployment，返回全部详细结果。 */
    public List<ResourceDetails> searchDeploymentsDetailed(String keyword) { return searchDeploymentsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchDeploymentsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.DEPLOYMENTS, keyword, options);
    }

    /** 跨全部 namespace 搜索 StatefulSet，返回全部简易结果。 */
    public List<ResourceSummary> searchStatefulSets(String keyword) { return searchStatefulSets(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchStatefulSets(String keyword, ListOptions options) {
        return searchResources(K8sResources.STATEFUL_SETS, keyword, options);
    }
    /** 跨全部 namespace 搜索 StatefulSet，返回全部详细结果。 */
    public List<ResourceDetails> searchStatefulSetsDetailed(String keyword) { return searchStatefulSetsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchStatefulSetsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.STATEFUL_SETS, keyword, options);
    }

    /** 跨全部 namespace 搜索 DaemonSet，返回全部简易结果。 */
    public List<ResourceSummary> searchDaemonSets(String keyword) { return searchDaemonSets(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchDaemonSets(String keyword, ListOptions options) {
        return searchResources(K8sResources.DAEMON_SETS, keyword, options);
    }
    /** 跨全部 namespace 搜索 DaemonSet，返回全部详细结果。 */
    public List<ResourceDetails> searchDaemonSetsDetailed(String keyword) { return searchDaemonSetsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchDaemonSetsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.DAEMON_SETS, keyword, options);
    }

    /** 跨全部 namespace 搜索 ReplicaSet，返回全部简易结果。 */
    public List<ResourceSummary> searchReplicaSets(String keyword) { return searchReplicaSets(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchReplicaSets(String keyword, ListOptions options) {
        return searchResources(K8sResources.REPLICA_SETS, keyword, options);
    }
    /** 跨全部 namespace 搜索 ReplicaSet，返回全部详细结果。 */
    public List<ResourceDetails> searchReplicaSetsDetailed(String keyword) { return searchReplicaSetsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchReplicaSetsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.REPLICA_SETS, keyword, options);
    }

    /** 跨全部 namespace 搜索 Job，返回全部简易结果。 */
    public List<ResourceSummary> searchJobs(String keyword) { return searchJobs(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchJobs(String keyword, ListOptions options) {
        return searchResources(K8sResources.JOBS, keyword, options);
    }
    /** 跨全部 namespace 搜索 Job，返回全部详细结果。 */
    public List<ResourceDetails> searchJobsDetailed(String keyword) { return searchJobsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchJobsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.JOBS, keyword, options);
    }

    /** 跨全部 namespace 搜索 CronJob，返回全部简易结果。 */
    public List<ResourceSummary> searchCronJobs(String keyword) { return searchCronJobs(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchCronJobs(String keyword, ListOptions options) {
        return searchResources(K8sResources.CRON_JOBS, keyword, options);
    }
    /** 跨全部 namespace 搜索 CronJob，返回全部详细结果。 */
    public List<ResourceDetails> searchCronJobsDetailed(String keyword) { return searchCronJobsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchCronJobsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.CRON_JOBS, keyword, options);
    }

    /** 跨全部 namespace 搜索 Ingress，返回全部简易结果。 */
    public List<ResourceSummary> searchIngresses(String keyword) { return searchIngresses(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchIngresses(String keyword, ListOptions options) {
        return searchResources(K8sResources.INGRESSES, keyword, options);
    }
    /** 跨全部 namespace 搜索 Ingress，返回全部详细结果。 */
    public List<ResourceDetails> searchIngressesDetailed(String keyword) { return searchIngressesDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchIngressesDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.INGRESSES, keyword, options);
    }

    /** 跨全部 namespace 搜索 PVC，返回全部简易结果。 */
    public List<ResourceSummary> searchPersistentVolumeClaims(String keyword) { return searchPersistentVolumeClaims(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchPersistentVolumeClaims(String keyword, ListOptions options) {
        return searchResources(K8sResources.PERSISTENT_VOLUME_CLAIMS, keyword, options);
    }
    /** 跨全部 namespace 搜索 PVC，返回全部详细结果。 */
    public List<ResourceDetails> searchPersistentVolumeClaimsDetailed(String keyword) { return searchPersistentVolumeClaimsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchPersistentVolumeClaimsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.PERSISTENT_VOLUME_CLAIMS, keyword, options);
    }

    /** 跨全部 namespace 搜索 Secret，返回全部简易结果。 */
    public List<ResourceSummary> searchSecrets(String keyword) { return searchSecrets(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchSecrets(String keyword, ListOptions options) {
        return searchResources(K8sResources.SECRETS, keyword, options);
    }
    /** 跨全部 namespace 搜索 Secret，返回全部详细结果。 */
    public List<ResourceDetails> searchSecretsDetailed(String keyword) { return searchSecretsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchSecretsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.SECRETS, keyword, options);
    }

    /** 跨全部 namespace 搜索 ServiceAccount，返回全部简易结果。 */
    public List<ResourceSummary> searchServiceAccounts(String keyword) { return searchServiceAccounts(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchServiceAccounts(String keyword, ListOptions options) {
        return searchResources(K8sResources.SERVICE_ACCOUNTS, keyword, options);
    }
    /** 跨全部 namespace 搜索 ServiceAccount，返回全部详细结果。 */
    public List<ResourceDetails> searchServiceAccountsDetailed(String keyword) { return searchServiceAccountsDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchServiceAccountsDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.SERVICE_ACCOUNTS, keyword, options);
    }

    /** 跨全部 namespace 搜索 NetworkPolicy，返回全部简易结果。 */
    public List<ResourceSummary> searchNetworkPolicies(String keyword) { return searchNetworkPolicies(keyword, null); }
    /** 简易版，允许通过 ListOptions 筛选和设置每页数量。 */
    public List<ResourceSummary> searchNetworkPolicies(String keyword, ListOptions options) {
        return searchResources(K8sResources.NETWORK_POLICIES, keyword, options);
    }
    /** 跨全部 namespace 搜索 NetworkPolicy，返回全部详细结果。 */
    public List<ResourceDetails> searchNetworkPoliciesDetailed(String keyword) { return searchNetworkPoliciesDetailed(keyword, null); }
    /** 详细版，包含 metadata、spec/status 及服务端提供的其他字段。 */
    public List<ResourceDetails> searchNetworkPoliciesDetailed(String keyword, ListOptions options) {
        return searchResourcesDetailed(K8sResources.NETWORK_POLICIES, keyword, options);
    }

    // ==================== 通用资源与 Discovery ====================

    public K8sResourceClient resource(ResourceDefinition definition) {
        if (definition == null) { throw new IllegalArgumentException("resource definition is required"); }
        return new K8sResourceClient(this, definition, null);
    }

    /** 根据 Discovery 确定作用域、Kind 和支持的动作，不猜测自定义资源名称。 */
    public K8sResourceClient resource(String apiVersion, String plural) {
        if (plural == null || plural.isEmpty()) { throw new IllegalArgumentException("resource name is required"); }
        for (ApiResource resource : discoverResources(apiVersion)) {
            if (plural.equals(resource.getName())) {
                return resource(resource.toDefinition(apiVersion));
            }
        }
        throw new IllegalArgumentException("Resource not advertised by API discovery: " + apiVersion + "/" + plural);
    }

    public K8sResourceClient pods(String namespace) { return resource(K8sResources.PODS).inNamespace(namespace); }
    public K8sResourceClient services(String namespace) { return resource(K8sResources.SERVICES).inNamespace(namespace); }
    public K8sResourceClient deployments(String namespace) { return resource(K8sResources.DEPLOYMENTS).inNamespace(namespace); }
    public K8sResourceClient configMaps(String namespace) { return resource(K8sResources.CONFIG_MAPS).inNamespace(namespace); }
    public K8sResourceClient secrets(String namespace) { return resource(K8sResources.SECRETS).inNamespace(namespace); }
    public K8sResourceClient namespaces() { return resource(K8sResources.NAMESPACES); }
    public K8sResourceClient nodes() { return resource(K8sResources.NODES); }

    /** 查询某个 API 版本的资源和子资源；返回值包括服务端声明的 verbs。 */
    public List<ApiResource> discoverResources(String apiVersion) {
        String base = ResourceDefinition.cluster(apiVersion, "resources", "APIResource").basePath();
        JsonObject document = parseObject(getRaw(base));
        if (!document.has("resources") || !document.get("resources").isJsonArray()) {
            throw new K8sToolsException("Discovery response has no resources array");
        }
        List<ApiResource> resources = new ArrayList<ApiResource>();
        for (JsonElement element : document.getAsJsonArray("resources")) {
            if (!element.isJsonObject()) { throw new K8sToolsException("Invalid API discovery resource"); }
            try {
                ApiResource resource = gson.fromJson(element, ApiResource.class);
                if (resource.getName() == null || resource.getKind() == null) {
                    throw new K8sToolsException("Discovery resource is missing name or kind");
                }
                resources.add(resource);
            } catch (JsonSyntaxException e) {
                throw new K8sToolsException("Invalid API discovery resource", e);
            }
        }
        return Collections.unmodifiableList(resources);
    }

    /** 返回 /api 和 /apis 中服务端声明的所有版本；不忽略认证或聚合 API 错误。 */
    public List<String> discoverApiVersions() {
        LinkedHashSet<String> versions = new LinkedHashSet<String>();
        JsonArray core = parseObject(getRaw("/api")).getAsJsonArray("versions");
        if (core == null) { throw new K8sToolsException("Discovery response has no versions array"); }
        for (JsonElement version : core) { versions.add(version.getAsString()); }
        JsonArray groups = parseObject(getRaw("/apis")).getAsJsonArray("groups");
        if (groups == null) { throw new K8sToolsException("Discovery response has no groups array"); }
        for (JsonElement group : groups) {
            for (JsonElement version : group.getAsJsonObject().getAsJsonArray("versions")) {
                versions.add(version.getAsJsonObject().get("groupVersion").getAsString());
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(versions));
    }

    // ==================== 通用 HTTP ====================

    /**
     * 对任意 API path 发起带 Bearer Token 的 GET 请求，返回原始 JSON 字符串。
     * TLS 校验失败（自签名证书等）且开启自动降级时，改用 trust-all 透明重试一次。
     *
     * @param path 以 / 开头的 API 路径，如 /api/v1/namespaces/default/pods
     */
    public String getRaw(String path) {
        return request("GET", path, null, null, null).getBody();
    }

    /**
     * 通用 REST 请求；body 是原始 JSON/文本，响应保留状态码和多值响应头。
     * 不跟随重定向，不自动重试写入。只有 GET/HEAD 保留兼容的 TLS 自动降级。
     * @param method GET、HEAD、OPTIONS、POST、PUT、PATCH 或 DELETE
     * @param path API 路径，可带已有 query，必须以单个 / 开头
     * @param query 附加查询参数，原文传入，由客户端统一 URL 编码
     * @param body 请求正文；没有正文时为 null
     * @param contentType 有正文时必填的媒体类型
     * @return 2xx 响应；其他状态抛 K8sApiException
     */
    public ApiResponse request(String method, String path, Map<String, String> query,
                               String body, String contentType) {
        if (method == null || !Arrays.asList("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")
                .contains(method.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unsupported HTTP method");
        }
        String verb = method.toUpperCase(Locale.ROOT);
        if (("GET".equals(verb) || "HEAD".equals(verb)) && body != null) {
            throw new IllegalArgumentException("GET/HEAD must not have a request body");
        }
        if (body != null && (contentType == null || contentType.trim().isEmpty())) {
            throw new IllegalArgumentException("contentType is required for a request body");
        }
        URI uri = requestUri(path, query);
        long requestId = REQUEST_IDS.incrementAndGet();
        long started = System.nanoTime();
        String server = LogSupport.endpoint(apiServer);
        String route = LogSupport.field(uri.getRawPath());
        LogSupport.debug(LOG, "API 请求开始 requestId={} method={} server={} path={}", requestId, verb, server, route);
        try {
            ApiResponse response;
            try {
                response = doRequest(verb, uri, body, contentType);
            } catch (K8sApiException e) {
                if (("GET".equals(verb) || "HEAD".equals(verb))
                        && tlsAutoFallback && !insecureConfigured && !degradedToInsecure
                        && e.getStatusCode() == -1 && isTlsError(e)) {
                    LOG.warn("TLS 校验失败，自动降级并重试；后续请求也跳过校验 requestId={} server={} errorType={}",
                            requestId, server, LogSupport.errorType(e));
                    degradeToInsecure();
                    response = doRequest(verb, uri, body, contentType);
                } else {
                    throw e;
                }
            }
            LogSupport.debug(LOG, "API 请求完成 requestId={} method={} server={} path={} status={} auditId={} elapsedMs={}",
                    requestId, verb, server, route, response.getStatusCode(),
                    LogSupport.auditId(response.getHeaders()), LogSupport.elapsedMs(started));
            return response;
        } catch (K8sApiException e) {
            // 404 是 exists/deleteIfExists 的正常分支；其余失败默认可见。正文与异常消息可能含 Secret。
            String format = "API 请求失败 requestId={} method={} server={} path={} status={} auditId={} elapsedMs={} errorType={}";
            Object[] details = {requestId, verb, server, route, e.getStatusCode(),
                    LogSupport.auditId(e.getResponseHeaders()), LogSupport.elapsedMs(started), LogSupport.errorType(e)};
            if (e.getStatusCode() == 404) { LogSupport.debug(LOG, format, details); }
            else { LOG.warn(format, details); }
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
            this.tlsSettings = new TlsSettings(ctx, INSECURE_HOSTNAME_VERIFIER);
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

    private URI requestUri(String path, Map<String, String> query) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.indexOf('#') >= 0 || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("path must be an API path starting with a single '/'");
        }
        try {
            URIBuilder builder = new URIBuilder(apiServer + path);
            if (query != null) {
                for (Map.Entry<String, String> entry : query.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        throw new IllegalArgumentException("query parameter names and values must not be null");
                    }
                    builder.addParameter(entry.getKey(), entry.getValue());
                }
            }
            return builder.build();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid API path", e);
        }
    }

    private ApiResponse doRequest(String method, URI uri, String body, String contentType) {
        return doRequest(method, uri, body, contentType, connectTimeoutMs, readTimeoutMs, null);
    }

    private ApiResponse doRequest(String method, URI uri, String body, String contentType,
                                  int connectTimeoutMs, int readTimeoutMs, PodExecOperation operation) {
        TlsSettings tls = tlsSettings;
        BasicHttpClientConnectionManager manager = BasicHttpClientConnectionManager.create(
                RegistryBuilder.<TlsSocketStrategy>create().register("https",
                        new DefaultClientTlsStrategy(tls.context, HostnameVerificationPolicy.CLIENT,
                                tls.verifier)).build());
        manager.setConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs)).build());
        HttpUriRequestBase request = new HttpUriRequestBase(method, uri);
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs)).build());
        request.setHeader("Authorization", "Bearer " + token);
        request.setHeader("Accept", "application/json");
        request.setHeader("User-Agent", USER_AGENT);
        if (body != null) {
            request.setEntity(new StringEntity(body,
                    ContentType.parse(contentType).withCharset(StandardCharsets.UTF_8)));
        }
        try (CloseableHttpClient http = HttpClients.custom().setConnectionManager(manager)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build()) {
            if (operation != null) { operation.onCancel(request::cancel); }
            return http.execute(request, response -> {
                String text = response.getEntity() == null ? ""
                        : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
                for (Header header : response.getHeaders()) {
                    String name = header.getName().toLowerCase(Locale.ROOT);
                    if (!headers.containsKey(name)) { headers.put(name, new ArrayList<String>()); }
                    headers.get(name).add(header.getValue());
                }
                ApiResponse result = new ApiResponse(response.getCode(), text, headers);
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    throw new K8sApiException(result);
                }
                return result;
            });
        } catch (IOException e) {
            throw new K8sApiException("请求 k8s api 失败: " + method + " " + uri.getPath(), e);
        } finally {
            manager.close();
        }
    }

    static JsonObject parseObject(String body) {
        try {
            JsonElement result = JsonParser.parseString(body);
            if (!result.isJsonObject()) { throw new JsonSyntaxException("expected JSON object"); }
            return result.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new K8sToolsException("解析 k8s api 响应失败: expected JSON object", e);
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

    // ==================== TLS ====================

    private static SSLContext buildSslContext(Builder b) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            if (b.insecureSkipTlsVerify) {
                ctx.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, null);
                return ctx;
            }
            if (b.caCertPem != null && !b.caCertPem.trim().isEmpty()) {
                Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509")
                        .generateCertificates(new ByteArrayInputStream(b.caCertPem.getBytes(StandardCharsets.UTF_8)));
                if (certificates.isEmpty()) { throw new GeneralSecurityException("CA bundle is empty"); }
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                int index = 0;
                for (Certificate certificate : certificates) {
                    ks.setCertificateEntry("k8s-ca-" + index++, certificate);
                }
                TrustManagerFactory tmf =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                ctx.init(null, tmf.getTrustManagers(), null);
                return ctx;
            }
            return SSLContext.getDefault(); // 使用 JVM 默认
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
        try {
            URI uri = new URI(u);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("apiServer must be an HTTP(S) URL without credentials, query or fragment");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid apiServer URL", e);
        }
        return u;
    }

    private static final class TlsSettings {
        private final SSLContext context;
        private final HostnameVerifier verifier;
        private TlsSettings(SSLContext context, HostnameVerifier verifier) {
            this.context = context;
            this.verifier = verifier;
        }
    }

    // ==================== Builder ====================

    public static final class Builder {
        private SshConfig execSshConfig;
        private String apiServer;
        private String token;
        private String caCertPem;
        private boolean insecureSkipTlsVerify;
        private boolean insecureConfiguredExplicitly;
        /** TLS 校验失败时自动降级为 trust-all 重试（忽略自签名证书），默认开启。 */
        private boolean tlsAutoFallback = true;
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 30000;
        private String redisUrl;
        private boolean refreshCache;

        /** 为 Pod Exec 提供 SSH 兜底；直接 API 接入时可选，fromSsh 自动保留传入配置。 */
        public Builder execSshConfig(SshConfig config) { this.execSshConfig = config; return this; }

        public Builder apiServer(String apiServer) { this.apiServer = apiServer; return this; }
        public Builder token(String token) { this.token = token; return this; }
        public Builder caCertPem(String caCertPem) { this.caCertPem = caCertPem; return this; }
        public Builder insecureSkipTlsVerify(boolean v) {
            this.insecureSkipTlsVerify = v;
            this.insecureConfiguredExplicitly = true;
            return this;
        }
        public Builder tlsAutoFallback(boolean v) { this.tlsAutoFallback = v; return this; }
        public Builder connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
        public Builder readTimeoutMs(int v) { this.readTimeoutMs = v; return this; }

        /**
         * SSH 接入时使用的 Redis URI，支持 redis/rediss、认证及数据库编号。
         * null 或空白关闭缓存；连接由 fromSsh 内部创建并关闭，不读取环境变量。
         */
        public Builder redisUrl(String v) { this.redisUrl = v; return this; }

        /** SSH 接入前删除当前 master 缓存再获取；需同时配置 redisUrl，不重放业务请求。 */
        public Builder refreshCache(boolean v) { this.refreshCache = v; return this; }

        /** 使用默认 SA 配置接入，Redis 缓存命中时不连接 SSH。默认直接跳过 TLS 校验。 */
        public K8sApiClient fromSsh(SshConfig config) {
            return fromSsh(config, null);
        }

        /**
         * 获取凭据并构造客户端。复制 options，不向调用方配置注入内部缓存或修改 Builder。
         * redisUrl 优先于 options 中的旧式外部缓存；内部连接在返回或异常时关闭。
         * 严格 TLS 需同时设置 insecureSkipTlsVerify(false) 和 tlsAutoFallback(false)。
         *
         * @param config SSH 配置
         * @param options SA、RBAC 和地址发现配置，null 使用默认值
         * @return 可立即查询或写入的客户端，无需 close
         */
        public K8sApiClient fromSsh(SshConfig config, ServiceTokenFetcher.Options options) {
            if (config == null) { throw new IllegalArgumentException("SshConfig is required"); }
            if (apiServer != null || token != null || caCertPem != null) {
                throw new IllegalArgumentException("fromSsh discovers credentials; use Options for discovery overrides");
            }
            if (connectTimeoutMs < 0 || readTimeoutMs < 0) {
                throw new IllegalArgumentException("timeouts must be >= 0");
            }
            URI redisUri = redisUri();
            if (refreshCache && redisUri == null) {
                throw new IllegalArgumentException("refreshCache requires redisUrl");
            }
            ServiceTokenFetcher.Options effective = options == null
                    ? new ServiceTokenFetcher.Options() : options.copy();
            long started = System.nanoTime();
            LOG.info("SSH 客户端初始化 host={} managedRedis={} redisEndpoint={} refreshCache={}",
                    LogSupport.field(config.getHost()), redisUri != null,
                    redisUri == null ? "-" : LogSupport.endpoint(redisUri.toString()), refreshCache);
            // 仅初始化期间需要 Redis；返回的客户端不持有池或 Redis 密码。
            try (JedisPool pool = redisUri == null ? null : new JedisPool(redisUri)) {
                if (pool != null) { effective.redisCache(new RedisServiceTokenCache(pool)); }
                try (ServiceTokenFetcher fetcher = new ServiceTokenFetcher(config, effective)) {
                    MasterInfo info = refreshCache ? fetcher.refresh() : fetcher.fetch();
                    boolean skipTls = !insecureConfiguredExplicitly || insecureSkipTlsVerify;
                    return K8sApiClient.builder().apiServer(info.getApiServerUrl()).token(info.getToken())
                            .caCertPem(skipTls ? null : info.getCaCertPem())
                            .insecureSkipTlsVerify(skipTls).tlsAutoFallback(tlsAutoFallback)
                            .connectTimeoutMs(connectTimeoutMs).readTimeoutMs(readTimeoutMs)
                            .execSshConfig(execSshConfig == null ? config : execSshConfig).build();
                }
            } catch (RuntimeException e) {
                LOG.error("SSH 客户端初始化失败 host={} elapsedMs={} errorType={}",
                        LogSupport.field(config.getHost()), LogSupport.elapsedMs(started), LogSupport.errorType(e));
                throw e;
            } finally {
                LogSupport.debug(LOG, "SSH 客户端初始化结束 host={} elapsedMs={}",
                        LogSupport.field(config.getHost()), LogSupport.elapsedMs(started));
            }
        }

        private URI redisUri() {
            if (redisUrl == null || redisUrl.trim().isEmpty()) { return null; }
            URI uri;
            try {
                uri = new URI(redisUrl.trim());
            } catch (URISyntaxException e) {
                // URI 语法异常会包含原始密码，不放入异常消息或 cause。
                throw new IllegalArgumentException("Invalid Redis URL syntax");
            }
            if (!("redis".equals(uri.getScheme()) || "rediss".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65535
                    || (uri.getRawUserInfo() != null && uri.getRawUserInfo().indexOf(':') < 0)
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPath() != null && !uri.getPath().matches("/?[0-9]*"))) {
                throw new IllegalArgumentException("Redis URL requires redis:// or rediss://, a valid host/database, and [:password@] or [user:password@] authentication");
            }
            return uri;
        }

        public K8sApiClient build() {
            if ((redisUrl != null && !redisUrl.trim().isEmpty()) || refreshCache) {
                throw new IllegalArgumentException("Redis options require fromSsh instead of build");
            }
            if (apiServer == null || apiServer.trim().isEmpty()) {
                throw new IllegalArgumentException("apiServer is required");
            }
            if (token == null || token.trim().isEmpty()) {
                throw new IllegalArgumentException("token is required");
            }
            if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("token must not contain line breaks");
            }
            if (connectTimeoutMs < 0 || readTimeoutMs < 0) {
                throw new IllegalArgumentException("timeouts must be >= 0");
            }
            return new K8sApiClient(this);
        }
    }
}

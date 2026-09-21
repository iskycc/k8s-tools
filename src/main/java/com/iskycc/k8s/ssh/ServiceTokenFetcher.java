package com.iskycc.k8s.ssh;

import com.iskycc.k8s.K8sToolsException;
import com.google.gson.JsonObject;
import com.iskycc.k8s.internal.LogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 通过 SSH 登录 k8s master 节点，创建/复用专用 ServiceAccount 并获取其长期 token，
 * 同时自动发现 API Server 地址、收集集群 CA 证书。
 *
 * <p><b>SA 处理策略</b>：先检查 SA 是否已存在；
 * 若已存在但拿不到永久 token（无 secret / secret 中 data.token 为空），
 * 则先删除该 SA（连同本工具创建的残留 secret）再重建，保证从零拿到可用 token。
 *
 * <p><b>永久 token</b>：{@code kubernetes.io/service-account-token} Secret 中的 token 由
 * token controller 签发；删除关联对象或服务端失效处理会使 token 不再可用：
 * <ol>
 *   <li>老集群(&lt;1.24)：SA 自动生成的 token secret，直接读取复用；</li>
 *   <li>本工具创建过的手动 secret，直接读取复用（幂等重跑）；</li>
 *   <li>新集群(&gt;=1.24)：创建时即带有 SA 注解的 secret，
 *       轮询等待 controller 填充 {@code data.token}（AlreadyExists 容忍）。</li>
 * </ol>
 *
 * <p><b>API Server 地址自动发现</b>（依次尝试）：
 * <ol>
 *   <li>{@code kubectl config view --minify -o jsonpath={.clusters[0].cluster.server}}</li>
 *   <li>{@code awk '/server:/{print $2; exit}' /etc/kubernetes/admin.conf}</li>
 *   <li>回退 {@code https://<ssh-host>:6443}</li>
 * </ol>
 *
 * <p>典型用法：
 * <pre>
 * SshConfig cfg = SshConfig.builder().host("10.4.4.8").username("root").password("***").build();
 * MasterInfo info = new ServiceTokenFetcher(cfg).fetch();
 * </pre>
 */
public class ServiceTokenFetcher implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceTokenFetcher.class);
    private final SshConfig sshConfig;
    private final Options options;
    private final SshExecutor ssh;

    public ServiceTokenFetcher(SshConfig sshConfig) {
        this(sshConfig, new Options());
    }

    public ServiceTokenFetcher(SshConfig sshConfig, Options options) {
        this(sshConfig, options, new SshExecutor(sshConfig));
    }

    /** 供测试注入自定义 executor。 */
    public ServiceTokenFetcher(SshConfig sshConfig, Options options, SshExecutor ssh) {
        this.sshConfig = sshConfig;
        this.options = options == null ? new Options() : options;
        this.ssh = ssh;
    }

    /**
     * 配置 Redis 时优先返回完整且配置匹配的缓存；未命中时执行：
     * SSH 连接 -> 确保 SA(存在但拿不到 token 则删除重建)
     * -> 获取永久 token -> 确保 RBAC 绑定 -> 自动发现 API 地址 -> 获取 CA。
     */
    public MasterInfo fetch() {
        long started = System.nanoTime();
        String stage = "validate";
        String master = LogSupport.field(sshConfig.getHost());
        try {
            options.validate();
            LOG.info("集群凭据获取开始 master={} namespace={} serviceAccount={} cacheEnabled={}",
                    master, options.serviceAccountNamespace, options.serviceAccount, options.redisCache != null);
            if (options.redisCache != null) {
                stage = "cache.read";
                MasterInfo cached = options.redisCache.load(sshConfig.getHost(), options.cacheContext());
                if (cached != null) {
                    LOG.info("集群凭据获取完成 master={} source=redis elapsedMs={}", master, LogSupport.elapsedMs(started));
                    return cached;
                }
            }
            stage = "ssh.connect";
            ssh.connect();
            stage = "sa.ensure";
            boolean saExisted = ensureServiceAccount();
            stage = "token.read";
            String token = fetchPermanentToken(saExisted);
            stage = "rbac.ensure";
            ensureClusterRoleBinding();
            stage = "api.discover";
            String apiServer = fetchApiServerUrl();
            stage = "ca.read";
            String caPem = options.fetchCaCert ? fetchCaCert() : null;
            MasterInfo info = new MasterInfo(apiServer, token, caPem,
                    options.serviceAccount, options.serviceAccountNamespace);
            if (options.redisCache != null) {
                stage = "cache.write";
                options.redisCache.save(sshConfig.getHost(), options.cacheContext(), info);
            }
            LOG.info("集群凭据获取完成 master={} source=ssh server={} caPresent={} elapsedMs={}",
                    master, LogSupport.endpoint(apiServer), caPem != null, LogSupport.elapsedMs(started));
            return info;
        } catch (RuntimeException e) {
            LOG.error("集群凭据获取失败 master={} namespace={} serviceAccount={} stage={} elapsedMs={} errorType={}",
                    master, LogSupport.field(options.serviceAccountNamespace), LogSupport.field(options.serviceAccount),
                    stage, LogSupport.elapsedMs(started), LogSupport.errorType(e));
            throw e;
        } finally {
            close();
        }
    }

    /** 删除 Redis 中的旧凭据后重新通过 SSH 获取；不会重放此前的 API 请求。 */
    public MasterInfo refresh() {
        options.validate();
        LOG.info("显式刷新集群凭据 master={}", LogSupport.field(sshConfig.getHost()));
        invalidateCache();
        return fetch();
    }

    /** 仅删除当前 master 的缓存；未配置 Redis 时不做任何操作。 */
    public void invalidateCache() {
        if (options.redisCache != null) {
            options.redisCache.invalidate(sshConfig.getHost());
        }
    }

    /**
     * 检查 SA 是否已存在；不存在则创建。
     *
     * @return true 表示执行前 SA 已存在于集群
     */
    private boolean ensureServiceAccount() {
        String sa = options.serviceAccount;
        String ns = options.serviceAccountNamespace;
        ExecResult get = ssh.exec("kubectl -n " + ns + " get sa " + sa, options.commandTimeoutMs);
        if (get.isSuccess()) {
            LogSupport.debug(LOG, "复用 ServiceAccount namespace={} name={}", ns, sa);
            return true;
        }
        ExecResult create = ssh.exec("kubectl create serviceaccount " + sa + " -n " + ns,
                options.commandTimeoutMs);
        if (create.isSuccess()) {
            LOG.info("已创建 ServiceAccount namespace={} name={}", ns, sa);
            return false;
        }
        if (isAlreadyExists(create)) {
            return true;
        }
        ExecResult recheck = ssh.exec("kubectl -n " + ns + " get sa " + sa, options.commandTimeoutMs);
        if (recheck.isSuccess()) {
            return true;
        }
        throw new K8sToolsException("确保 ServiceAccount " + ns + ":" + sa + " 存在失败; get: "
                + get.combinedOutput() + "; create: " + create.combinedOutput());
    }

    private void ensureClusterRoleBinding() {
        String sa = options.serviceAccount;
        String ns = options.serviceAccountNamespace;
        String binding = options.clusterRoleBindingName != null
                ? options.clusterRoleBindingName : sa;
        ExecResult create = ssh.exec("kubectl create clusterrolebinding " + binding
                        + " --clusterrole=" + options.clusterRole
                        + " --serviceaccount=" + ns + ":" + sa,
                options.commandTimeoutMs);
        if (create.isSuccess() || isAlreadyExists(create)) {
            LogSupport.debug(LOG, "ClusterRoleBinding 已存在或已创建 name={} created={}", binding, create.isSuccess());
            return;
        }
        ExecResult get = ssh.exec("kubectl get clusterrolebinding " + binding,
                options.commandTimeoutMs);
        if (!get.isSuccess()) {
            throw new K8sToolsException("确保 ClusterRoleBinding " + binding + " 存在失败; create: "
                    + create.combinedOutput() + "; get: " + get.combinedOutput());
        }
    }

    /**
     * 获取 SA 的永久 token；SA 已存在但拿不到 token 时先删除重建（见类注释）。
     */
    private String fetchPermanentToken(boolean saExisted) {
        String sa = options.serviceAccount;
        String ns = options.serviceAccountNamespace;
        String manualSecret = options.permanentTokenSecretName != null
                ? options.permanentTokenSecretName : sa + "-token";

        if (saExisted) {
            // 1) 老集群(<1.24)：SA 自动生成的 token secret，直接复用
            ExecResult saRes = ssh.exec("kubectl -n " + ns + " get sa " + sa
                    + " -o jsonpath={.secrets[0].name}", options.commandTimeoutMs);
            String autoSecret = saRes.isSuccess() ? saRes.getStdout().trim() : "";
            if (!autoSecret.isEmpty()) {
                String token = readSecretToken(autoSecret);
                if (token != null) {
                    LogSupport.debug(LOG, "复用 SA 自动关联的 token Secret namespace={} serviceAccount={}", ns, sa);
                    return token;
                }
            }
            // 2) 本工具此前创建的手动 secret（幂等重跑），直接复用
            String token = readSecretToken(manualSecret);
            if (token != null) {
                LogSupport.debug(LOG, "复用手动 token Secret namespace={} name={}", ns, manualSecret);
                return token;
            }
            // 3) SA 存在但拿不到永久 token：先删除再重建
            if (!options.recreateSaWhenTokenUnobtainable) {
                throw new K8sToolsException("ServiceAccount " + ns + ":" + sa
                        + " 已存在但无法获取永久 token，且已禁用自动删除重建"
                        + "(recreateSaWhenTokenUnobtainable=false)");
            }
            LOG.warn("已有 SA 无法读取 token，即将删除并重建 namespace={} serviceAccount={}", ns, sa);
            recreateServiceAccount(manualSecret);
        }

        // API Server 在创建时就校验 SA 注解，必须把注解与 Secret 一次提交。
        JsonObject secret = new JsonObject();
        secret.addProperty("apiVersion", "v1");
        secret.addProperty("kind", "Secret");
        secret.addProperty("type", "kubernetes.io/service-account-token");
        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", manualSecret);
        metadata.addProperty("namespace", ns);
        JsonObject annotations = new JsonObject();
        annotations.addProperty("kubernetes.io/service-account.name", sa);
        metadata.add("annotations", annotations);
        secret.add("metadata", metadata);
        ExecResult create = ssh.exec("printf '%s' " + shellQuote(secret.toString())
                + " | kubectl -n " + ns + " create -f -", options.commandTimeoutMs);
        if (!create.isSuccess() && !isAlreadyExists(create)) {
            throw new K8sToolsException("创建永久 token Secret " + ns + "/" + manualSecret
                    + " 失败: " + create.combinedOutput());
        }
        if (!create.isSuccess()) {
            ExecResult annotate = ssh.exec("kubectl -n " + ns + " annotate secret " + manualSecret
                    + " kubernetes.io/service-account.name=" + sa + " --overwrite",
                    options.commandTimeoutMs);
            if (!annotate.isSuccess()) {
                throw new K8sToolsException("为 Secret " + ns + "/" + manualSecret
                        + " 绑定 ServiceAccount 失败: " + annotate.combinedOutput());
            }
        }
        LogSupport.debug(LOG, "token Secret 已准备，等待 controller 填充 namespace={} name={}", ns, manualSecret);
        String token = readSecretTokenWithRetry(manualSecret);
        if (token == null) {
            throw new K8sToolsException("等待 Secret " + ns + "/" + manualSecret
                    + " 生成永久 token 超时(重试 " + options.tokenWaitRetries + " 次)");
        }
        return token;
    }

    /** SA 存在但 token 不可得：删除 SA（连同残留的手动 secret）后重建。 */
    private void recreateServiceAccount(String manualSecret) {
        String sa = options.serviceAccount;
        String ns = options.serviceAccountNamespace;
        ExecResult del = ssh.exec("kubectl -n " + ns + " delete sa " + sa + " --ignore-not-found",
                options.commandTimeoutMs);
        if (!del.isSuccess()) {
            throw new K8sToolsException("删除已存在但 token 不可得的 ServiceAccount "
                    + ns + ":" + sa + " 失败: " + del.combinedOutput());
        }
        // best-effort 清理可能残留的手动 secret（失败可由 create 的 AlreadyExists 容忍兜底）
        ssh.exec("kubectl -n " + ns + " delete secret " + manualSecret + " --ignore-not-found",
                options.commandTimeoutMs);
        ExecResult create = ssh.exec("kubectl create serviceaccount " + sa + " -n " + ns,
                options.commandTimeoutMs);
        if (!create.isSuccess() && !isAlreadyExists(create)) {
            throw new K8sToolsException("重建 ServiceAccount " + ns + ":" + sa
                    + " 失败: " + create.combinedOutput());
        }
    }

    /** 读取 secret 中 data.token（单次），未就绪/不存在返回 null。 */
    private String readSecretToken(String secretName) {
        String ns = options.serviceAccountNamespace;
        ExecResult r = ssh.exec("kubectl -n " + ns + " get secret " + secretName
                + " -o jsonpath={.data.token}", options.commandTimeoutMs);
        String b64 = r.getStdout().trim();
        if (!r.isSuccess() || b64.isEmpty()) {
            return null;
        }
        try {
            String token = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8).trim();
            return token.isEmpty() ? null : token;
        } catch (IllegalArgumentException e) {
            throw new K8sToolsException("Secret " + secretName + " 中 token 不是合法 base64", e);
        }
    }

    /** 轮询等待 token controller 填充 data.token。 */
    private String readSecretTokenWithRetry(String secretName) {
        for (int i = 0; i <= options.tokenWaitRetries; i++) {
            if (i > 0) {
                sleep(options.tokenWaitIntervalMs);
            }
            String token = readSecretToken(secretName);
            if (token != null) {
                LogSupport.debug(LOG, "token Secret 已就绪 namespace={} name={} attempt={}",
                        options.serviceAccountNamespace, secretName, i + 1);
                return token;
            }
            LogSupport.debug(LOG, "等待 token Secret namespace={} name={} attempt={} maxAttempts={} intervalMs={}",
                    options.serviceAccountNamespace, secretName, i + 1,
                    (long) options.tokenWaitRetries + 1, options.tokenWaitIntervalMs);
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new K8sToolsException("等待永久 token 生成时被中断", e);
        }
    }

    /**
     * 自动发现 API Server 地址：kubectl config view -> admin.conf -> https://host:6443。
     */
    private String fetchApiServerUrl() {
        if (options.apiServerOverride != null && !options.apiServerOverride.trim().isEmpty()) {
            LOG.info("API 地址已确定 source=override server={}", LogSupport.endpoint(options.apiServerOverride));
            return options.apiServerOverride.trim();
        }
        // 1) 当前 kubeconfig
        ExecResult r = ssh.exec("kubectl config view --minify"
                + " -o jsonpath={.clusters[0].cluster.server}", options.commandTimeoutMs);
        String url = normalizeDiscoveredUrl(r.getStdout());
        if (r.isSuccess() && url != null) {
            LOG.info("API 地址已发现 source=kubeconfig server={}", LogSupport.endpoint(url));
            return url;
        }
        LogSupport.debug(LOG, "API 地址发现继续下一来源 source=kubeconfig exitCode={} validUrl={}", r.getExitCode(), url != null);
        // 2) master 上的 admin.conf
        ExecResult conf = ssh.exec("awk '/server:/{print $2; exit}' " + shellQuote(options.kubeConfigPath),
                options.commandTimeoutMs);
        url = normalizeDiscoveredUrl(conf.getStdout());
        if (conf.isSuccess() && url != null) {
            LOG.info("API 地址已发现 source=admin-conf server={}", LogSupport.endpoint(url));
            return url;
        }
        // 3) 兜底：按 master 默认 6443 端口推断
        url = urlForHost("https", sshConfig.getHost().trim(), 6443);
        LOG.warn("API 地址发现使用默认端口 source=fallback server={}", LogSupport.endpoint(url));
        return url;
    }

    static boolean isValidApiServerUrl(String value) {
        if (value == null || value.trim().isEmpty()) { return false; }
        try {
            URI uri = new URI(value.trim());
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private String normalizeDiscoveredUrl(String value) {
        if (!isValidApiServerUrl(value)) { return null; }
        URI uri = URI.create(value.trim());
        String host = uri.getHost();
        if ("localhost".equalsIgnoreCase(host) || host.matches("127\\.[0-9]+\\.[0-9]+\\.[0-9]+")
                || "0.0.0.0".equals(host) || "[::1]".equals(host) || "[::]".equals(host)) {
            return urlForHost(uri.getScheme(), sshConfig.getHost().trim(), uri.getPort());
        }
        return value.trim();
    }

    private static String urlForHost(String scheme, String host, int port) {
        try {
            return new URI(scheme, null, host, port, null, null, null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("SSH host cannot be used as an API address", e);
        }
    }

    private String fetchCaCert() {
        ExecResult r = ssh.exec("cat -- " + shellQuote(options.caCertPath), options.commandTimeoutMs);
        if (r.isSuccess() && r.getStdout().contains("BEGIN CERTIFICATE")) {
            LogSupport.debug(LOG, "集群 CA 已读取 master={}", LogSupport.field(sshConfig.getHost()));
            return r.getStdout().trim();
        }
        // CA 获取失败不致命：客户端默认会自动忽略自签名/校验失败
        LOG.warn("未读取到集群 CA master={} exitCode={}，严格 TLS 模式需由 JVM 信任集群证书",
                LogSupport.field(sshConfig.getHost()), r.getExitCode());
        return null;
    }

    private static boolean isAlreadyExists(ExecResult r) {
        String out = r.combinedOutput();
        return out.contains("AlreadyExists") || out.contains("already exists");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @Override
    public void close() {
        ssh.close();
    }

    /** 获取行为的可配置项。 */
    public static final class Options {
        private String serviceAccount = "k8s-tools";
        private String serviceAccountNamespace = "kube-system";
        private String clusterRoleBindingName;
        private String clusterRole = "cluster-admin";
        /** 手动创建的永久 token Secret 名称；null 时默认 "<sa>-token"。 */
        private String permanentTokenSecretName;
        /** SA 已存在但拿不到永久 token 时，是否先删除 SA 再重建（默认开启）。 */
        private boolean recreateSaWhenTokenUnobtainable = true;
        /** 等待 token controller 填充 data.token 的重试次数。 */
        private int tokenWaitRetries = 10;
        /** 等待 token controller 填充 data.token 的重试间隔（毫秒）。 */
        private long tokenWaitIntervalMs = 1000;
        private String apiServerOverride;
        /** master 上 kubeconfig 路径（API 地址自动发现第 2 层）。 */
        private String kubeConfigPath = "/etc/kubernetes/admin.conf";
        private String caCertPath = "/etc/kubernetes/pki/ca.crt";
        private boolean fetchCaCert = true;
        private int commandTimeoutMs = 30000;
        private RedisServiceTokenCache redisCache;

        /** 复制配置；已有外部缓存引用仍由原调用方管理。 */
        public Options copy() {
            Options copy = new Options();
            copy.serviceAccount = serviceAccount;
            copy.serviceAccountNamespace = serviceAccountNamespace;
            copy.clusterRoleBindingName = clusterRoleBindingName;
            copy.clusterRole = clusterRole;
            copy.permanentTokenSecretName = permanentTokenSecretName;
            copy.recreateSaWhenTokenUnobtainable = recreateSaWhenTokenUnobtainable;
            copy.tokenWaitRetries = tokenWaitRetries;
            copy.tokenWaitIntervalMs = tokenWaitIntervalMs;
            copy.apiServerOverride = apiServerOverride;
            copy.kubeConfigPath = kubeConfigPath;
            copy.caCertPath = caCertPath;
            copy.fetchCaCert = fetchCaCert;
            copy.commandTimeoutMs = commandTimeoutMs;
            copy.redisCache = redisCache;
            return copy;
        }

        private void validate() {
            if (apiServerOverride != null && !apiServerOverride.trim().isEmpty()
                    && !isValidApiServerUrl(apiServerOverride)) {
                throw new IllegalArgumentException("apiServerOverride must be a valid HTTP(S) server URL");
            }
            requireResourceName(serviceAccount, "serviceAccount", false);
            if (serviceAccountNamespace == null || serviceAccountNamespace.length() > 63
                    || !serviceAccountNamespace.matches("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?")) {
                throw new IllegalArgumentException("serviceAccountNamespace must be a DNS label");
            }
            requireResourceName(clusterRole, "clusterRole", true);
            if (clusterRoleBindingName != null) { requireResourceName(clusterRoleBindingName, "clusterRoleBindingName", true); }
            requireResourceName(permanentTokenSecretName == null ? serviceAccount + "-token" : permanentTokenSecretName,
                    "permanentTokenSecretName", false);
            if (tokenWaitRetries < 0 || tokenWaitIntervalMs < 0 || commandTimeoutMs <= 0) {
                throw new IllegalArgumentException("invalid retry or timeout configuration");
            }
            if (kubeConfigPath == null || !kubeConfigPath.startsWith("/")
                    || caCertPath == null || !caCertPath.startsWith("/")) {
                throw new IllegalArgumentException("kubeConfigPath and caCertPath must be absolute remote paths");
            }
        }

        private String cacheContext() {
            JsonObject context = new JsonObject();
            context.addProperty("serviceAccount", serviceAccount);
            context.addProperty("namespace", serviceAccountNamespace);
            context.addProperty("role", clusterRole);
            context.addProperty("binding", clusterRoleBindingName == null ? serviceAccount : clusterRoleBindingName);
            context.addProperty("secret", permanentTokenSecretName == null ? serviceAccount + "-token" : permanentTokenSecretName);
            context.addProperty("apiServerOverride", apiServerOverride == null ? "" : apiServerOverride.trim());
            context.addProperty("kubeConfigPath", kubeConfigPath);
            context.addProperty("caCertPath", caCertPath);
            context.addProperty("fetchCaCert", fetchCaCert);
            return context.toString();
        }

        private static void requireResourceName(String value, String option, boolean rbac) {
            String pattern = rbac ? "[A-Za-z0-9][A-Za-z0-9_.:-]*" : "[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?";
            if (value == null || value.length() > 253 || !value.matches(pattern)) {
                throw new IllegalArgumentException("invalid " + option);
            }
        }

        public Options serviceAccount(String v) { this.serviceAccount = v; return this; }
        public Options serviceAccountNamespace(String v) { this.serviceAccountNamespace = v; return this; }
        public Options clusterRoleBindingName(String v) { this.clusterRoleBindingName = v; return this; }
        public Options clusterRole(String v) { this.clusterRole = v; return this; }
        public Options permanentTokenSecretName(String v) { this.permanentTokenSecretName = v; return this; }
        public Options recreateSaWhenTokenUnobtainable(boolean v) { this.recreateSaWhenTokenUnobtainable = v; return this; }
        public Options tokenWaitRetries(int v) { this.tokenWaitRetries = v; return this; }
        public Options tokenWaitIntervalMs(long v) { this.tokenWaitIntervalMs = v; return this; }
        public Options apiServerOverride(String v) { this.apiServerOverride = v; return this; }
        public Options kubeConfigPath(String v) { this.kubeConfigPath = v; return this; }
        public Options caCertPath(String v) { this.caCertPath = v; return this; }
        public Options fetchCaCert(boolean v) { this.fetchCaCert = v; return this; }
        public Options commandTimeoutMs(int v) { this.commandTimeoutMs = v; return this; }
        /** 配置后优先读缓存，未命中才通过 SSH 获取；连接池仍由调用方关闭。 */
        public Options redisCache(RedisServiceTokenCache v) { this.redisCache = v; return this; }
    }
}

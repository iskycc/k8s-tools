package com.iskycc.k8s.ssh;

import com.iskycc.k8s.K8sToolsException;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 通过 SSH 登录 k8s master 节点，创建/复用专用 ServiceAccount 并获取其<b>永久 token</b>，
 * 同时自动发现 API Server 地址、收集集群 CA 证书。
 *
 * <p><b>SA 处理策略</b>：先检查 SA 是否已存在；
 * 若已存在但拿不到永久 token（无 secret / secret 中 data.token 为空），
 * 则先删除该 SA（连同本工具创建的残留 secret）再重建，保证从零拿到可用 token。
 *
 * <p><b>永久 token</b>：{@code kubernetes.io/service-account-token} Secret 中的 token 由
 * token controller 签发，永久有效、不过期：
 * <ol>
 *   <li>老集群(&lt;1.24)：SA 自动生成的 token secret，直接读取复用；</li>
 *   <li>本工具创建过的手动 secret，直接读取复用（幂等重跑）；</li>
 *   <li>新集群(&gt;=1.24)：手动创建 secret + annotate 绑定 SA，
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
     * 执行完整获取流程：SSH 连接 -> 确保 SA(存在但拿不到 token 则删除重建)
     * -> 获取永久 token -> 确保 RBAC 绑定 -> 自动发现 API 地址 -> 获取 CA。
     */
    public MasterInfo fetch() {
        try {
            ssh.connect();
            boolean saExisted = ensureServiceAccount();
            String token = fetchPermanentToken(saExisted);
            ensureClusterRoleBinding();
            String apiServer = fetchApiServerUrl();
            String caPem = options.fetchCaCert ? fetchCaCert() : null;
            return new MasterInfo(apiServer, token, caPem,
                    options.serviceAccount, options.serviceAccountNamespace);
        } finally {
            close();
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
            return true;
        }
        ExecResult create = ssh.exec("kubectl create serviceaccount " + sa + " -n " + ns,
                options.commandTimeoutMs);
        if (create.isSuccess()) {
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
                    return token;
                }
            }
            // 2) 本工具此前创建的手动 secret（幂等重跑），直接复用
            String token = readSecretToken(manualSecret);
            if (token != null) {
                return token;
            }
            // 3) SA 存在但拿不到永久 token：先删除再重建
            if (!options.recreateSaWhenTokenUnobtainable) {
                throw new K8sToolsException("ServiceAccount " + ns + ":" + sa
                        + " 已存在但无法获取永久 token，且已禁用自动删除重建"
                        + "(recreateSaWhenTokenUnobtainable=false)");
            }
            recreateServiceAccount(manualSecret);
        }

        // 手动创建永久 token secret（新集群 >=1.24 或 SA 刚重建）
        ExecResult create = ssh.exec("kubectl -n " + ns + " create secret generic " + manualSecret
                + " --type=kubernetes.io/service-account-token", options.commandTimeoutMs);
        if (!create.isSuccess() && !isAlreadyExists(create)) {
            throw new K8sToolsException("创建永久 token Secret " + ns + "/" + manualSecret
                    + " 失败: " + create.combinedOutput());
        }
        ExecResult annotate = ssh.exec("kubectl -n " + ns + " annotate secret " + manualSecret
                + " kubernetes.io/service-account.name=" + sa + " --overwrite",
                options.commandTimeoutMs);
        if (!annotate.isSuccess()) {
            throw new K8sToolsException("为 Secret " + ns + "/" + manualSecret
                    + " 绑定 ServiceAccount 失败: " + annotate.combinedOutput());
        }
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
                return token;
            }
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
            return options.apiServerOverride.trim();
        }
        // 1) 当前 kubeconfig
        ExecResult r = ssh.exec("kubectl config view --minify"
                + " -o jsonpath={.clusters[0].cluster.server}", options.commandTimeoutMs);
        String url = r.getStdout().trim();
        if (r.isSuccess() && !url.isEmpty()) {
            return url;
        }
        // 2) master 上的 admin.conf
        ExecResult conf = ssh.exec("awk '/server:/{print $2; exit}' " + options.kubeConfigPath,
                options.commandTimeoutMs);
        url = conf.getStdout().trim();
        if (conf.isSuccess() && !url.isEmpty()) {
            return url;
        }
        // 3) 兜底：按 master 默认 6443 端口推断
        return "https://" + sshConfig.getHost() + ":6443";
    }

    private String fetchCaCert() {
        ExecResult r = ssh.exec("cat " + options.caCertPath, options.commandTimeoutMs);
        if (r.isSuccess() && r.getStdout().contains("BEGIN CERTIFICATE")) {
            return r.getStdout().trim();
        }
        // CA 获取失败不致命：客户端默认会自动忽略自签名/校验失败
        return null;
    }

    private static boolean isAlreadyExists(ExecResult r) {
        String out = r.combinedOutput();
        return out.contains("AlreadyExists") || out.contains("already exists");
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
    }
}

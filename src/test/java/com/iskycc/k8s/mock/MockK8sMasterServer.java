package com.iskycc.k8s.mock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 模拟 k8s master 节点的 SSH 服务器（E2E 用，基于 Apache MINA SSHD）。
 *
 * <p>接受密码认证，按 {@link SaScenario} 预设 kubectl 行为，覆盖：
 * SA 存在性检查、SA 删除重建、永久 token secret 的创建/复用/AlreadyExists、
 * token controller 填充延迟、API Server 地址自动发现的三个层级。
 */
public class MockK8sMasterServer implements Closeable {

    /** 模拟的集群 SA 场景。 */
    public enum SaScenario {
        /** 全新集群：SA 不存在 → 创建 SA + 手动创建永久 secret（controller 延迟 2 次填充）。 */
        FRESH_CLUSTER,
        /** 老集群(&lt;1.24)：SA 存在且自动生成的 token secret 可直接复用。 */
        LEGACY_AUTO_SECRET,
        /** 幂等重跑：SA 存在、无自动 secret，但工具此前创建的手动 secret 已有 token；kubeconfig 损坏 → API 地址走 admin.conf。 */
        EXISTING_MANUAL_SECRET,
        /** SA 存在但 token 不可得 → 应删除 SA 重建；残留 secret 删除失败 → create 返回 AlreadyExists；API 地址发现全部失败 → 走 host:6443 兜底。 */
        BROKEN_SA
    }

    private static final String SA_NAME = "k8s-tools";
    private static final String LEGACY_SECRET_NAME = "k8s-tools-token-mock42";
    private static final String BROKEN_SECRET_NAME = "k8s-tools-token-broken";
    private static final String MANUAL_SECRET_NAME = "k8s-tools-token";
    private static final String ADMIN_CONF_URL = "https://10.96.0.1:6443";
    /** FRESH_CLUSTER 模式下，前 N 次读取 secret 返回空（模拟 controller 尚未填充）。 */
    private static final int CONTROLLER_FILL_DELAY_READS = 2;

    private final SaScenario scenario;
    private final String username;
    private final String password;
    private final String apiServerUrl;
    private final String token;
    private final String caCertPem;

    private final List<String> executedCommands =
            Collections.synchronizedList(new ArrayList<String>());
    private final Map<String, AtomicInteger> secretTokenReads =
            Collections.synchronizedMap(new HashMap<String, AtomicInteger>());
    private volatile JsonObject createdSecretManifest;

    public JsonObject getCreatedSecretManifest() {
        return createdSecretManifest == null ? null : createdSecretManifest.deepCopy();
    }

    private SshServer sshd;
    private Path hostKeyFile;
    private int port = -1;

    public MockK8sMasterServer(SaScenario scenario, String username, String password,
                               String apiServerUrl, String token, String caCertPem) {
        this.scenario = scenario;
        this.username = username;
        this.password = password;
        this.apiServerUrl = apiServerUrl;
        this.token = token;
        this.caCertPem = caCertPem;
    }

    public void start() throws IOException {
        hostKeyFile = Files.createTempFile("mock-master-hostkey", ".ser");
        SimpleGeneratorHostKeyProvider keyProvider =
                new SimpleGeneratorHostKeyProvider(hostKeyFile);
        keyProvider.setAlgorithm("RSA");
        keyProvider.setKeySize(2048);

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(keyProvider);
        sshd.setPasswordAuthenticator((user, pass, session) ->
                username.equals(user) && password.equals(pass));
        sshd.setCommandFactory(new CommandFactory() {
            @Override
            public Command createCommand(ChannelSession channel, String commandLine) {
                return new MockCommand(commandLine, MockK8sMasterServer.this::handleCommand);
            }
        });
        sshd.start();
        this.port = sshd.getPort();
    }

    public int getPort() {
        return port;
    }

    /** 已执行过的命令列表（按顺序），用于断言交互过程。 */
    public List<String> getExecutedCommands() {
        synchronized (executedCommands) {
            return new ArrayList<String>(executedCommands);
        }
    }

    Response handleCommand(String cmd) {
        executedCommands.add(cmd);
        String c = cmd.trim();

        // ---------- ServiceAccount ----------
        if (c.startsWith("kubectl -n kube-system get sa ") && c.contains("jsonpath={.secrets[0].name}")) {
            return handleGetSaSecretName();
        }
        if (c.equals("kubectl -n kube-system get sa " + SA_NAME)) {
            // SA 存在性检查
            if (scenario == SaScenario.FRESH_CLUSTER) {
                return new Response(1, "", "Error from server (NotFound): serviceaccounts \""
                        + SA_NAME + "\" not found\n");
            }
            return Response.ok("NAME        SECRETS   AGE\n" + SA_NAME + "   1         3d\n");
        }
        if (c.startsWith("kubectl create serviceaccount")) {
            return Response.ok("serviceaccount/" + SA_NAME + " created\n");
        }
        if (c.startsWith("kubectl -n kube-system delete sa ")) {
            if (scenario == SaScenario.BROKEN_SA) {
                return Response.ok("serviceaccount \"" + SA_NAME + "\" deleted\n");
            }
            return Response.ok("");
        }
        if (c.startsWith("kubectl -n kube-system delete secret ")) {
            if (scenario == SaScenario.BROKEN_SA) {
                // 模拟删除卡住/失败：后续 create secret 将得到 AlreadyExists
                return new Response(1, "", "error: timed out waiting for the condition\n");
            }
            return Response.ok("");
        }

        // ---------- RBAC ----------
        if (c.startsWith("kubectl create clusterrolebinding")) {
            return Response.ok("clusterrolebinding.rbac.authorization.k8s.io/" + SA_NAME + " created\n");
        }
        if (c.startsWith("kubectl get clusterrolebinding")) {
            return Response.ok("NAME        ROLE                      AGE\n"
                    + SA_NAME + "   ClusterRole/cluster-admin 3d\n");
        }

        // ---------- 永久 token Secret ----------
        if (c.startsWith("kubectl -n kube-system create secret generic ")
                && c.contains("--type=kubernetes.io/service-account-token")) {
            return new Response(1, "", "Secret is invalid: metadata.annotations[kubernetes.io/service-account.name]: Required value\n");
        }
        if (c.startsWith("printf '%s' '") && c.endsWith("' | kubectl -n kube-system create -f -")) {
            String document = c.substring("printf '%s' '".length(),
                    c.length() - "' | kubectl -n kube-system create -f -".length());
            JsonObject manifest;
            try {
                manifest = JsonParser.parseString(document).getAsJsonObject();
                JsonObject metadata = manifest.getAsJsonObject("metadata");
                if (!"Secret".equals(manifest.get("kind").getAsString())
                        || !"v1".equals(manifest.get("apiVersion").getAsString())
                        || !"kubernetes.io/service-account-token".equals(manifest.get("type").getAsString())
                        || !MANUAL_SECRET_NAME.equals(metadata.get("name").getAsString())
                        || !"kube-system".equals(metadata.get("namespace").getAsString())
                        || !SA_NAME.equals(metadata.getAsJsonObject("annotations")
                                .get("kubernetes.io/service-account.name").getAsString())) {
                    return new Response(1, "", "Secret manifest is invalid\n");
                }
            } catch (RuntimeException e) {
                return new Response(1, "", "Secret manifest is missing required fields\n");
            }
            createdSecretManifest = manifest;
            if (scenario == SaScenario.BROKEN_SA) {
                return new Response(1, "", "Error from server (AlreadyExists): secrets \""
                        + MANUAL_SECRET_NAME + "\" already exists\n");
            }
            return Response.ok("secret/" + MANUAL_SECRET_NAME + " created\n");
        }
        if (c.startsWith("kubectl -n kube-system annotate secret ")
                && c.contains("kubernetes.io/service-account.name=")) {
            return Response.ok("secret/" + MANUAL_SECRET_NAME + " annotated\n");
        }
        if (c.startsWith("kubectl -n kube-system get secret ") && c.contains("jsonpath={.data.token}")) {
            return handleGetSecretToken(c);
        }

        // ---------- API Server 地址自动发现 ----------
        if (c.startsWith("kubectl config view")) {
            if (scenario == SaScenario.FRESH_CLUSTER || scenario == SaScenario.LEGACY_AUTO_SECRET) {
                return Response.ok(apiServerUrl + "\n");
            }
            return new Response(1, "", "error: no configuration has been provided\n");
        }
        if (c.startsWith("awk ") && c.contains("admin.conf")) {
            if (scenario == SaScenario.EXISTING_MANUAL_SECRET) {
                return Response.ok(ADMIN_CONF_URL + "\n");
            }
            return new Response(2, "", "awk: cannot open /etc/kubernetes/admin.conf\n");
        }

        // ---------- CA ----------
        if (c.equals("cat -- '/etc/kubernetes/pki/ca.crt'")) {
            return Response.ok(caCertPem);
        }
        return new Response(127, "", "bash: " + c + ": command not found\n");
    }

    private Response handleGetSaSecretName() {
        switch (scenario) {
            case LEGACY_AUTO_SECRET:
                return Response.ok(LEGACY_SECRET_NAME + "\n");
            case BROKEN_SA:
                return Response.ok(BROKEN_SECRET_NAME + "\n");
            default:
                return Response.ok(""); // SA 无自动 secret
        }
    }

    private Response handleGetSecretToken(String c) {
        // 解析 secret 名称: "kubectl -n kube-system get secret <name> -o jsonpath=..."
        String[] parts = c.split("\\s+");
        String secretName = parts.length > 5 ? parts[5] : "";

        if (LEGACY_SECRET_NAME.equals(secretName)) {
            return Response.ok(base64Token() + "\n");
        }
        if (BROKEN_SECRET_NAME.equals(secretName)) {
            return Response.ok(""); // 损坏：data.token 为空 → 应触发 SA 删除重建
        }
        if (MANUAL_SECRET_NAME.equals(secretName)) {
            int reads = secretTokenReads
                    .computeIfAbsent(secretName, k -> new AtomicInteger())
                    .incrementAndGet();
            switch (scenario) {
                case FRESH_CLUSTER:
                    // 模拟 token controller 延迟填充
                    return reads <= CONTROLLER_FILL_DELAY_READS
                            ? Response.ok("") : Response.ok(base64Token() + "\n");
                case BROKEN_SA:
                    // 第 1 次是重建前的探测(NotFound)，之后 secret 已就绪
                    return reads <= 1
                            ? new Response(1, "", "Error from server (NotFound): secrets \""
                                    + MANUAL_SECRET_NAME + "\" not found\n")
                            : Response.ok(base64Token() + "\n");
                case EXISTING_MANUAL_SECRET:
                default:
                    return Response.ok(base64Token() + "\n");
            }
        }
        return new Response(1, "", "Error from server (NotFound): secrets \"" + secretName
                + "\" not found\n");
    }

    private String base64Token() {
        return Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        if (sshd != null) {
            sshd.stop(true);
            sshd = null;
        }
        if (hostKeyFile != null) {
            Files.deleteIfExists(hostKeyFile);
        }
    }

    /** 命令执行响应。 */
    static final class Response {
        final int exitCode;
        final String stdout;
        final String stderr;

        Response(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        static Response ok(String stdout) {
            return new Response(0, stdout, "");
        }
    }

    /** 把预设响应写回 SSH exec 通道。 */
    private static final class MockCommand implements Command {
        private final String commandLine;
        private final Function<String, Response> handler;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;

        MockCommand(String commandLine, Function<String, Response> handler) {
            this.commandLine = commandLine;
            this.handler = handler;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            Thread t = new Thread(() -> {
                try {
                    Response r = handler.apply(commandLine);
                    if (out != null) {
                        out.write(r.stdout.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        out.close();
                    }
                    if (err != null) {
                        err.write(r.stderr.getBytes(StandardCharsets.UTF_8));
                        err.flush();
                        err.close();
                    }
                    callback.onExit(r.exitCode);
                } catch (IOException e) {
                    callback.onExit(1);
                }
            }, "mock-ssh-command");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void destroy(ChannelSession channel) throws IOException {
            // no-op
        }
    }
}

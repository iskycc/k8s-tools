import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.api.K8sResources;
import com.iskycc.k8s.api.ListOptions;
import com.iskycc.k8s.api.PodExecException;
import com.iskycc.k8s.api.PodExecOptions;
import com.iskycc.k8s.api.PodExecResult;
import com.iskycc.k8s.ssh.SshConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 8 示例：SSH/Redis 接入 -> 跨全部 namespace 按名称关键词查找 Pod -> 执行整条 shell 命令。
 * 依赖 io.github.iskycc:k8s-tools:1.5.5，运行说明见 pod-search-exec.md。
 * 本文件位于 docs，不进入库的发布包。初始化可能写入 SA/Secret/RBAC。
 */
public final class K8sPodSearchExecExample {
    private K8sPodSearchExecExample() { }

    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            usage();
            return;
        }
        if (args.length < 2 || args.length > 3) {
            usage();
            System.exit(2);
            return;
        }
        try {
            String keyword = args[0];
            String command = args[1]; // 整条命令作为一个 Java 参数，不自行按空格拆分。
            String container = args.length == 3 ? args[2] : null;
            if (keyword.trim().isEmpty() || command.trim().isEmpty()) {
                throw new ExampleException("Pod 关键词和命令不能为空");
            }
            // 在连接前校验容器名称；默认无 stdin/TTY、30 秒总超时、4 MiB 输出上限。
            PodExecOptions.builder().container(container).build();

            // 1. SSH 配置：有密码时强制密码模式，否则使用显式配置的私钥。
            SshConfig.Builder ssh = SshConfig.builder()
                    .host(requiredEnv("K8S_MASTER_IP"))
                    .port(Integer.parseInt(env("K8S_SSH_PORT", "22")))
                    .username(env("K8S_SSH_USER", "root"));
            String password = System.getenv("K8S_SSH_PASSWORD");
            if (password != null && !password.isEmpty()) {
                ssh.password(password).passwordOnly(true);
            } else {
                ssh.privateKeyPath(requiredEnv("K8S_SSH_KEY"))
                        .privateKeyPassphrase(System.getenv("K8S_SSH_KEY_PASSPHRASE"));
            }

            // 2. Redis 由客户端管理；未配置 URL 时不使用缓存。自动获取 API 地址和 token。
            K8sApiClient client = K8sApiClient.builder()
                    .redisUrl(env("K8S_TOOLS_REDIS_URL", null))
                    .fromSsh(ssh.build());

            // 3. 跨全部 namespace 搜索；执行时使用匹配 Pod 自身的 namespace。
            JsonObject pod = findPodByKeyword(client, keyword);
            JsonObject metadata = pod.getAsJsonObject("metadata");
            String namespace = metadata.get("namespace").getAsString();
            String podName = metadata.get("name").getAsString();
            String selectedContainer = selectContainer(pod, container);
            System.err.println("执行目标：" + namespace + "/" + podName + "，容器：" + selectedContainer);

            // 4. 执行整条命令，如 "ls -al /tmp"，或 "sh /app/tests/run.sh"。
            // AUTO 自动选择 WebSocket 或旧集群 SSH，不需要在示例里判断 Kubernetes 版本。
            PodExecOptions options = PodExecOptions.builder()
                    .container(selectedContainer)
                    .timeoutMs(30000) // 长时间运行的测试用例可适当调大。
                    .maxOutputBytes(4 * 1024 * 1024)
                    .build();
            PodExecResult result = client.execShell(namespace, podName, options, command);
            System.out.print(result.getStdout());
            System.err.print(result.getStderr());
            System.err.println("\n执行退出码：" + result.getExitCode());
            if (!result.isSuccess()) { System.exit(1); }
        } catch (ExampleException e) {
            // 只输出示例自己生成的提示；网络/SSH 异常的正文与消息可能含敏感内容。
            System.err.println(e.hint);
            System.exit(2);
        } catch (PodExecException e) {
            System.err.println("执行中止：" + e.getFailureReason() + "；未自动重试。");
            System.exit(1);
        } catch (K8sApiException e) {
            System.err.println("Kubernetes 请求失败，HTTP 状态码：" + e.getStatusCode());
            System.exit(1);
        } catch (RuntimeException e) {
            System.err.println("运行失败（" + e.getClass().getSimpleName()
                    + "），请检查参数、SSH/Redis 配置和诊断日志；不输出凭据或异常正文。");
            System.exit(1);
        }
    }

    /** 跨全部 namespace 自动分页；完整名称优先，同名时可用 namespace/pod 精确选择。 */
    public static JsonObject findPodByKeyword(K8sApiClient client, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new ExampleException("Pod 关键词不能为空");
        }
        List<JsonObject> matches = new ArrayList<JsonObject>();
        List<JsonObject> exactMatches = new ArrayList<JsonObject>();
        boolean qualifiedName = keyword.indexOf('/') >= 0;
        for (JsonObject pod : client.resource(K8sResources.PODS).inAllNamespaces().listAll(ListOptions.builder()
                .fieldSelector("status.phase=Running").limit(100).build())) {
            JsonObject metadata = pod.getAsJsonObject("metadata");
            if (metadata.has("deletionTimestamp") && !metadata.get("deletionTimestamp").isJsonNull()) { continue; }
            String name = metadata.get("name").getAsString();
            String identity = metadata.get("namespace").getAsString() + "/" + name;
            if (qualifiedName ? identity.equals(keyword) : name.contains(keyword)) {
                matches.add(pod);
                if (name.equals(keyword) || identity.equals(keyword)) { exactMatches.add(pod); }
            }
        }
        if (!exactMatches.isEmpty()) { matches = exactMatches; }
        if (matches.isEmpty()) {
            throw new ExampleException("全部 namespace 中未找到名称匹配、处于 Running 且未删除的 Pod；请检查关键词。");
        }
        if (matches.size() > 1) {
            List<String> names = new ArrayList<String>();
            for (JsonObject pod : matches) {
                JsonObject metadata = pod.getAsJsonObject("metadata");
                names.add(metadata.get("namespace").getAsString() + "/" + metadata.get("name").getAsString());
            }
            throw new ExampleException("匹配到多个 Pod：" + names + "；请把关键词改为其中一个 namespace/pod。");
        }
        return matches.get(0);
    }

    /** 单容器自动选择；多容器必须明确指定，避免在 sidecar 中执行用例。 */
    public static String selectContainer(JsonObject pod, String requested) {
        JsonArray containers = pod.getAsJsonObject("spec").getAsJsonArray("containers");
        List<String> names = new ArrayList<String>();
        for (JsonElement item : containers) {
            names.add(item.getAsJsonObject().get("name").getAsString());
        }
        if (requested != null) {
            if (!names.contains(requested)) {
                throw new ExampleException("指定容器不在 Pod 中；可用容器：" + names);
            }
            return requested;
        }
        if (names.size() != 1) {
            throw new ExampleException("请通过第三个参数指定容器；可用容器：" + names);
        }
        return names.get(0);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String requiredEnv(String name) {
        String value = env(name, null);
        if (value == null) { throw new ExampleException("缺少环境变量：" + name); }
        return value;
    }

    private static void usage() {
        System.out.println("用法：K8sPodSearchExecExample <Pod 名称关键词或 namespace/pod> '<整条 shell 命令>' [容器名]");
        System.out.println("示例：K8sPodSearchExecExample nginx 'ls -al /tmp' nginx");
        System.out.println("跨全部 namespace 搜索，使用匹配 Pod 的实际 namespace 执行；不需要配置 K8S_NAMESPACE。");
        System.out.println("环境：K8S_MASTER_IP、K8S_SSH_USER、K8S_SSH_PASSWORD（或 K8S_SSH_KEY）。");
        System.out.println("可选：K8S_SSH_PORT（22）、K8S_TOOLS_REDIS_URL、K8S_SSH_KEY_PASSPHRASE。");
        System.out.println("fromSsh 可能写入 SA/Secret/RBAC，默认跳过 TLS 校验；命令需要容器有 /bin/sh。");
        System.out.println("此帮助模式不连接 SSH、Redis 或 Kubernetes。");
    }

    private static final class ExampleException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String hint;
        private ExampleException(String hint) {
            super("Pod search example cannot continue");
            this.hint = hint;
        }
    }
}

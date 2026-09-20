package com.iskycc.k8s;

import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.model.Deployment;
import com.iskycc.k8s.api.model.Namespace;
import com.iskycc.k8s.api.model.Node;
import com.iskycc.k8s.api.model.Pod;
import com.iskycc.k8s.api.model.Service;
import com.iskycc.k8s.api.model.VersionInfo;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;
import com.iskycc.k8s.ssh.SshConfig;

/**
 * 命令行演示入口：SSH 登录 master 获取 token，然后调用 k8s API 输出集群数据。
 *
 * <pre>
 * java -cp 'k8s-tools.jar:dependency/*' com.iskycc.k8s.Main --host 10.4.4.8 --user root --password '***'
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String host = null;
        int port = 22;
        String user = "root";
        String password = null;
        String keyPath = null;
        boolean passwordOnly = false;
        String namespace = "default";
        String apiServerOverride = null;
        boolean insecure = true;
        String redisUrl = System.getenv("K8S_TOOLS_REDIS_URL");
        boolean refreshCache = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--host":
                    host = next(args, ++i, a);
                    break;
                case "--port":
                    port = Integer.parseInt(next(args, ++i, a));
                    break;
                case "--user":
                    user = next(args, ++i, a);
                    break;
                case "--password":
                    password = next(args, ++i, a);
                    break;
                case "--password-only":
                    passwordOnly = true;
                    break;
                case "--key":
                    keyPath = next(args, ++i, a);
                    break;
                case "--namespace":
                    namespace = next(args, ++i, a);
                    break;
                case "--api-server":
                    apiServerOverride = next(args, ++i, a);
                    break;
                case "--insecure":
                    insecure = true;
                    break;
                case "--strict-tls":
                    insecure = false;
                    break;
                case "--redis-url":
                    redisUrl = next(args, ++i, a);
                    break;
                case "--refresh-cache":
                    refreshCache = true;
                    break;
                case "-h":
                case "--help":
                    usage();
                    return;
                default:
                    System.err.println("未知参数: " + a);
                    usage();
                    System.exit(2);
            }
        }
        if (host == null) {
            System.err.println("缺少 --host");
            usage();
            System.exit(2);
        }
        if (passwordOnly && (password == null || password.isEmpty())) {
            System.err.println("--password-only 必须同时提供非空 --password");
            System.exit(2);
            return;
        }

        if (refreshCache && (redisUrl == null || redisUrl.trim().isEmpty())) {
            System.err.println("--refresh-cache 需要 --redis-url 或 K8S_TOOLS_REDIS_URL");
            System.exit(2);
            return;
        }

        try {
            SshConfig.Builder sshBuilder = SshConfig.builder()
                    .host(host).port(port).username(user);
            if (passwordOnly) {
                sshBuilder.password(password).passwordOnly(true);
            } else if (keyPath != null) {
                sshBuilder.privateKeyPath(keyPath);
            } else if (password != null) {
                sshBuilder.password(password);
            } else {
                System.err.println("必须提供 --password 或 --key");
                System.exit(2);
            }

            System.out.println("[1/3] 获取 service token：" + host
                    + (redisUrl == null || redisUrl.trim().isEmpty() ? "，通过 SSH" : "，优先 Redis 缓存") + " ...");
            ServiceTokenFetcher.Options options = new ServiceTokenFetcher.Options();
            if (apiServerOverride != null) {
                options.apiServerOverride(apiServerOverride);
            }
            K8sApiClient client = K8sApiClient.builder()
                    .redisUrl(redisUrl).refreshCache(refreshCache)
                    .insecureSkipTlsVerify(insecure).tlsAutoFallback(false)
                    .fromSsh(sshBuilder.build(), options);
            System.out.println("      完成: API Server=" + client.getApiServer());
            System.out.println("[2/3] 构建 K8sApiClient (TLS: "
                    + (insecure ? "跳过证书及主机名校验" : "严格校验，不自动降级") + ")");

            System.out.println("[3/3] 调用 k8s API 获取数据 ...");
            VersionInfo version = client.getVersion();
            System.out.println("\n== 集群版本 ==");
            System.out.println("  gitVersion: " + version.getGitVersion()
                    + "  platform: " + version.getPlatform());

            System.out.println("\n== Nodes ==");
            for (Node n : client.listNodes()) {
                System.out.println("  " + n.getName()
                        + "  Ready=" + n.isReady() + "  InternalIP=" + n.getInternalIp());
            }

            System.out.println("\n== Namespaces ==");
            for (Namespace ns : client.listNamespaces()) {
                System.out.println("  " + ns.getName() + "  " + ns.getPhase());
            }

            System.out.println("\n== Pods (ns=" + namespace + ") ==");
            for (Pod p : client.listPods(namespace)) {
                System.out.println("  " + p.getName() + "  " + p.getPhase()
                        + "  node=" + p.getNodeName()
                        + "  podIP=" + (p.getStatus() != null ? p.getStatus().getPodIP() : "-"));
            }

            System.out.println("\n== Services (ns=" + namespace + ") ==");
            for (Service s : client.listServices(namespace)) {
                System.out.println("  " + s.getName() + "  " + s.getType()
                        + "  clusterIP=" + s.getClusterIP());
            }

            System.out.println("\n== Deployments (ns=" + namespace + ") ==");
            for (Deployment d : client.listDeployments(namespace)) {
                System.out.println("  " + d.getName() + "  replicas=" + d.getReplicas()
                        + "  ready=" + d.getReadyReplicas());
            }
        } catch (K8sToolsException e) {
            System.err.println("执行失败: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            // 不回显可能含用户名和密码的 Redis URI。
            System.err.println("配置或 Redis 连接失败，请检查参数及 Redis 服务");
            System.exit(2);
        }
    }

    private static String next(String[] args, int i, String flag) {
        if (i >= args.length) {
            System.err.println("参数 " + flag + " 缺少值");
            System.exit(2);
        }
        return args[i];
    }

    private static void usage() {
        System.out.println("用法: java -cp 'k8s-tools.jar:dependency/*' com.iskycc.k8s.Main --host <master-ip> [选项]");
        System.out.println("  --port <n>          SSH 端口, 默认 22");
        System.out.println("  --user <u>          SSH 用户, 默认 root");
        System.out.println("  --password <p>      SSH 密码(未传 --key 时仅用密码认证)");
        System.out.println("  --password-only     仅用密码，忽略 --key、本地私钥及 SSH agent；需 --password");
        System.out.println("  --key <path>        SSH 私钥路径(同时传密码时优先，--password-only 除外)");
        System.out.println("  --namespace <ns>    查询的命名空间, 默认 default");
        System.out.println("  --api-server <url>  覆盖自动发现的 API Server 地址");
        System.out.println("  --insecure          跳过证书及主机名校验(默认开启，适用于自签名集群)");
        System.out.println("  --strict-tls        使用发现的 CA 或 JVM 信任库严格校验，不自动降级");
        System.out.println("  --redis-url <url>   Redis 地址，也可用 K8S_TOOLS_REDIS_URL；缓存命中不连接 SSH");
        System.out.println("  --refresh-cache     删除当前 master 的缓存并重新通过 SSH 获取");
    }
}

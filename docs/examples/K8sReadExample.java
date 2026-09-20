import com.google.gson.JsonObject;
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.ListOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Maven 接入指南的独立 Java 8 示例：直接使用已有凭据查询 Pod 名称。 */
public final class K8sReadExample {
    private K8sReadExample() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("设置 K8S_API_SERVER、K8S_TOKEN、K8S_CA_FILE 后运行。");
            System.out.println("K8S_NAMESPACE 可选，默认 default。仅查询 Pod 名称，不创建集群资源。");
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("只支持无参数或 --help");
        }

        String namespace = System.getenv("K8S_NAMESPACE");
        if (namespace == null || namespace.trim().isEmpty()) {
            namespace = "default";
        }
        String caPem = new String(Files.readAllBytes(Paths.get(requiredEnv("K8S_CA_FILE"))),
                StandardCharsets.UTF_8);
        if (caPem.trim().isEmpty()) {
            throw new IllegalArgumentException("K8S_CA_FILE 内容不能为空");
        }
        K8sApiClient client = K8sApiClient.builder()
                .apiServer(requiredEnv("K8S_API_SERVER"))
                .token(requiredEnv("K8S_TOKEN"))
                .caCertPem(caPem)
                .insecureSkipTlsVerify(false)
                .tlsAutoFallback(false)
                .connectTimeoutMs(10000)
                .readTimeoutMs(30000)
                .build();

        // 每页最多请求 100 条；listAll 会将所有页汇总到内存。
        List<JsonObject> pods = client.pods(namespace)
                .listAll(ListOptions.builder().limit(100).build());
        System.out.println("命名空间 " + namespace + "，Pod 数量：" + pods.size());
        for (JsonObject pod : pods) {
            System.out.println(pod.getAsJsonObject("metadata").get("name").getAsString());
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少环境变量：" + name);
        }
        return value;
    }
}

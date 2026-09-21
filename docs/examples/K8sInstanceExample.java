import com.iskycc.k8s.K8sInstance;
import com.iskycc.k8s.K8sTools;
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.api.model.PodSummary;
import com.iskycc.k8s.api.model.ResourceDetails;
import com.iskycc.k8s.api.model.ResourceSummary;

import java.util.List;

/**
 * Java 8：七个参数构造 K8sInstance，统一初始化后使用完整 SDK。
 * 此入口为当前源码新增，已发布的 1.6.0 不含；先从源码构建 1.6.0-SNAPSHOT。
 * 本示例位于 docs，不进入发布包。SSH 初始化可能写入 SA/Secret/RBAC，后续仅查询。
 */
public final class K8sInstanceExample {
    private K8sInstanceExample() { }

    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("用法：K8sInstanceExample [资源名称关键词]，默认 nginx");
            System.out.println("必填：K8S_MASTER_IP、K8S_SSH_USER、K8S_SSH_PASSWORD、K8S_REDIS_IP。");
            System.out.println("可选：K8S_SSH_PORT=22、K8S_REDIS_PORT=6379、K8S_REDIS_PASSWORD（原始密码）。");
            System.out.println("Redis 默认用户/数据库 0；API 地址自动发现，默认跳过证书及主机名校验。");
            return;
        }
        if (args.length > 1) { System.err.println("只接受一个关键词；用 --help 查看配置。"); System.exit(2); return; }
        try {
            // 参数顺序：master IP、SSH 端口、SSH 用户名、SSH 密码、Redis 密码、Redis IP、Redis 端口。
            K8sInstance instance = new K8sInstance(
                    required("K8S_MASTER_IP"), Integer.parseInt(env("K8S_SSH_PORT", "22")),
                    required("K8S_SSH_USER"), required("K8S_SSH_PASSWORD"),
                    System.getenv("K8S_REDIS_PASSWORD"), required("K8S_REDIS_IP"),
                    Integer.parseInt(env("K8S_REDIS_PORT", "6379")));
            String keyword = args.length == 0 ? "nginx" : args[0];
            if (keyword.trim().isEmpty()) { throw new IllegalArgumentException("资源名称关键词不能为空"); }

            // 内部完成 Redis 缓存判断、密码 SSH、凭据获取和 API 地址发现；无需 Jedis 或手填 Redis URL。
            K8sApiClient tools = K8sTools.init(instance);
            System.out.println("Kubernetes: " + tools.getVersion().getGitVersion());
            for (PodSummary pod : tools.searchPods(keyword)) {
                System.out.println(pod.getNamespace() + "/" + pod.getPodName() + " containers=" + pod.getContainerNames());
            }
            showIdentities("ConfigMap 简易结果", tools.searchConfigMaps(keyword));
            showIdentities("Service 简易结果", tools.searchServices(keyword));
            for (ResourceDetails pod : tools.searchPodsDetailed(keyword)) {
                System.out.println("Pod 详细结果：" + pod.getNamespace() + "/" + pod.getName()
                        + " resourceVersion=" + pod.getResourceVersion());
                // pod.getSpec() / getStatus() / toJson() 可读取详细字段，本示例不打印完整正文。
            }
            System.out.println("已发现 API 版本数：" + tools.discoverApiVersions().size());
        } catch (K8sApiException e) {
            System.err.println("Kubernetes 请求失败，HTTP 状态码：" + e.getStatusCode());
            System.exit(1);
        } catch (RuntimeException e) {
            System.err.println("运行失败（" + e.getClass().getSimpleName() + "），请检查参数和诊断日志。");
            System.exit(1);
        }
    }

    private static void showIdentities(String label, List<ResourceSummary> items) {
        System.out.println(label + "：" + items.size());
        for (ResourceSummary item : items) { System.out.println("  " + item.getNamespace() + "/" + item.getName()); }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String required(String name) {
        String value = env(name, null);
        if (value == null) { throw new IllegalArgumentException("Missing " + name); }
        return value;
    }
}

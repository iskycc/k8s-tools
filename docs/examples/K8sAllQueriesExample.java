import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iskycc.k8s.K8sToolsException;
import com.iskycc.k8s.api.ApiResource;
import com.iskycc.k8s.api.ApiResponse;
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.api.K8sResourceClient;
import com.iskycc.k8s.api.K8sResources;
import com.iskycc.k8s.api.ListOptions;
import com.iskycc.k8s.api.model.K8sList;
import com.iskycc.k8s.api.model.PodSummary;
import com.iskycc.k8s.api.model.ResourceSummary;
import com.iskycc.k8s.ssh.SshConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Java 8 完整查询示例，依赖 io.github.iskycc:k8s-tools:1.6.0，客户端内部管理 Redis。
 * SSH 获取/复用 SA -> Redis 缓存 -> 自动发现 API -> 查询；启动说明见 all-queries.md。
 * SSH 初始化可能写入 SA/Secret/RBAC；本示例后续的 API 请求全部为 GET。
 * 放在 docs/examples，不进入本库的发布 jar、sources 或 Javadoc。
 */
public final class K8sAllQueriesExample {
    private final K8sApiClient client;
    private final String namespace;
    private final int pageSize;
    private int completed;
    private int skipped;
    private int failed;

    private K8sAllQueriesExample(K8sApiClient client, String namespace, int pageSize) {
        this.client = client;
        this.namespace = namespace;
        this.pageSize = pageSize;
    }

    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            usage();
            return;
        }
        boolean refresh = args.length == 1 && "--refresh-cache".equals(args[0]);
        if (args.length != 0 && !refresh) {
            usage();
            System.exit(2);
            return;
        }
        try {
            // 1. SSH：只配置 master IP 和 SSH 凭据，无需 API URL、token 或 CA 文件。
            SshConfig.Builder sshBuilder = SshConfig.builder()
                    .host(requiredEnv("K8S_MASTER_IP"))
                    .port(positiveIntEnv("K8S_SSH_PORT", 22, 65535))
                    .username(env("K8S_SSH_USER", "root"))
                    .connectTimeoutMs(15000);
            String password = System.getenv("K8S_SSH_PASSWORD");
            if (password != null && !password.isEmpty()) {
                sshBuilder.password(password).passwordOnly(true);
            } else {
                sshBuilder.privateKeyPath(requiredEnv("K8S_SSH_KEY"))
                        .privateKeyPassphrase(System.getenv("K8S_SSH_KEY_PASSPHRASE"));
            }
            SshConfig ssh = sshBuilder.build();
            String namespace = env("K8S_NAMESPACE", "default");
            if (!namespace.matches("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?") || namespace.length() > 63) {
                throw new IllegalArgumentException("K8S_NAMESPACE 必须为具体命名空间名称");
            }
            int pageSize = positiveIntEnv("K8S_PAGE_SIZE", 100, 1000);

            // 2. 客户端内部管理 Redis：判断缓存，必要时 SSH 获取并自动发现 API 地址。
            // 有密码用 redis://:password@host:6379/0，ACL 用 redis://user:password@host:6379/0。
            // 密码中的特殊字符需百分号编码；无需引用 Jedis 或缓存实现，也无需关闭连接池。
            // --refresh-cache 会在 fromSsh 内删除旧缓存并重新获取。
            K8sApiClient client = K8sApiClient.builder()
                    .redisUrl(requiredEnv("K8S_TOOLS_REDIS_URL"))
                    .refreshCache(refresh)
                    .fromSsh(ssh);
            if (refresh) { System.out.println("Redis 凭据已重新获取。"); }
            System.out.println("API Server: " + client.getApiServer());
            System.out.println("TLS: 跳过证书及主机名校验；查询命名空间: " + namespace);

            // 3. 依次执行本库提供的各类查询，默认跳过 HTTPS 证书和主机名校验。
            K8sAllQueriesExample example = new K8sAllQueriesExample(client, namespace, pageSize);
            example.queryTypedModels();
            example.queryResources();
            example.querySearch();
            example.queryPaginationAndSelectors();
            example.queryDiscoveryAndCustomResources();
            example.queryRawApi();
            System.out.println("\n查询完成：成功 " + example.completed
                    + "，跳过 " + example.skipped + "，失败 " + example.failed);
            if (example.failed > 0) {
                throw new K8sToolsException("部分查询失败");
            }
        } catch (K8sApiException e) {
            System.err.println("查询中止，HTTP 状态码：" + e.getStatusCode());
            if (e.getStatusCode() == 401) {
                System.err.println("凭据可能失效；检查后用 --refresh-cache 重新运行。本次不会自动重放请求。");
            }
            System.exit(1);
        } catch (RuntimeException e) {
            // 不输出堆栈、异常正文、Redis URI、SSH 密码或 token。
            System.err.println("运行失败（" + e.getClass().getSimpleName()
                    + "），请核对环境配置、SSH/Redis 服务及前面的查询结果。使用 --help 查看配置。");
            System.exit(1);
        }
    }

    /** 1.6.0 公共搜索方法：始终跨 namespace、自动分页，简易和详细结果均返回全部匹配项。 */
    private void querySearch() {
        String keyword = env("K8S_SEARCH_KEYWORD", "kube");
        ListOptions options = ListOptions.builder().limit(pageSize).build();
        query("searchPods", () -> showSearch(client.searchPods(keyword, options)));
        query("searchPodsDetailed", () -> showSearch(client.searchPodsDetailed(keyword, options)));
        query("searchConfigMaps", () -> showSearch(client.searchConfigMaps(keyword, options)));
        query("searchConfigMapsDetailed", () -> showSearch(client.searchConfigMapsDetailed(keyword, options)));
        query("searchServices", () -> showSearch(client.searchServices(keyword, options)));
        query("searchServicesDetailed", () -> showSearch(client.searchServicesDetailed(keyword, options)));
    }

    private static void showSearch(List<? extends ResourceSummary> results) {
        System.out.println("跨全部 namespace 匹配 " + results.size() + " 个对象，最多展示 10 个摘要");
        for (int i = 0; i < Math.min(results.size(), 10); i++) {
            ResourceSummary item = results.get(i);
            System.out.println("  " + item.getNamespace() + "/" + item.getName()
                    + (item instanceof PodSummary ? " containers=" + ((PodSummary) item).getContainerNames() : ""));
        }
        // 详细版保留完整 spec/status/data，但本 Demo 不输出正文或配置值。
    }

    /** 旧的简化 POJO 查询入口；null 表示跨命名空间，仅适用于这些 list 快捷方法。 */
    private void queryTypedModels() {
        query("getVersion", () -> System.out.println(client.getVersion().getGitVersion()));
        query("listNodes", () -> showModels(client.listNodes()));
        query("listNamespaces", () -> showModels(client.listNamespaces()));
        query("listPods(namespace)", () -> showModels(client.listPods(namespace)));
        query("listServices(namespace)", () -> showModels(client.listServices(namespace)));
        query("listDeployments(namespace)", () -> showModels(client.listDeployments(namespace)));
        query("listPods(null)，跨命名空间", () -> showModels(client.listPods(null)));
        query("listServices(null)，跨命名空间", () -> showModels(client.listServices(null)));
        query("listDeployments(null)，跨命名空间", () -> showModels(client.listDeployments(null)));
    }

    /** 七个常用资源入口：返回完整 JSON，但控制台只打印 metadata 摘要。 */
    private void queryResources() {
        queryResource("nodes", client.nodes());
        queryResource("namespaces", client.namespaces());
        String pod = queryResource("pods", client.pods(namespace));
        queryResource("services", client.services(namespace));
        String deployment = queryResource("deployments", client.deployments(namespace));
        queryResource("configMaps", client.configMaps(namespace));
        queryResource("secrets（不打印 data/stringData）", client.secrets(namespace));

        // 其余内置资源通过 ResourceDefinition 常量访问，不自行推测 plural。
        queryResource("resource(SERVICE_ACCOUNTS).inNamespace", client.resource(K8sResources.SERVICE_ACCOUNTS)
                .inNamespace(namespace));
        queryResource("resource(CUSTOM_RESOURCE_DEFINITIONS)，集群资源",
                client.resource(K8sResources.CUSTOM_RESOURCE_DEFINITIONS));
        query("pods.inAllNamespaces().list", () -> showPage(client.pods(namespace)
                .inAllNamespaces().list(pageOptions())));

        // 只有列表非空时才查询实际存在的对象，不要求用户手填 Pod/Deployment 名称。
        if (pod != null) {
            query("Pod status 子资源 GET", () -> showObject(client.pods(namespace).subresource(pod, "status").get()));
        }
        if (deployment != null) {
            query("Deployment status 子资源 GET", () -> showObject(client.deployments(namespace)
                    .subresource(deployment, "status").get()));
            query("Deployment scale 子资源 GET", () -> {
                JsonObject scale = client.deployments(namespace).subresource(deployment, "scale").get();
                showObject(scale);
                if (scale.has("spec") && scale.get("spec").isJsonObject()) {
                    System.out.println("replicas=" + stringValue(scale.getAsJsonObject("spec"), "replicas"));
                }
            });
        }
    }

    /** 演示 list(options)、exists(name)、get(name)；exists 仅将 404 转为 false。 */
    private String queryResource(String label, K8sResourceClient resource) {
        List<String> names = new ArrayList<String>();
        query(label + ".list", () -> {
            K8sList<JsonObject> page = resource.list(pageOptions());
            showPage(page);
            if (!page.getItems().isEmpty()) {
                JsonObject metadata = page.getItems().get(0).getAsJsonObject("metadata");
                if (metadata != null && metadata.has("name")) { names.add(metadata.get("name").getAsString()); }
            }
        });
        if (names.isEmpty()) { return null; }
        String name = names.get(0);
        query(label + ".exists", () -> System.out.println(name + " exists=" + resource.exists(name)));
        query(label + ".get", () -> showObject(resource.get(name)));
        return name;
    }

    private void queryPaginationAndSelectors() {
        K8sResourceClient pods = client.pods(namespace);
        query("Pod labelSelector + fieldSelector", () -> showPage(pods.list(ListOptions.builder()
                .labelSelector(env("K8S_LABEL_SELECTOR", null))
                .fieldSelector(env("K8S_FIELD_SELECTOR", "status.phase=Running"))
                .limit(pageSize).timeoutSeconds(30).build())));
        query("Pod listAll 自动遍历所有页", () -> showItems(pods.listAll(pageOptions())));
        query("Pod list + continue 逐页遍历", () -> {
            ListOptions next = pageOptions();
            Set<String> seen = new HashSet<String>();
            String version = null;
            int pageNumber = 0;
            while (true) {
                K8sList<JsonObject> page = pods.list(next);
                K8sList.ListMeta metadata = page.getMetadata();
                String currentVersion = metadata == null ? null : metadata.getResourceVersion();
                if (version != null && currentVersion != null && !version.equals(currentVersion)) {
                    throw new K8sToolsException("分页 resourceVersion 改变");
                }
                if (currentVersion != null) { version = currentVersion; }
                System.out.println("页码 " + (++pageNumber));
                showPage(page);
                String token = metadata == null ? null : metadata.getContinueToken();
                if (token == null || token.isEmpty()) { break; }
                if (!seen.add(token)) { throw new K8sToolsException("重复的分页 continue token"); }
                next = next.withContinueToken(token);
            }
        });
    }

    /** 遍历服务端实际声明的全部 API 版本和 list 资源，自动涵盖 CRD/聚合 API。 */
    private void queryDiscoveryAndCustomResources() {
        List<String> versions = new ArrayList<String>();
        query("discoverApiVersions", () -> {
            versions.addAll(client.discoverApiVersions());
            System.out.println(versions);
        });
        for (String version : versions) {
            List<ApiResource> resources = new ArrayList<ApiResource>();
            query("discoverResources(" + version + ")", () -> resources.addAll(client.discoverResources(version)));
            for (ApiResource item : resources) {
                System.out.println("  " + version + "/" + item.getName() + " kind=" + item.getKind()
                        + " namespaced=" + item.isNamespaced() + " verbs=" + item.getVerbs());
                // verbs 表示 API 能力，不代表 RBAC；子资源不能作为独立集合 list。
                if (item.isSubresource() || !item.getVerbs().contains("list")) { continue; }
                K8sResourceClient discovered = client.resource(item.toDefinition(version));
                if (item.isNamespaced()) { discovered = discovered.inNamespace(namespace); }
                final K8sResourceClient resource = discovered;
                // 每种资源仅查询首页；大集群可按上面的分页示例继续处理。
                query("Discovery list " + version + "/" + item.getName(), () -> showPage(resource.list(pageOptions())));
            }
        }

        // 同一入口也可直接传 CRD 的真实 group/version 和 plural，无需创建 Java 模型。
        query("resource(apiVersion, plural)，由 Discovery 确定作用域", () -> {
            K8sResourceClient resource = client.resource(env("K8S_RESOURCE_API_VERSION", "apps/v1"),
                    env("K8S_RESOURCE_PLURAL", "deployments"));
            if (resource.getDefinition().isNamespaced()) { resource = resource.inNamespace(namespace); }
            showPage(resource.list(pageOptions()));
        });
    }

    private void queryRawApi() {
        query("getRaw(/version)", () -> {
            JsonObject version = JsonParser.parseString(client.getRaw("/version")).getAsJsonObject();
            System.out.println("gitVersion=" + stringValue(version, "gitVersion"));
        });
        query("request(GET)，读取状态码和响应头", () -> {
            ApiResponse response = client.request("GET", "/api/v1/nodes",
                    Collections.singletonMap("limit", Integer.toString(pageSize)), null, null);
            System.out.println("HTTP=" + response.getStatusCode() + ", content-type=" + response.getHeader("Content-Type"));
            // response.getBody() 是原始正文；按需解析，不将凭据或 Secret 正文写入日志。
            JsonObject document = JsonParser.parseString(response.getBody()).getAsJsonObject();
            System.out.println("items=" + document.getAsJsonArray("items").size());
        });
    }

    private ListOptions pageOptions() { return ListOptions.builder().limit(pageSize).timeoutSeconds(30).build(); }

    private void query(String label, Runnable action) {
        System.out.println("\n== " + label + " ==");
        try {
            action.run();
            completed++;
        } catch (K8sApiException e) {
            if (e.getStatusCode() == 401) { throw e; }
            if (e.getStatusCode() == 403 || e.getStatusCode() == 404) {
                skipped++;
                System.out.println("跳过：HTTP " + e.getStatusCode() + "（权限不足、资源不支持或对象已删除）");
            } else {
                failed++;
                System.err.println("查询失败：HTTP " + e.getStatusCode() + "；未自动重试，未输出响应正文。");
            }
        } catch (K8sToolsException | IllegalArgumentException | UnsupportedOperationException e) {
            failed++;
            System.err.println("查询失败：" + e.getClass().getSimpleName() + "；未输出可能含敏感数据的异常正文。");
        }
    }

    private static void showModels(List<?> models) {
        System.out.println("本次返回 " + models.size() + " 个对象（旧 POJO 接口不自动翻页）");
        for (int i = 0; i < Math.min(models.size(), 10); i++) { System.out.println("  " + models.get(i)); }
    }

    private static void showPage(K8sList<JsonObject> page) {
        showItems(page.getItems());
        K8sList.ListMeta metadata = page.getMetadata();
        if (metadata != null) {
            System.out.println("resourceVersion=" + metadata.getResourceVersion()
                    + ", hasMore=" + (metadata.getContinueToken() != null && !metadata.getContinueToken().isEmpty())
                    + ", remainingItemCount=" + metadata.getRemainingItemCount());
        }
    }

    private static void showItems(List<JsonObject> items) {
        System.out.println("本次返回 " + items.size() + " 个对象，最多展示 10 个名称");
        for (int i = 0; i < Math.min(items.size(), 10); i++) { showObject(items.get(i)); }
    }

    private static void showObject(JsonObject object) {
        JsonObject metadata = object.has("metadata") && object.get("metadata").isJsonObject()
                ? object.getAsJsonObject("metadata") : new JsonObject();
        System.out.println("  " + stringValue(object, "kind") + " "
                + stringValue(metadata, "namespace") + "/" + stringValue(metadata, "name"));
    }

    private static String stringValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? "-" : value.getAsString();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String requiredEnv(String name) {
        String value = env(name, null);
        if (value == null) { throw new IllegalArgumentException("缺少环境变量 " + name); }
        return value;
    }

    private static int positiveIntEnv(String name, int fallback, int max) {
        int value = Integer.parseInt(env(name, Integer.toString(fallback)));
        if (value <= 0 || value > max) { throw new IllegalArgumentException("无效的 " + name); }
        return value;
    }

    private static void usage() {
        System.out.println("用法：K8sAllQueriesExample [--refresh-cache | --help]");
        System.out.println("必填：K8S_MASTER_IP、K8S_TOOLS_REDIS_URL，及 K8S_SSH_PASSWORD 或 K8S_SSH_KEY。");
        System.out.println("可选：K8S_SSH_PORT=22、K8S_SSH_USER=root、K8S_SSH_KEY_PASSPHRASE。");
        System.out.println("查询：K8S_NAMESPACE=default、K8S_PAGE_SIZE=100、K8S_LABEL_SELECTOR、K8S_FIELD_SELECTOR。");
        System.out.println("跨 namespace 搜索：K8S_SEARCH_KEYWORD=kube，仅按资源名称包含匹配。");
        System.out.println("自定义资源：K8S_RESOURCE_API_VERSION=apps/v1、K8S_RESOURCE_PLURAL=deployments。");
        System.out.println("无需手填 API 地址或证书。缓存未命中/强制刷新时 SSH 初始化可能创建 SA、Secret 和 RBAC。");
        System.out.println("后续 API 全部 GET；TLS 跳过证书及主机名校验；不输出 token、Redis URI 或 Secret 正文。");
    }
}

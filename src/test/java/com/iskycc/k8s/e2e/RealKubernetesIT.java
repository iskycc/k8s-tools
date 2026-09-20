package com.iskycc.k8s.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.iskycc.k8s.K8sToolsException;
import com.iskycc.k8s.api.DeleteOptions;
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.api.K8sResourceClient;
import com.iskycc.k8s.api.K8sResources;
import com.iskycc.k8s.api.ListOptions;
import com.iskycc.k8s.api.PatchType;
import com.iskycc.k8s.api.ResourceDefinition;
import com.iskycc.k8s.api.WriteOptions;
import com.iskycc.k8s.api.model.K8sList;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;
import com.iskycc.k8s.ssh.SshConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 仅由 real-e2e profile 执行：真实 OpenSSH/kubectl、Redis 和一次性 kind 集群，无 mock。 */
public class RealKubernetesIT {
    private static final String SA = "k8s-tools-e2e";
    private static final String TOKEN_KEY = "127.0.0.1ServiceToken";
    private static final String URL_KEY = "127.0.0.1ApiServerUrl";
    private static K8sApiClient client;
    private static ServiceTokenFetcher.Options fetchOptions;
    private static String redisUrl;
    private String namespace;

    @BeforeClass
    public static void connectToDisposableCluster() throws Exception {
        assertTrue("必须显式启用 real-e2e profile", Boolean.getBoolean("k8s.tools.realE2e"));
        assertEquals("仅允许一次性 GitHub runner 环境", "true", required("GITHUB_ACTIONS"));
        assertEquals("kind-k8s-tools-e2e", kubectl("config", "current-context"));
        redisUrl = required("E2E_REDIS_URL");
        assertEquals("127.0.0.1", URI.create(redisUrl).getHost());
        fetchOptions = new ServiceTokenFetcher.Options().serviceAccount(SA)
                .clusterRoleBindingName(SA).tokenWaitRetries(60).tokenWaitIntervalMs(500)
                .kubeConfigPath("/home/k8se2e/.kube/config").caCertPath("/home/k8se2e/.kube/ca.crt");
        client = K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh(false), fetchOptions);
        assertEquals(required("E2E_EXPECTED_API_SERVER"), client.getApiServer());
    }

    @Before
    public void createNamespace() {
        namespace = "k8s-tools-it-" + UUID.randomUUID().toString().substring(0, 8);
        client.namespaces().create(json("{\"metadata\":{\"name\":\"" + namespace + "\"}}"));
    }

    @After
    public void deleteNamespace() {
        if (client != null && namespace != null) {
            client.namespaces().deleteIfExists(namespace, DeleteOptions.builder()
                    .propagationPolicy(DeleteOptions.PropagationPolicy.Background).build());
        }
    }

    @Test
    public void sshBootstrapRedisReuseAndExplicitRefresh() {
        JsonObject secret = client.secrets("kube-system").get(SA + "-token");
        assertEquals("kubernetes.io/service-account-token", secret.get("type").getAsString());
        assertEquals(SA, secret.getAsJsonObject("metadata").getAsJsonObject("annotations")
                .get("kubernetes.io/service-account.name").getAsString());
        assertTrue(secret.getAsJsonObject("data").has("token"));
        try (JedisPool pool = new JedisPool(URI.create(redisUrl)); Jedis redis = pool.getResource()) {
            assertEquals("string", redis.type(TOKEN_KEY));
            assertEquals("string", redis.type(URL_KEY));
            assertEquals(-1, redis.ttl(TOKEN_KEY));
            assertEquals(client.getApiServer(), redis.get(URL_KEY));
            String originalToken = redis.get(TOKEN_KEY);
            assertTrue("应缓存真实 token", originalToken != null && !originalToken.isEmpty());
            // 错误 SSH 密码仍能连接 API，证明此路径直接复用 Redis，没有 SSH 回退。
            K8sApiClient cached = K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh(true), fetchOptions);
            assertFalse(cached.listNodes().isEmpty());
            try {
                redis.set(TOKEN_KEY, "expired-test-token");
                K8sApiClient expired = K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh(true), fetchOptions);
                assertEquals(401, assertThrows(K8sApiException.class, expired::listNamespaces).getStatusCode());
                assertEquals("expired-test-token", redis.get(TOKEN_KEY));
                K8sApiClient refreshed = K8sApiClient.builder().redisUrl(redisUrl).refreshCache(true)
                        .fromSsh(ssh(false), fetchOptions);
                assertFalse(refreshed.listNodes().isEmpty());
                // 不使用 assertEquals 输出 token，即使断言失败也不泄漏凭据。
                assertTrue("刷新应重新读取现有 SA 的 token", originalToken.equals(redis.get(TOKEN_KEY)));
            } finally {
                redis.set(TOKEN_KEY, originalToken);
            }
        }
        String deniedRedis = "redis://:wrong-password@127.0.0.1:16379/0";
        assertThrows(K8sToolsException.class,
                () -> K8sApiClient.builder().redisUrl(deniedRedis).fromSsh(ssh(false), fetchOptions));
    }

    @Test
    public void typedQueriesWorkloadControllerAndScale() throws Exception {
        K8sResourceClient deployments = client.deployments(namespace);
        deployments.create(json("{\"metadata\":{\"name\":\"workload\"},\"spec\":{\"replicas\":1,"
                + "\"selector\":{\"matchLabels\":{\"app\":\"e2e\"}},\"template\":{"
                + "\"metadata\":{\"labels\":{\"app\":\"e2e\"}},\"spec\":{\"terminationGracePeriodSeconds\":0,"
                + "\"containers\":[{\"name\":\"pause\",\"image\":\"registry.k8s.io/pause:3.10\","
                + "\"imagePullPolicy\":\"IfNotPresent\"}]}}}}"));
        await("Deployment 应由真实控制器创建 Ready Pod", () -> readyReplicas(deployments.get("workload")) >= 1);
        client.services(namespace).create(json("{\"metadata\":{\"name\":\"workload\"},\"spec\":{"
                + "\"selector\":{\"app\":\"e2e\"},\"ports\":[{\"port\":80,\"targetPort\":80}]}}"));
        assertTrue(client.getVersion().getGitVersion().startsWith("v1.37."));
        assertFalse(client.listNodes().isEmpty());
        assertTrue(client.listNamespaces().stream().anyMatch(n -> namespace.equals(n.getName())));
        assertFalse(client.listPods(namespace).isEmpty());
        assertFalse(client.listPods(null).isEmpty());
        assertEquals(1, client.listServices(namespace).size());
        assertFalse(client.listServices(null).isEmpty());
        assertEquals(1, client.listDeployments(namespace).size());
        assertFalse(client.listDeployments(null).isEmpty());
        String podName = client.listPods(namespace).get(0).getName();
        assertEquals("Running", client.pods(namespace).subresource(podName, "status").get()
                .getAsJsonObject("status").get("phase").getAsString());
        deployments.scale("workload", 2);
        await("扩容应产生第二个 Ready Pod", () -> readyReplicas(deployments.get("workload")) >= 2);
        assertEquals(2, deployments.subresource("workload", "scale").get()
                .getAsJsonObject("spec").get("replicas").getAsInt());
        assertEquals("2", kubectl("-n", namespace, "get", "deployment", "workload", "-o", "jsonpath={.spec.replicas}"));
        deployments.patch("workload", PatchType.STRATEGIC_MERGE_PATCH,
                json("{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"pause\","
                        + "\"env\":[{\"name\":\"E2E_MARK\",\"value\":\"checked\"}]}]}}}}"));
        JsonObject container = deployments.get("workload").getAsJsonObject("spec").getAsJsonObject("template")
                .getAsJsonObject("spec").getAsJsonArray("containers").get(0).getAsJsonObject();
        assertEquals("registry.k8s.io/pause:3.10", container.get("image").getAsString());
        assertEquals("checked", container.getAsJsonArray("env").get(0).getAsJsonObject().get("value").getAsString());
        assertTrue(JsonParser.parseString(client.getRaw("/version")).getAsJsonObject().has("gitVersion"));
        assertEquals(200, client.request("GET", "/api/v1/nodes", null, null, null).getStatusCode());
    }

    @Test
    public void configMapCrudPatchesAndConflictSemantics() throws Exception {
        K8sResourceClient maps = client.configMaps(namespace);
        JsonObject created = maps.create(json("{\"metadata\":{\"name\":\"settings\"},\"data\":{\"value\":\"one\"}}"));
        JsonObject stale = created.deepCopy();
        created.getAsJsonObject("data").addProperty("value", "two");
        maps.replace("settings", created);
        assertEquals("two", kubectl("-n", namespace, "get", "configmap", "settings", "-o", "jsonpath={.data.value}"));
        stale.getAsJsonObject("data").addProperty("value", "stale");
        assertEquals(409, assertThrows(K8sApiException.class, () -> maps.replace("settings", stale)).getStatusCode());
        assertEquals(409, assertThrows(K8sApiException.class, () -> maps.create(stale)).getStatusCode());
        maps.patch("settings", PatchType.MERGE_PATCH, json("{\"data\":{\"extra\":\"merged\"}}"));
        maps.patch("settings", PatchType.JSON_PATCH, JsonParser.parseString(
                "[{\"op\":\"replace\",\"path\":\"/data/value\",\"value\":\"patched\"}]"));
        assertEquals("merged", maps.get("settings").getAsJsonObject("data").get("extra").getAsString());
        assertEquals("patched", kubectl("-n", namespace, "get", "configmap", "settings", "-o", "jsonpath={.data.value}"));
        assertEquals(400, assertThrows(K8sApiException.class, () -> maps.create(json(
                "{\"metadata\":{\"name\":\"invalid\"},\"unknownTopLevel\":true}"),
                WriteOptions.builder().fieldValidation("Strict").build())).getStatusCode());
        assertFalse(maps.exists("invalid"));
        maps.delete("settings", DeleteOptions.builder().uid(created.getAsJsonObject("metadata").get("uid").getAsString()).build());
        await("ConfigMap 应删除", () -> !maps.exists("settings"));
        assertFalse(maps.deleteIfExists("settings", null));
        assertEquals(404, assertThrows(K8sApiException.class, () -> maps.get("settings")).getStatusCode());
    }

    @Test
    public void applyDryRunSelectorsPaginationAndCollectionDelete() {
        K8sResourceClient maps = client.configMaps(namespace);
        for (int i = 0; i < 5; i++) {
            maps.create(json("{\"metadata\":{\"name\":\"page-" + i + "\",\"labels\":{\"batch\":\"e2e\"}},"
                    + "\"data\":{\"index\":\"" + i + "\"}}"));
        }
        ListOptions selection = ListOptions.builder().labelSelector("batch=e2e").limit(2).build();
        K8sList<JsonObject> first = maps.list(selection);
        assertEquals(2, first.getItems().size());
        assertTrue(first.getMetadata().getContinueToken().length() > 0);
        assertEquals(2, maps.list(selection.withContinueToken(first.getMetadata().getContinueToken())).getItems().size());
        assertEquals(5, maps.listAll(selection).size());
        assertEquals(1, maps.list(ListOptions.builder().fieldSelector("metadata.name=page-0").build()).getItems().size());
        assertTrue(client.resource(K8sResources.CONFIG_MAPS).inAllNamespaces().listAll(selection).size() >= 5);
        JsonObject desired = json("{\"metadata\":{\"name\":\"applied\"},\"data\":{\"value\":\"one\"}}");
        maps.apply(desired, WriteOptions.builder().fieldManager("e2e").dryRun(true).build(), false);
        assertFalse(maps.exists("applied"));
        maps.apply(desired, "e2e", false);
        desired.getAsJsonObject("data").addProperty("value", "two");
        maps.apply(desired, "e2e", false);
        assertEquals("two", maps.get("applied").getAsJsonObject("data").get("value").getAsString());
        maps.delete("applied", DeleteOptions.builder().dryRun(true).build());
        assertTrue(maps.exists("applied"));
        maps.deleteCollection(ListOptions.builder().labelSelector("batch=e2e").build(), null);
        await("集合删除只影响匹配标签的资源", () -> maps.list(selection).getItems().isEmpty());
        assertTrue(maps.exists("applied"));
    }

    @Test
    public void discoveryCustomResourceSchemaCrudAndStatus() {
        String group = namespace + ".example.com";
        String crdName = "widgets." + group;
        K8sResourceClient definitions = client.resource(K8sResources.CUSTOM_RESOURCE_DEFINITIONS);
        try {
            definitions.create(json("{\"metadata\":{\"name\":\"" + crdName + "\"},\"spec\":{\"group\":\"" + group
                    + "\",\"scope\":\"Namespaced\",\"names\":{\"plural\":\"widgets\",\"singular\":\"widget\",\"kind\":\"Widget\"},"
                    + "\"versions\":[{\"name\":\"v1\",\"served\":true,\"storage\":true,\"subresources\":{\"status\":{}},"
                    + "\"schema\":{\"openAPIV3Schema\":{\"type\":\"object\",\"properties\":{"
                    + "\"spec\":{\"type\":\"object\",\"x-kubernetes-preserve-unknown-fields\":true,"
                    + "\"properties\":{\"size\":{\"type\":\"integer\"}}},"
                    + "\"status\":{\"type\":\"object\",\"x-kubernetes-preserve-unknown-fields\":true}}}}}]}}"));
            await("CRD 应由 apiserver 建立", () -> {
                JsonObject status = definitions.get(crdName).getAsJsonObject("status");
                if (status == null || !status.has("conditions")) { return false; }
                for (JsonElement item : status.getAsJsonArray("conditions")) {
                    JsonObject condition = item.getAsJsonObject();
                    if ("Established".equals(condition.get("type").getAsString())
                            && "True".equals(condition.get("status").getAsString())) { return true; }
                }
                return false;
            });
            assertTrue(client.discoverApiVersions().contains("v1"));
            assertTrue(client.discoverApiVersions().contains("apps/v1"));
            await("Discovery 应公布 CRD 版本", () -> client.discoverApiVersions().contains(group + "/v1"));
            assertTrue(client.discoverResources(group + "/v1").stream().anyMatch(r -> "widgets".equals(r.getName()) && r.isNamespaced()));
            K8sResourceClient widgets = client.resource(group + "/v1", "widgets").inNamespace(namespace);
            widgets.create(json("{\"metadata\":{\"name\":\"sample\"},\"spec\":{\"size\":1,\"extra\":\"preserved\"}}"));
            JsonObject widget = widgets.get("sample");
            widget.getAsJsonObject("spec").addProperty("size", 2);
            widgets.replace("sample", widget);
            widgets.patch("sample", PatchType.MERGE_PATCH, json("{\"spec\":{\"size\":3}}"));
            assertEquals("preserved", widgets.get("sample").getAsJsonObject("spec").get("extra").getAsString());
            assertEquals(3, widgets.list().getItems().get(0).getAsJsonObject("spec").get("size").getAsInt());
            widgets.subresource("sample", "status").patch(PatchType.MERGE_PATCH, json("{\"status\":{\"phase\":\"Ready\"}}"), null);
            assertEquals("Ready", widgets.subresource("sample", "status").get().getAsJsonObject("status").get("phase").getAsString());
            assertEquals(422, assertThrows(K8sApiException.class, () -> widgets.patch("sample", PatchType.MERGE_PATCH,
                    json("{\"spec\":{\"size\":\"not-an-integer\"}}"))).getStatusCode());
            widgets.delete("sample");
            await("自定义资源应删除", () -> !widgets.exists("sample"));
        } finally {
            definitions.deleteIfExists(crdName, null);
        }
    }

    @Test
    public void realTlsAuthenticationAndRbac() {
        K8sApiClient strict = K8sApiClient.builder().redisUrl(redisUrl)
                .insecureSkipTlsVerify(false).tlsAutoFallback(false).fromSsh(ssh(false), fetchOptions);
        assertFalse(strict.listNodes().isEmpty());
        assertFalse(strict.isDegradedToInsecure());
        try (JedisPool pool = new JedisPool(URI.create(redisUrl)); Jedis redis = pool.getResource()) {
            K8sApiClient untrusted = K8sApiClient.builder().apiServer(client.getApiServer())
                    .token(redis.get(TOKEN_KEY)).insecureSkipTlsVerify(false).tlsAutoFallback(false).build();
            assertEquals(-1, assertThrows(K8sApiException.class, untrusted::listNodes).getStatusCode());
        }
        K8sApiClient invalid = K8sApiClient.builder().apiServer(client.getApiServer()).token("invalid-test-token")
                .insecureSkipTlsVerify(true).build();
        assertEquals(401, assertThrows(K8sApiException.class, invalid::listNodes).getStatusCode());
        K8sResourceClient accounts = client.resource(K8sResources.SERVICE_ACCOUNTS).inNamespace(namespace);
        accounts.create(json("{\"metadata\":{\"name\":\"restricted\"}}"));
        JsonObject request = accounts.subresource("restricted", "token").create(json(
                "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"TokenRequest\","
                        + "\"spec\":{\"audiences\":[],\"expirationSeconds\":600}}"), null);
        K8sApiClient restricted = K8sApiClient.builder().apiServer(client.getApiServer())
                .token(request.getAsJsonObject("status").get("token").getAsString()).insecureSkipTlsVerify(true).build();
        assertEquals(403, assertThrows(K8sApiException.class, restricted::listNodes).getStatusCode());
        JsonObject review = restricted.resource(ResourceDefinition.cluster("authorization.k8s.io/v1",
                "selfsubjectaccessreviews", "SelfSubjectAccessReview")).create(json(
                "{\"spec\":{\"resourceAttributes\":{\"namespace\":\"" + namespace + "\",\"verb\":\"list\",\"resource\":\"pods\"}}}"));
        assertFalse(review.getAsJsonObject("status").get("allowed").getAsBoolean());
    }

    @Test
    public void secretAndServiceCrud() {
        K8sResourceClient secrets = client.secrets(namespace);
        secrets.create(json("{\"metadata\":{\"name\":\"sample\"},\"type\":\"Opaque\",\"stringData\":{\"value\":\"fixture\"}}"));
        JsonObject secret = secrets.get("sample");
        assertEquals("Zml4dHVyZQ==", secret.getAsJsonObject("data").get("value").getAsString());
        secret.getAsJsonObject("data").addProperty("value", "dXBkYXRlZA==");
        secrets.replace("sample", secret);
        assertEquals("dXBkYXRlZA==", secrets.get("sample").getAsJsonObject("data").get("value").getAsString());
        secrets.delete("sample");
        await("Secret 应删除", () -> !secrets.exists("sample"));
        K8sResourceClient services = client.services(namespace);
        services.create(json("{\"metadata\":{\"name\":\"sample\"},\"spec\":{\"ports\":[{\"port\":80,\"targetPort\":8080}]}}"));
        JsonObject service = services.get("sample");
        String clusterIp = service.getAsJsonObject("spec").get("clusterIP").getAsString();
        service.getAsJsonObject("spec").getAsJsonArray("ports").get(0).getAsJsonObject().addProperty("targetPort", 9090);
        services.replace("sample", service);
        assertEquals(clusterIp, services.get("sample").getAsJsonObject("spec").get("clusterIP").getAsString());
        assertEquals(9090, services.get("sample").getAsJsonObject("spec").getAsJsonArray("ports")
                .get(0).getAsJsonObject().get("targetPort").getAsInt());
        services.delete("sample");
        await("Service 应删除", () -> !services.exists("sample"));
    }

    private static SshConfig ssh(boolean wrongPassword) {
        return SshConfig.builder().host("127.0.0.1").port(22222).username("k8se2e")
                .password(wrongPassword ? "wrong-password" : required("E2E_SSH_PASSWORD"))
                .passwordOnly(true).connectTimeoutMs(5000).build();
    }

    private static int readyReplicas(JsonObject deployment) {
        JsonObject status = deployment.getAsJsonObject("status");
        return status == null || !status.has("readyReplicas") ? 0 : status.get("readyReplicas").getAsInt();
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) { throw new IllegalStateException("缺少 E2E 环境变量: " + name); }
        return value;
    }

    private static void await(String description, BooleanSupplier ready) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) { return; }
            try { Thread.sleep(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(description, e); }
        }
        fail("等待超时: " + description);
    }

    private static String kubectl(String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("kubectl");
        command.add("--kubeconfig=" + required("KUBECONFIG"));
        command.add("--request-timeout=15s");
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) { throw new AssertionError("kubectl 执行超时"); }
            try (InputStream input = process.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) { out.write(buffer, 0, read); }
                assertEquals("kubectl 应成功执行", 0, process.exitValue());
                return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
            }
        } finally {
            process.destroy();
        }
    }
}

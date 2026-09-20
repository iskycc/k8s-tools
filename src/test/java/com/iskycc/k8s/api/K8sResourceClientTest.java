package com.iskycc.k8s.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iskycc.k8s.K8sToolsException;
import com.iskycc.k8s.api.model.K8sList;
import com.iskycc.k8s.mock.CertUtil;
import com.iskycc.k8s.mock.MockK8sApiServer;
import com.iskycc.k8s.mock.MockK8sApiServer.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** 使用实际 HTTPS 请求验证 Kubernetes REST 协议、作用域、正文及异常语义。 */
public class K8sResourceClientTest {
    private MockK8sApiServer server;
    private K8sApiClient client;

    @Before
    public void start() throws Exception {
        server = new MockK8sApiServer("test-token");
        client = K8sApiClient.builder().apiServer(server.getBaseUrl()).token("test-token")
                .caCertPem(server.getCaCertPem()).tlsAutoFallback(false).build();
    }

    @After
    public void stop() { if (server != null) { server.close(); } }

    @Test
    public void configMapCrudPreservesUnknownFieldsAndResourceVersion() {
        K8sResourceClient maps = client.configMaps("demo");
        JsonObject input = json("{\"metadata\":{\"name\":\"settings\"},\"data\":{\"中文\":\"值\"},\"futureField\":{\"enabled\":true}}");
        String original = input.toString();
        JsonObject stored = input.deepCopy();
        stored.addProperty("apiVersion", "v1");
        stored.addProperty("kind", "ConfigMap");
        stored.getAsJsonObject("metadata").addProperty("namespace", "demo");
        stored.getAsJsonObject("metadata").addProperty("resourceVersion", "11");
        server.enqueueResponse(201, stored.toString());
        assertEquals("11", maps.create(input).getAsJsonObject("metadata").get("resourceVersion").getAsString());
        assertEquals(original, input.toString());

        server.enqueueResponse(200, stored.toString());
        JsonObject read = maps.get("settings");
        read.getAsJsonObject("data").addProperty("中文", "新值");
        JsonObject updated = read.deepCopy();
        updated.getAsJsonObject("metadata").addProperty("resourceVersion", "12");
        server.enqueueResponse(200, updated.toString());
        maps.replace("settings", read);
        server.enqueueResponse(200, updated.toString());
        maps.patch("settings", PatchType.MERGE_PATCH, json("{\"data\":{\"obsolete\":null}}"));
        server.enqueueResponse(202, "{\"kind\":\"Status\",\"status\":\"Success\"}");
        assertEquals("Status", maps.delete("settings").get("kind").getAsString());
        server.enqueueResponse(404, status("NotFound"));
        assertFalse(maps.exists("settings"));

        List<RecordedRequest> requests = server.getRequests();
        assertEquals(6, requests.size());
        assertRequest(requests.get(0), "POST", "/api/v1/namespaces/demo/configmaps", "application/json");
        JsonObject creation = json(requests.get(0).body);
        assertEquals("ConfigMap", creation.get("kind").getAsString());
        assertEquals("demo", creation.getAsJsonObject("metadata").get("namespace").getAsString());
        assertRequest(requests.get(1), "GET", "/api/v1/namespaces/demo/configmaps/settings", null);
        assertRequest(requests.get(2), "PUT", "/api/v1/namespaces/demo/configmaps/settings", "application/json");
        JsonObject replacement = json(requests.get(2).body);
        assertTrue(replacement.getAsJsonObject("futureField").get("enabled").getAsBoolean());
        assertEquals("11", replacement.getAsJsonObject("metadata").get("resourceVersion").getAsString());
        assertEquals("新值", replacement.getAsJsonObject("data").get("中文").getAsString());
        assertRequest(requests.get(3), "PATCH", "/api/v1/namespaces/demo/configmaps/settings", "application/merge-patch+json");
        assertTrue(json(requests.get(3).body).getAsJsonObject("data").get("obsolete").isJsonNull());
        assertRequest(requests.get(4), "DELETE", "/api/v1/namespaces/demo/configmaps/settings", "application/json");
    }

    @Test
    public void clusterResourceAndGeneratedNameUseCorrectCollections() {
        server.enqueueResponse(201, "{\"kind\":\"Namespace\",\"metadata\":{\"name\":\"generated-abc\"}}");
        client.namespaces().create(json("{\"metadata\":{\"generateName\":\"generated-\"}}"));
        assertRequest(last(), "POST", "/api/v1/namespaces", "application/json");
        server.enqueueResponse(200, "{\"kind\":\"PersistentVolume\",\"metadata\":{\"name\":\"pv-one\"}}");
        client.resource(K8sResources.PERSISTENT_VOLUMES).get("pv-one");
        assertRequest(last(), "GET", "/api/v1/persistentvolumes/pv-one", null);
        server.enqueueResponse(200, "{\"kind\":\"ClusterRole\"}");
        client.resource(K8sResources.CLUSTER_ROLES).get("system:discovery");
        assertRequest(last(), "GET", "/apis/rbac.authorization.k8s.io/v1/clusterroles/system:discovery", null);
    }

    @Test
    public void selectorsAndPaginationPreserveOpaqueContinueTokens() {
        server.enqueueResponse(200, "{\"kind\":\"PodList\",\"metadata\":{\"resourceVersion\":\"42\",\"continue\":\"next+/=?\",\"remainingItemCount\":1},\"items\":[{\"metadata\":{\"name\":\"one\"}}]}");
        server.enqueueResponse(200, "{\"metadata\":{\"resourceVersion\":\"42\",\"continue\":\"\"},\"items\":[{\"metadata\":{\"name\":\"two\"}}]}");
        String selector = "app in (api,web),team=platform";
        ListOptions options = ListOptions.builder().labelSelector(selector)
                .fieldSelector("status.phase=Running").limit(1).resourceVersion("42")
                .resourceVersionMatch("Exact").timeoutSeconds(10).build();
        List<JsonObject> items = client.resource(K8sResources.PODS).inAllNamespaces().listAll(options);
        assertEquals(2, items.size());
        assertEquals("two", items.get(1).getAsJsonObject("metadata").get("name").getAsString());
        assertRequest(last(), "GET", "/api/v1/pods", null);
        assertEquals(selector, last().query.get("labelSelector"));
        assertEquals("next+/=?", last().query.get("continue"));
        assertEquals("1", last().query.get("limit"));
        assertEquals("42", last().query.get("resourceVersion"));
        assertFalse(options.toQueryParameters().containsKey("continue"));
    }

    @Test
    public void singlePageExposesMetadataAndDoesNotFetchNextPage() {
        server.enqueueResponse(200, "{\"metadata\":{\"resourceVersion\":\"31\",\"continue\":\"page2\",\"remainingItemCount\":9},\"items\":[]}");
        K8sList<JsonObject> page = client.pods("demo").list(ListOptions.builder().limit(5).build());
        assertEquals("31", page.getMetadata().getResourceVersion());
        assertEquals("page2", page.getMetadata().getContinueToken());
        assertEquals(Long.valueOf(9), page.getMetadata().getRemainingItemCount());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void paginationFailsOnExpiredOrRepeatedToken() {
        server.enqueueResponse(200, "{\"metadata\":{\"continue\":\"next\"},\"items\":[]}");
        server.enqueueResponse(410, status("ResourceExpired"));
        K8sApiException expired = assertThrows(K8sApiException.class, () -> client.pods("demo").listAll());
        assertEquals(410, expired.getStatusCode());
        assertEquals(2, server.getRequestCount());
        server.enqueueResponse(200, "{\"metadata\":{\"continue\":\"again\"},\"items\":[]}");
        server.enqueueResponse(200, "{\"metadata\":{\"continue\":\"again\"},\"items\":[]}");
        assertThrows(K8sToolsException.class, () -> client.pods("demo").listAll());
        assertEquals(4, server.getRequestCount());
    }

    @Test
    public void paginationRejectsMixedSnapshots() {
        server.enqueueResponse(200, "{\"metadata\":{\"resourceVersion\":\"1\",\"continue\":\"next\"},\"items\":[]}");
        server.enqueueResponse(200, "{\"metadata\":{\"resourceVersion\":\"2\"},\"items\":[]}");
        assertThrows(K8sToolsException.class, () -> client.pods("demo").listAll());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void jsonAndStrategicPatchesUseRealPatchWithCorrectMediaTypes() {
        server.enqueueResponse(200, "{}");
        JsonArray patch = JsonParser.parseString("[{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":\"12\"},{\"op\":\"replace\",\"path\":\"/spec/replicas\",\"value\":2}]").getAsJsonArray();
        client.deployments("demo").patch("web", PatchType.JSON_PATCH, patch);
        assertRequest(last(), "PATCH", "/apis/apps/v1/namespaces/demo/deployments/web", "application/json-patch+json");
        assertEquals(patch.toString(), last().body);
        server.enqueueResponse(200, "{}");
        client.deployments("demo").patch("web", PatchType.STRATEGIC_MERGE_PATCH,
                json("{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"web\",\"image\":\"nginx:1.28\"}]}}}}"));
        assertTrue(last().contentType.startsWith("application/strategic-merge-patch+json"));
    }

    @Test
    public void applyAndDryRunUseExplicitManagerAndForce() {
        server.enqueueResponse(201, "{\"kind\":\"ConfigMap\"}");
        JsonObject body = json("{\"metadata\":{\"name\":\"settings\"},\"data\":{\"feature\":\"on\"}}");
        client.configMaps("demo").apply(body, WriteOptions.builder().fieldManager("test-operator")
                .fieldValidation("Strict").dryRun(true).build(), false);
        assertRequest(last(), "PATCH", "/api/v1/namespaces/demo/configmaps/settings", "application/apply-patch+yaml");
        assertEquals("test-operator", last().query.get("fieldManager"));
        assertEquals("false", last().query.get("force"));
        assertEquals("All", last().query.get("dryRun"));
        assertEquals("Strict", last().query.get("fieldValidation"));
        assertEquals("v1", json(last().body).get("apiVersion").getAsString());
        assertFalse(body.has("apiVersion"));
        server.enqueueResponse(200, "{}");
        client.configMaps("demo").apply(body, "test-operator", true);
        assertEquals("true", last().query.get("force"));
        assertThrows(IllegalArgumentException.class, () -> client.configMaps("demo").apply(body, WriteOptions.builder().build(), false));
    }

    @Test
    public void deletionSupportsPreconditionsAndEmptyResponses() {
        DeleteOptions options = DeleteOptions.builder().gracePeriodSeconds(0)
                .propagationPolicy(DeleteOptions.PropagationPolicy.Foreground)
                .uid("uid-123").resourceVersion("17").dryRun(true).build();
        server.enqueueResponse(204, "");
        assertNull(client.pods("demo").delete("web", options));
        JsonObject body = json(last().body);
        assertEquals("DeleteOptions", body.get("kind").getAsString());
        assertEquals(0, body.get("gracePeriodSeconds").getAsInt());
        assertEquals("Foreground", body.get("propagationPolicy").getAsString());
        assertEquals("uid-123", body.getAsJsonObject("preconditions").get("uid").getAsString());
        assertEquals("17", body.getAsJsonObject("preconditions").get("resourceVersion").getAsString());
        assertEquals("All", body.getAsJsonArray("dryRun").get(0).getAsString());
        options.toJson().remove("preconditions");
        assertTrue(options.toJson().has("preconditions"));
        server.enqueueResponse(404, status("NotFound"));
        assertFalse(client.pods("demo").deleteIfExists("missing", null));
    }

    @Test
    public void deleteCollectionRetainsSelectionAndScope() {
        server.enqueueResponse(200, "{\"kind\":\"Status\",\"status\":\"Success\"}");
        client.resource(K8sResources.JOBS).inNamespace("batch").deleteCollection(
                ListOptions.builder().labelSelector("job-group=finished").build(),
                DeleteOptions.builder().propagationPolicy(DeleteOptions.PropagationPolicy.Background).build());
        assertRequest(last(), "DELETE", "/apis/batch/v1/namespaces/batch/jobs", "application/json");
        assertEquals("job-group=finished", last().query.get("labelSelector"));
        assertThrows(IllegalArgumentException.class, () -> client.resource(K8sResources.JOBS).deleteCollection(null, null));
    }

    @Test
    public void discoveryResolvesCrdAndRejectsUnsupportedActions() {
        server.enqueueResponse(200, "{\"groupVersion\":\"sample.example/v1\",\"resources\":[{\"name\":\"widgets\",\"kind\":\"Widget\",\"namespaced\":true,\"verbs\":[\"get\",\"list\",\"create\",\"patch\"]},{\"name\":\"widgets/status\",\"kind\":\"Widget\",\"namespaced\":true,\"verbs\":[\"get\",\"patch\"]}]}");
        K8sResourceClient widgets = client.resource("sample.example/v1", "widgets").inNamespace("demo");
        assertRequest(last(), "GET", "/apis/sample.example/v1", null);
        assertTrue(widgets.getDefinition().hasDiscoveredVerbs());
        assertEquals("Widget", widgets.getDefinition().getKind());
        server.enqueueResponse(201, "{\"kind\":\"Widget\",\"spec\":{\"futureConfig\":[1,2]}}");
        JsonObject created = widgets.create(json("{\"metadata\":{\"name\":\"w1\"},\"spec\":{\"futureConfig\":[1,2]}}"));
        assertEquals(2, created.getAsJsonObject("spec").getAsJsonArray("futureConfig").size());
        assertRequest(last(), "POST", "/apis/sample.example/v1/namespaces/demo/widgets", "application/json");
        assertEquals("sample.example/v1", json(last().body).get("apiVersion").getAsString());
        assertThrows(UnsupportedOperationException.class, () -> widgets.delete("w1"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void discoveryAcceptsHyphenatedCustomResourceNamesAndVersions() {
        server.enqueueResponse(200, "{\"resources\":[{\"name\":\"sample-widgets\",\"kind\":\"Sample-Widget\",\"namespaced\":true,\"verbs\":[\"create\"]}]}");
        K8sResourceClient widgets = client.resource("sample.example/v1-alpha", "sample-widgets").inNamespace("demo");
        assertRequest(last(), "GET", "/apis/sample.example/v1-alpha", null);
        server.enqueueResponse(201, "{}");
        widgets.create(json("{\"metadata\":{\"name\":\"w1\"}}"));
        assertRequest(last(), "POST", "/apis/sample.example/v1-alpha/namespaces/demo/sample-widgets", "application/json");
        assertEquals("Sample-Widget", json(last().body).get("kind").getAsString());
        assertEquals("sample.example/v1-alpha", json(last().body).get("apiVersion").getAsString());
        assertThrows(IllegalArgumentException.class, () -> ResourceDefinition.cluster("sample.example/../v1", "widgets", "Widget"));
        assertThrows(IllegalArgumentException.class, () -> ResourceDefinition.cluster("v1", "pods/secrets", "Pod"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void createOnlyReviewResourcesDoNotRequireMetadataName() {
        server.enqueueResponse(200, "{\"resources\":[{\"name\":\"selfsubjectaccessreviews\",\"kind\":\"SelfSubjectAccessReview\",\"namespaced\":false,\"verbs\":[\"create\"]}]}");
        K8sResourceClient reviews = client.resource("authorization.k8s.io/v1", "selfsubjectaccessreviews");
        server.enqueueResponse(201, "{\"kind\":\"SelfSubjectAccessReview\",\"status\":{\"allowed\":true}}");
        JsonObject response = reviews.create(json("{\"spec\":{\"resourceAttributes\":{\"namespace\":\"demo\",\"verb\":\"get\",\"resource\":\"pods\"}}}"));
        assertTrue(response.getAsJsonObject("status").get("allowed").getAsBoolean());
        assertRequest(last(), "POST", "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews", "application/json");
        assertFalse(json(last().body).getAsJsonObject("metadata").has("name"));
        assertThrows(UnsupportedOperationException.class, () -> reviews.get("unused"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void discoveryListsVersionsAndSubresourceCapabilities() {
        server.enqueueResponse(200, "{\"versions\":[\"v1\"]}");
        server.enqueueResponse(200, "{\"groups\":[{\"versions\":[{\"groupVersion\":\"apps/v1\"},{\"groupVersion\":\"apps/v1beta1\"}]}]}");
        assertEquals(java.util.Arrays.asList("v1", "apps/v1", "apps/v1beta1"), client.discoverApiVersions());
        server.enqueueResponse(200, "{\"resources\":[{\"name\":\"deployments/scale\",\"kind\":\"Scale\",\"namespaced\":true,\"verbs\":[\"get\",\"patch\"]}]}");
        ApiResource scale = client.discoverResources("apps/v1").get(0);
        assertTrue(scale.isSubresource());
        assertTrue(scale.getVerbs().contains("patch"));
        assertThrows(IllegalArgumentException.class, () -> scale.toDefinition("apps/v1"));
    }

    @Test
    public void scaleStatusAndTokenSubresourcesKeepTheirOwnKinds() {
        server.enqueueResponse(200, "{\"apiVersion\":\"autoscaling/v1\",\"kind\":\"Scale\",\"spec\":{\"replicas\":3}}");
        assertEquals(3, client.deployments("demo").scale("web", 3).getAsJsonObject("spec").get("replicas").getAsInt());
        assertRequest(last(), "PATCH", "/apis/apps/v1/namespaces/demo/deployments/web/scale", "application/merge-patch+json");
        assertEquals(3, json(last().body).getAsJsonObject("spec").get("replicas").getAsInt());
        ResourceDefinition custom = ResourceDefinition.cluster("sample.example/v1", "widgets", "Widget");
        server.enqueueResponse(200, "{}");
        client.resource(custom).subresource("w1", "status").replace(json("{\"metadata\":{\"resourceVersion\":\"4\"},\"status\":{\"ready\":true}}"), null);
        assertRequest(last(), "PUT", "/apis/sample.example/v1/widgets/w1/status", "application/json");
        server.enqueueResponse(201, "{\"kind\":\"TokenRequest\",\"status\":{\"token\":\"test-token-response\"}}");
        client.resource(K8sResources.SERVICE_ACCOUNTS).inNamespace("demo").subresource("reader", "token")
                .create(json("{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"TokenRequest\",\"spec\":{\"audiences\":[\"api\"]}}"), null);
        assertRequest(last(), "POST", "/api/v1/namespaces/demo/serviceaccounts/reader/token", "application/json");
        assertEquals("TokenRequest", json(last().body).get("kind").getAsString());
    }

    @Test
    public void scopeAndIdentityErrorsFailBeforeAnyRequest() {
        JsonObject named = json("{\"metadata\":{\"name\":\"web\",\"resourceVersion\":\"3\"}}");
        assertThrows(IllegalArgumentException.class, () -> client.resource(K8sResources.PODS).get("web"));
        assertThrows(IllegalArgumentException.class, () -> client.resource(K8sResources.PODS).create(named));
        assertThrows(IllegalArgumentException.class, () -> client.nodes().inNamespace("demo"));
        assertThrows(IllegalArgumentException.class, () -> client.nodes().create(json("{\"metadata\":{\"name\":\"n\",\"namespace\":\"demo\"}}")));
        assertThrows(IllegalArgumentException.class, () -> client.pods("demo").replace("different", named));
        assertThrows(IllegalArgumentException.class, () -> client.pods("demo").create(json("{\"kind\":\"Secret\",\"metadata\":{\"name\":\"web\"}}")));
        assertThrows(IllegalArgumentException.class, () -> client.pods("demo").create(json("{\"metadata\":{\"name\":\"web\",\"namespace\":\"other\"}}")));
        assertThrows(IllegalArgumentException.class, () -> client.pods("demo").replace("web", json("{\"metadata\":{\"name\":\"web\"}}")));
        assertThrows(IllegalArgumentException.class, () -> client.pods("demo").patch("web", PatchType.JSON_PATCH, new JsonObject()));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void pathsRejectTraversalButAllIsAValidExplicitNamespace() {
        for (String value : new String[]{"../secrets", "%2e%2e", ".", "..", "pod?watch=true", "pod#fragment", "pod/name", "pod name", "pod\nname"}) {
            assertThrows(IllegalArgumentException.class, () -> client.pods("demo").get(value));
        }
        assertThrows(IllegalArgumentException.class, () -> client.pods("../other"));
        assertThrows(IllegalArgumentException.class, () -> client.getRaw("//other.example/api"));
        assertEquals(0, server.getRequestCount());
        server.enqueueResponse(200, "{\"items\":[]}");
        client.pods("all").list();
        assertEquals("/api/v1/namespaces/all/pods", last().path);
        client.listPods("all");
        assertEquals("/api/v1/pods", last().path);
    }

    @Test
    public void conflictForbiddenAndRateLimitAreNotRetriedOrSwallowed() {
        server.enqueueResponse(409, "{\"kind\":\"Status\",\"reason\":\"Conflict\",\"message\":\"resourceVersion is stale\"}");
        K8sApiException conflict = assertThrows(K8sApiException.class, () -> client.pods("demo").replace("web",
                json("{\"metadata\":{\"name\":\"web\",\"resourceVersion\":\"old\"}}")));
        assertEquals(409, conflict.getStatusCode());
        assertEquals("Conflict", conflict.getReason());
        assertEquals("resourceVersion is stale", conflict.getStatusMessage());
        server.enqueueResponse(403, status("Forbidden"));
        assertEquals(403, assertThrows(K8sApiException.class, () -> client.pods("demo").exists("web")).getStatusCode());
        server.enqueueResponse(403, status("Forbidden"));
        assertThrows(K8sApiException.class, () -> client.pods("demo").deleteIfExists("web", null));
        server.enqueueResponse(429, status("TooManyRequests"), Collections.singletonMap("Retry-After", "3"));
        K8sApiException busy = assertThrows(K8sApiException.class, () -> client.pods("demo").get("web"));
        assertEquals("3", busy.getResponseHeaders().get("retry-after").get(0));
        assertEquals(4, server.getRequestCount());
    }

    @Test
    public void redirectsDoNotForwardCredentialsToAnotherServer() throws Exception {
        try (MockK8sApiServer other = new MockK8sApiServer("test-token")) {
            server.enqueueResponse(302, "", Collections.singletonMap("Location", other.getBaseUrl() + "/version"));
            assertEquals(302, assertThrows(K8sApiException.class, () -> client.getVersion()).getStatusCode());
            assertEquals(0, other.getRequestCount());
        }
    }

    @Test
    public void networkFailureDoesNotReplayPost() {
        server.enqueueDisconnect();
        K8sApiException error = assertThrows(K8sApiException.class,
                () -> client.configMaps("demo").create(json("{\"metadata\":{\"name\":\"once\"}}")));
        assertEquals(-1, error.getStatusCode());
        assertNotNull(error.getCause());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void untrustedTlsDoesNotDowngradeOrReplayWrites() {
        K8sApiClient untrusted = K8sApiClient.builder().apiServer(server.getBaseUrl()).token("test-token").build();
        assertEquals(-1, assertThrows(K8sApiException.class,
                () -> untrusted.configMaps("demo").create(json("{\"metadata\":{\"name\":\"once\"}}"))).getStatusCode());
        assertFalse(untrusted.isDegradedToInsecure());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void explicitInsecureAndCaBundlesWorkForWrites() throws Exception {
        server.enqueueResponse(201, "{}");
        K8sApiClient insecure = K8sApiClient.builder().apiServer(server.getBaseUrl()).token("test-token")
                .insecureSkipTlsVerify(true).build();
        insecure.configMaps("demo").create(json("{\"metadata\":{\"name\":\"one\"}}"));
        String extraCa = CertUtil.generateSelfSigned("other", new String[]{"localhost"}, new String[]{"127.0.0.1"}).getPem();
        K8sApiClient bundle = K8sApiClient.builder().apiServer(server.getBaseUrl()).token("test-token")
                .caCertPem(extraCa + "\n" + server.getCaCertPem()).tlsAutoFallback(false).build();
        server.enqueueResponse(201, "{}");
        bundle.configMaps("demo").create(json("{\"metadata\":{\"name\":\"two\"}}"));
        assertEquals(2, server.getRequestCount());
        assertFalse(bundle.isDegradedToInsecure());
    }

    @Test
    public void rawResponsesKeepHeadersAndDoNotLeakErrorBodiesInMessages() {
        server.enqueueResponse(202, "{\"kind\":\"Status\"}", Collections.singletonMap("Warning", "299 api \"deprecated\""));
        ApiResponse result = client.request("POST", "/apis/custom.example/v1/actions",
                Collections.singletonMap("value", "a+b/c?中文"), "{}", "application/json");
        assertEquals(202, result.getStatusCode());
        assertEquals("299 api \"deprecated\"", result.getHeader("WARNING").get(0));
        assertEquals("a+b/c?中文", last().query.get("value"));
        server.enqueueResponse(422, "{\"reason\":\"Invalid\",\"message\":\"secret-value\"}");
        K8sApiException error = assertThrows(K8sApiException.class, () -> client.getRaw("/invalid"));
        assertFalse(error.getMessage().contains("secret-value"));
        assertTrue(error.getResponseBody().contains("secret-value"));
        assertEquals("Invalid", error.getReason());
    }

    @Test
    public void malformedSuccessResponsesDoNotBecomeEmptyResources() {
        server.enqueueResponse(200, "not json");
        assertThrows(K8sToolsException.class, () -> client.pods("demo").get("web"));
        server.enqueueResponse(200, "{}");
        assertThrows(K8sToolsException.class, () -> client.pods("demo").list());
        server.enqueueResponse(200, "{\"items\":[null]}");
        assertThrows(K8sToolsException.class, () -> client.pods("demo").list());
    }

    private RecordedRequest last() {
        List<RecordedRequest> requests = server.getRequests();
        return requests.get(requests.size() - 1);
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static String status(String reason) { return "{\"kind\":\"Status\",\"reason\":\"" + reason + "\"}"; }
    private static void assertRequest(RecordedRequest request, String method, String path, String contentType) {
        assertEquals(method, request.method);
        assertEquals(path, request.path);
        if (contentType != null) { assertTrue(request.contentType, request.contentType.startsWith(contentType)); }
    }
}

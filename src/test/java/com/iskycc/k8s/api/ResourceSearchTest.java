package com.iskycc.k8s.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iskycc.k8s.K8sLogging;
import com.iskycc.k8s.K8sToolsException;
import com.iskycc.k8s.api.model.PodSummary;
import com.iskycc.k8s.api.model.PodDetails;
import com.iskycc.k8s.api.model.ServiceDetails;
import com.iskycc.k8s.api.model.ResourceDetails;
import com.iskycc.k8s.api.model.ResourceSummary;
import com.iskycc.k8s.mock.MockK8sApiServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ResourceSearchTest {
    private MockK8sApiServer server;
    private K8sApiClient client;

    @Before public void start() throws Exception {
        server = new MockK8sApiServer("search-token");
        client = K8sApiClient.builder().apiServer(server.getBaseUrl()).token("search-token")
                .caCertPem(server.getCaCertPem()).tlsAutoFallback(false).build();
    }
    @After public void close() { server.close(); }

    @Test public void podsReturnAllMatchesAcrossPagesNamespacesAndStatuses() {
        JsonObject one = resource("Pod", "production", "web");
        one.add("spec", json("{\"containers\":[{\"name\":\"app\",\"image\":\"private-image\"},{\"name\":\"sidecar\"}],"
                + "\"initContainers\":[{\"name\":\"init\"}],\"ephemeralContainers\":[{\"name\":\"debugger\"}]}"));
        one.add("status", json("{\"phase\":\"Pending\"}"));
        JsonObject two = resource("Pod", "staging", "web-canary");
        two.getAsJsonObject("metadata").addProperty("deletionTimestamp", "2026-09-21T00:00:00Z");
        page("42", "next+/=?", one, resource("Pod", "production", "other"));
        page("42", "", two, resource("Pod", "staging", "web"));
        List<PodSummary> results = client.searchPods("web");
        assertEquals(3, results.size()); // 完整名称也不能隐藏部分匹配或跨 namespace 同名。
        assertEquals("production", results.get(0).getNamespace());
        assertEquals("web", results.get(0).getPodName());
        assertEquals(Arrays.asList("app", "sidecar"), results.get(0).getContainerNames());
        assertTrue(results.get(1).getContainerNames().isEmpty());
        assertEquals("staging", results.get(2).getNamespace());
        assertThrows(UnsupportedOperationException.class, () -> results.get(0).getContainerNames().add("new"));
        JsonObject simple = new Gson().toJsonTree(results.get(0)).getAsJsonObject();
        assertEquals(new HashSet<String>(Arrays.asList("namespace", "name", "containerNames")), simple.keySet());
        for (MockK8sApiServer.RecordedRequest request : server.getRequests()) {
            assertEquals("GET", request.method); assertEquals("/api/v1/pods", request.path);
            assertEquals("100", request.query.get("limit")); assertFalse(request.query.containsKey("fieldSelector"));
        }
        assertEquals("next+/=?", server.getRequests().get(1).query.get("continue"));
        assertEquals("Bearer search-token", server.getLastAuthorization());
    }

    @Test public void detailedResultsRetainCommonAndUnknownFieldsWithoutSharingMutableState() {
        JsonObject input = resource("ConfigMap", "team", "app-config");
        input.getAsJsonObject("metadata").addProperty("uid", "id-1");
        input.getAsJsonObject("metadata").addProperty("resourceVersion", "123");
        input.getAsJsonObject("metadata").addProperty("creationTimestamp", "2026-09-21T00:00:00Z");
        input.getAsJsonObject("metadata").add("labels", json("{\"app\":\"demo\"}"));
        input.getAsJsonObject("metadata").add("annotations", json("{\"owner\":\"team\"}"));
        input.add("data", json("{\"config\":\"private-content\"}"));
        input.add("binaryData", json("{\"file\":\"YWJj\"}"));
        input.addProperty("immutable", true);
        input.add("futureField", json("{\"enabled\":true}"));
        page("1", "", input);
        ResourceDetails item = client.searchConfigMapsDetailed("config").get(0);
        assertEquals("team", item.getNamespace()); assertEquals("app-config", item.getName());
        assertEquals("v1", item.getApiVersion()); assertEquals("ConfigMap", item.getKind());
        assertEquals("id-1", item.getUid()); assertEquals("123", item.getResourceVersion());
        assertEquals("2026-09-21T00:00:00Z", item.getCreationTimestamp()); assertNull(item.getDeletionTimestamp());
        assertEquals("demo", item.getLabels().get("app")); assertEquals("team", item.getAnnotations().get("owner"));
        assertEquals("private-content", item.getData().get("config").getAsString());
        assertEquals("YWJj", item.getBinaryData().get("file").getAsString());
        assertTrue(item.toJson().getAsJsonObject("futureField").get("enabled").getAsBoolean());
        assertTrue(item.toJson().get("immutable").getAsBoolean());
        item.getData().addProperty("config", "changed"); item.getMetadata().addProperty("name", "changed");
        item.toJson().remove("futureField");
        assertEquals("private-content", item.getData().get("config").getAsString());
        assertEquals("app-config", item.getName()); assertTrue(item.toJson().has("futureField"));
        assertThrows(UnsupportedOperationException.class, () -> item.getLabels().put("app", "changed"));
        assertFalse(item.toString().contains("private-content"));
        assertTrue(item.getSpec().entrySet().isEmpty()); assertTrue(item.getStatus().entrySet().isEmpty());
        ResourceDetails copy = new ResourceDetails(input); input.getAsJsonObject("data").addProperty("config", "modified");
        assertEquals("private-content", copy.getData().get("config").getAsString());
    }

    @Test public void serviceAndPodDetailsExposeSpecAndStatus() {
        JsonObject service = resource("Service", "a", "web");
        service.add("spec", json("{\"type\":\"NodePort\",\"clusterIP\":\"10.0.0.1\",\"ports\":[{\"port\":80,\"targetPort\":\"http\",\"nodePort\":30080}],\"selector\":{\"app\":\"web\"}}"));
        service.add("status", json("{\"loadBalancer\":{\"ingress\":[{\"ip\":\"192.0.2.1\"}]}}"));
        page("1", "", service);
        ServiceDetails details = client.searchServicesDetailed("web").get(0);
        assertEquals("NodePort", details.getType());
        assertEquals("http", details.getPorts().get(0).getTargetPort());
        assertEquals("http", details.getSpec().getAsJsonArray("ports").get(0).getAsJsonObject().get("targetPort").getAsString());
        assertTrue(details.getStatus().has("loadBalancer"));
        details.getSpec().addProperty("type", "changed");
        assertEquals("NodePort", details.getSpec().get("type").getAsString());
        JsonObject pod = resource("Pod", "b", "web");
        pod.add("spec", json("{\"nodeName\":\"node-1\",\"containers\":[{\"name\":\"app\",\"image\":\"busybox\"}]}"));
        pod.add("status", json("{\"phase\":\"Running\",\"podIP\":\"10.1.0.1\",\"hostIP\":\"192.0.2.1\","
                + "\"startTime\":\"2026-09-21T00:00:00Z\",\"containerStatuses\":[{\"name\":\"app\",\"ready\":true}]}"));
        page("1", "", pod);
        PodDetails p = client.searchPodsDetailed("web").get(0);
        assertEquals("node-1", p.getNodeName());
        assertEquals("10.1.0.1", p.getPodIP());
        assertEquals("192.0.2.1", p.getHostIP());
        assertEquals("2026-09-21T00:00:00Z", p.getStartTime());
        assertEquals(Collections.singletonList("app"), p.getContainerNames());
        assertTrue(p.getContainerStatuses().get(0).getReady());
    }

    @Test public void collectionItemsWithoutTypeFieldsUseExplicitResourceDefinition() {
        JsonObject pod = resource("Pod", "team", "web");
        pod.remove("kind"); pod.remove("apiVersion");
        page("1", "", pod);
        ResourceDetails result = client.searchPodsDetailed("web").get(0);
        assertEquals("Pod", result.getKind()); assertEquals("v1", result.getApiVersion());
        assertEquals("Pod", result.toJson().get("kind").getAsString());
        ResourceDetails direct = new ResourceDetails(pod, "v1", "Pod");
        assertFalse(pod.has("kind")); assertEquals("Pod", direct.getKind());
        JsonObject custom = resource("Widget", "team", "web");
        custom.addProperty("apiVersion", "example.com/v2");
        ResourceDetails supplied = new ResourceDetails(custom, "example.com/v1", "OtherKind");
        assertEquals("Widget", supplied.getKind()); assertEquals("example.com/v2", supplied.getApiVersion());
    }

    @Test public void optionsPreserveSelectorsAndDoNotMutateCallerOrLimitTotalResults() {
        ListOptions options = ListOptions.builder().limit(1).labelSelector("app in (web,api)")
                .fieldSelector("status.phase=Running").resourceVersion("2").resourceVersionMatch("Exact").timeoutSeconds(5).build();
        page("2", "p2", resource("Pod", "a", "web-one"));
        page("2", "", resource("Pod", "b", "web-two"));
        assertEquals(2, client.searchPodsDetailed("web", options).size());
        assertFalse(options.toQueryParameters().containsKey("continue"));
        for (MockK8sApiServer.RecordedRequest request : server.getRequests()) {
            assertEquals("1", request.query.get("limit"));
            assertEquals("app in (web,api)", request.query.get("labelSelector"));
            assertEquals("status.phase=Running", request.query.get("fieldSelector"));
            assertEquals("2", request.query.get("resourceVersion")); assertEquals("Exact", request.query.get("resourceVersionMatch"));
        }
    }

    @Test public void literalNameMatchingDoesNotSearchNamespacesLabelsOrContents() {
        JsonObject input = resource("ConfigMap", "needle", "unrelated");
        input.add("data", json("{\"needle\":\"needle\"}"));
        input.getAsJsonObject("metadata").add("labels", json("{\"app\":\"needle\"}"));
        page("1", "", input, resource("ConfigMap", "a", "Needle"));
        assertTrue(client.searchConfigMaps("needle").isEmpty());
        page("1", "", resource("Service", "a", "web"));
        assertTrue(client.searchServices(".*").isEmpty());
    }

    @Test public void commonResourceMethodsUseCorrectGlobalCollections() throws Exception {
        String[][] routes = {
                {"Pods", "/api/v1/pods"}, {"ConfigMaps", "/api/v1/configmaps"}, {"Services", "/api/v1/services"},
                {"Deployments", "/apis/apps/v1/deployments"}, {"StatefulSets", "/apis/apps/v1/statefulsets"},
                {"DaemonSets", "/apis/apps/v1/daemonsets"}, {"ReplicaSets", "/apis/apps/v1/replicasets"},
                {"Jobs", "/apis/batch/v1/jobs"}, {"CronJobs", "/apis/batch/v1/cronjobs"},
                {"Ingresses", "/apis/networking.k8s.io/v1/ingresses"}, {"PersistentVolumeClaims", "/api/v1/persistentvolumeclaims"},
                {"Secrets", "/api/v1/secrets"}, {"ServiceAccounts", "/api/v1/serviceaccounts"},
                {"NetworkPolicies", "/apis/networking.k8s.io/v1/networkpolicies"}
        };
        String[] detailTypes = {"PodDetails", "ConfigMapDetails", "ServiceDetails", "DeploymentDetails",
                "StatefulSetDetails", "DaemonSetDetails", "ReplicaSetDetails", "JobDetails", "CronJobDetails",
                "IngressDetails", "PersistentVolumeClaimDetails", "SecretDetails", "ServiceAccountDetails", "NetworkPolicyDetails"};
        int routeIndex = 0;
        for (String[] route : routes) {
            for (String suffix : Arrays.asList("", "Detailed")) {
                for (boolean options : Arrays.asList(false, true)) {
                    page("1", "", resource("Fixture", "a", "target"), resource("Fixture", "b", "target"));
                    Object result = options ? K8sApiClient.class.getMethod("search" + route[0] + suffix, String.class, ListOptions.class)
                            .invoke(client, "target", ListOptions.builder().limit(1).build())
                            : K8sApiClient.class.getMethod("search" + route[0] + suffix, String.class).invoke(client, "target");
                    assertEquals(2, ((List<?>) result).size());
                    if (!suffix.isEmpty()) {
                        assertEquals(detailTypes[routeIndex], ((List<?>) result).get(0).getClass().getSimpleName());
                        assertEquals("Fixture", ((ResourceDetails) ((List<?>) result).get(0)).getKind());
                    }
                    MockK8sApiServer.RecordedRequest request = server.getRequests().get(server.getRequestCount() - 1);
                    assertEquals(route[1], request.path); assertEquals("GET", request.method); assertEquals("", request.body);
                    if (suffix.isEmpty() && !"Pods".equals(route[0])) {
                        assertEquals(new HashSet<String>(Arrays.asList("namespace", "name")),
                                new Gson().toJsonTree(((List<?>) result).get(0)).getAsJsonObject().keySet());
                    }
                }
            }
            routeIndex++;
        }
        assertEquals(56, server.getRequestCount());
    }

    @Test public void genericSearchSupportsCustomResourcesAndPreservesTheirFields() {
        ResourceDefinition widget = ResourceDefinition.namespaced("example.com/v1", "widgets", "Widget");
        JsonObject item = resource("Widget", "team", "widget-one");
        item.add("customRoot", json("{\"enabled\":true}"));
        page("1", "", item); assertEquals("widget-one", client.searchResources(widget, "widget").get(0).getName());
        page("1", "", item); assertTrue(client.searchResourcesDetailed(widget, "widget").get(0).toJson().has("customRoot"));
        assertEquals("/apis/example.com/v1/widgets", server.getRequests().get(1).path);
    }

    @Test public void invalidSearchInputsFailBeforeAnyNetworkRequest() {
        for (String value : Arrays.asList(null, "", " \t")) {
            assertThrows(IllegalArgumentException.class, () -> client.searchPods(value));
            assertThrows(IllegalArgumentException.class, () -> client.searchConfigMapsDetailed(value));
        }
        assertThrows(IllegalArgumentException.class, () -> client.searchResources(K8sResources.NODES, "node"));
        assertThrows(IllegalArgumentException.class, () -> client.searchResourcesDetailed(null, "node"));
        assertThrows(IllegalArgumentException.class, () -> client.searchServices("web", ListOptions.builder().continueToken("skip").build()));
        ResourceDefinition noList = ResourceDefinition.discovered("v1", "pods", "Pod", true, Collections.singleton("get"));
        assertThrows(UnsupportedOperationException.class, () -> client.searchResources(noList, "web"));
        assertEquals(0, server.getRequestCount());
    }

    @Test public void failuresNeverReturnPartialMatchesOrReplayRequests() {
        page("1", "next", resource("Pod", "a", "web"));
        server.enqueueResponse(403, "{\"message\":\"private-error\"}", Collections.singletonMap("Warning", "denied"));
        K8sApiException denied = assertThrows(K8sApiException.class, () -> client.searchPods("web"));
        assertEquals(403, denied.getStatusCode()); assertTrue(denied.getResponseBody().contains("private-error"));
        assertFalse(denied.getMessage().contains("private-error")); assertEquals(2, server.getRequestCount());
        for (int code : new int[]{401, 404, 410, 429, 503}) {
            server.enqueueResponse(code, "{}", Collections.singletonMap("Retry-After", "0"));
            assertEquals(code, assertThrows(K8sApiException.class, () -> client.searchServicesDetailed("web")).getStatusCode());
        }
        assertEquals(7, server.getRequestCount());
    }

    @Test public void invalidPaginationAndMissingIdentityFailInsteadOfTruncatingResults() {
        page("1", "repeat", resource("Pod", "a", "web")); page("1", "repeat");
        assertThrows(K8sToolsException.class, () -> client.searchPods("web"));
        page("1", "next"); page("2", "", resource("Pod", "a", "web"));
        assertThrows(K8sToolsException.class, () -> client.searchPodsDetailed("web"));
        page("1", "", json("{\"metadata\":{\"name\":\"web\"}}"));
        assertThrows(K8sToolsException.class, () -> client.searchServices("web"));
        assertEquals(5, server.getRequestCount());
    }

    @Test public void searchesDoNotLogKeywordsOrSensitiveDetails() throws Exception {
        PrintStream saved = System.err; boolean debug = K8sLogging.isDebugEnabled();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, "UTF-8")) {
            System.setErr(capture); K8sLogging.setDebugEnabled(true);
            JsonObject secret = resource("Secret", "a", "secret-config"); secret.add("data", json("{\"password\":\"encoded-private-data\"}"));
            page("1", "", secret); ResourceDetails result = client.searchSecretsDetailed("secret-config").get(0);
            assertEquals("encoded-private-data", result.getData().get("password").getAsString());
            assertFalse(result.toString().contains("encoded-private-data"));
            page("1", ""); client.searchConfigMaps("sensitive-keyword");
        } finally { System.setErr(saved); K8sLogging.setDebugEnabled(debug); }
        String logs = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(logs.contains("API 请求完成"));
        for (String secret : Arrays.asList("encoded-private-data", "sensitive-keyword", "search-token")) { assertFalse(logs.contains(secret)); }
    }

    private void page(String version, String token, JsonObject... items) {
        JsonObject page = new JsonObject(), metadata = new JsonObject(); JsonArray array = new JsonArray();
        metadata.addProperty("resourceVersion", version); metadata.addProperty("continue", token);
        for (JsonObject item : items) { array.add(item); }
        page.add("metadata", metadata); page.add("items", array); server.enqueueResponse(200, page.toString());
    }
    private static JsonObject resource(String kind, String namespace, String name) {
        JsonObject object = new JsonObject(), metadata = new JsonObject();
        object.addProperty("apiVersion", "v1"); object.addProperty("kind", kind);
        metadata.addProperty("namespace", namespace); metadata.addProperty("name", name); object.add("metadata", metadata);
        return object;
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
}

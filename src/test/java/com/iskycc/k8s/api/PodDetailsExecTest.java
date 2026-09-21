package com.iskycc.k8s.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iskycc.k8s.K8sTools;
import com.iskycc.k8s.api.model.PodDetails;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PodDetailsExecTest {
    private MockWebServer server;
    private K8sApiClient client;

    @Before public void start() throws Exception {
        server = new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        client = K8sApiClient.builder().apiServer(server.url("/").toString()).token("bound-client-token").build();
    }

    @After public void stop() { server.close(); }

    @Test public void searchResultExecutesWithSameClientAndNeedsOnlyOneExecRequest() throws Exception {
        PodDetails pod = search("app");
        PodExecOptions options = PodExecOptions.builder().timeoutMs(5000).maxOutputBytes(1024).build();
        reply("out", "err", 7);
        PodExecResult result = pod.exec(options, "printf", "%s", "a b;$(ignored)");
        assertEquals("out", result.getStdout()); assertEquals("err", result.getStderr());
        assertEquals(7, result.getExitCode()); assertFalse(result.isSuccess());
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("/api/v1/namespaces/team/pods/web/exec", request.getUrl().encodedPath());
        assertEquals(Arrays.asList("printf", "%s", "a b;$(ignored)"), request.getUrl().queryParameterValues("command"));
        assertEquals("app", request.getUrl().queryParameter("container"));
        assertEquals("Bearer bound-client-token", request.getHeaders().get("Authorization"));
        assertNull(options.getContainer()); // 自动选择不修改调用方的选项。
        assertEquals(2, server.getRequestCount()); // 一次搜索 + 一次 exec，无重新初始化或 GET Pod。
    }

    @Test public void facadeAndClientOverloadsSupportShellAndArgv() throws Exception {
        PodDetails pod = search("app");
        for (int i = 0; i < 3; i++) { reply("ok", "", 0); }
        assertEquals("ok", K8sTools.execShell(pod, "ls -al /tmp").getStdout());
        assertEquals(Arrays.asList("/bin/sh", "-c", "ls -al /tmp"), next().getUrl().queryParameterValues("command"));
        assertTrue(client.exec(pod, "ls", "-al", "/tmp").isSuccess());
        assertEquals(Arrays.asList("ls", "-al", "/tmp"), next().getUrl().queryParameterValues("command"));
        assertTrue(client.execShell(pod, "printf x | cat").isSuccess());
        assertEquals(Arrays.asList("/bin/sh", "-c", "printf x | cat"), next().getUrl().queryParameterValues("command"));
        assertEquals(4, server.getRequestCount());
    }

    @Test public void multipleContainersNeedExplicitSelectionAndSupportInitAndEphemeralNames() throws Exception {
        PodDetails pod = search("app", "sidecar");
        assertThrows(IllegalArgumentException.class, () -> K8sTools.exec(pod, "date"));
        assertThrows(IllegalArgumentException.class, () -> pod.execShell("date"));
        assertThrows(IllegalArgumentException.class, () -> client.exec(pod,
                PodExecOptions.builder().container("missing").build(), "date"));
        assertEquals(1, server.getRequestCount());
        for (String name : Arrays.asList("sidecar", "init", "debugger")) {
            reply("", "", 0);
            assertTrue(K8sTools.exec(pod, PodExecOptions.builder().container(name).build(), "date").isSuccess());
            assertEquals(name, next().getUrl().queryParameter("container"));
        }
        assertEquals(4, server.getRequestCount());
    }

    @Test public void boundResultCannotExecuteOnAnotherClientAndSerializationOmitsCredentials() throws Exception {
        PodDetails pod = search("app");
        K8sApiClient other = K8sApiClient.builder().apiServer(server.url("/").toString()).token("other-token").build();
        assertThrows(IllegalArgumentException.class, () -> other.exec(pod, "date"));
        assertThrows(IllegalArgumentException.class, () -> other.execShell(pod, "date"));
        assertEquals(1, server.getRequestCount());
        for (String value : Arrays.asList(new Gson().toJson(pod), pod.toJson().toString(), pod.toString())) {
            assertFalse(value.contains("bound-client-token"));
            assertFalse(value.contains("execClient"));
            assertFalse(value.contains(server.url("/").toString()));
        }
        reply("original", "", 0);
        assertEquals("original", pod.exec("date").getStdout());
        assertEquals("Bearer bound-client-token", next().getHeaders().get("Authorization"));
    }

    @Test public void detachedSnapshotsNeedExplicitExistingClientAndUnknownContainersNeedExplicitName() throws Exception {
        PodDetails detached = new PodDetails(search("app").toJson());
        assertThrows(IllegalStateException.class, () -> detached.exec("date"));
        assertThrows(IllegalStateException.class, () -> K8sTools.execShell(detached, "date"));
        reply("ok", "", 0);
        assertTrue(client.exec(detached, "date").isSuccess());
        next();
        PodDetails identityOnly = new PodDetails(JsonParser.parseString(
                "{\"metadata\":{\"namespace\":\"team\",\"name\":\"web\"}}" ).getAsJsonObject());
        assertThrows(IllegalArgumentException.class, () -> client.exec(identityOnly, "date"));
        reply("", "", 0);
        client.exec(identityOnly, PodExecOptions.builder().container("app").build(), "date");
        assertEquals("app", next().getUrl().queryParameter("container"));
        assertEquals(3, server.getRequestCount());
    }

    @Test public void executionFailureNeverRefreshesCredentialsOrReplaysCommand() throws Exception {
        PodDetails pod = search("app");
        server.enqueue(new MockResponse.Builder().code(401).body("private-error").build());
        K8sApiException error = assertThrows(K8sApiException.class, () -> pod.exec("date"));
        assertEquals(401, error.getStatusCode()); assertEquals("private-error", error.getResponseBody());
        assertEquals(2, server.getRequestCount());
        assertEquals(Collections.singletonList("date"), next().getUrl().queryParameterValues("command"));
    }

    @Test public void validationAndOutputLimitsArePreserved() throws Exception {
        PodDetails pod = search("app");
        assertThrows(IllegalArgumentException.class, () -> client.exec((PodDetails) null, "date"));
        assertThrows(IllegalArgumentException.class, () -> K8sTools.execShell(null, "date"));
        assertThrows(IllegalArgumentException.class, () -> pod.exec((PodExecOptions) null, "date"));
        assertThrows(IllegalArgumentException.class, () -> pod.execShell(" "));
        assertThrows(IllegalArgumentException.class, () -> pod.exec(new String[0]));
        assertEquals(1, server.getRequestCount());
        reply("12345", "", 0);
        PodExecException limit = assertThrows(PodExecException.class,
                () -> pod.exec(PodExecOptions.builder().maxOutputBytes(4).build(), "date"));
        assertEquals(PodExecException.Reason.OUTPUT_LIMIT, limit.getFailureReason());
        assertEquals("1234", limit.getPartialResult().getStdout());
        assertEquals(2, server.getRequestCount());
    }

    private PodDetails search(String... containerNames) throws Exception {
        JsonObject resource = JsonParser.parseString("{\"metadata\":{\"namespace\":\"team\",\"name\":\"web\","
                + "\"annotations\":{\"kubectl.kubernetes.io/default-container\":\"sidecar\"}},\"spec\":{"
                + "\"initContainers\":[{\"name\":\"init\"}],\"ephemeralContainers\":[{\"name\":\"debugger\"}]}}" ).getAsJsonObject();
        JsonArray containers = new JsonArray();
        for (String name : containerNames) {
            JsonObject container = new JsonObject(); container.addProperty("name", name); containers.add(container);
        }
        resource.getAsJsonObject("spec").add("containers", containers);
        JsonObject list = new JsonObject(); JsonArray items = new JsonArray(); items.add(resource);
        list.add("items", items);
        server.enqueue(new MockResponse.Builder().body(list.toString()).build());
        PodDetails pod = client.searchPodsDetailed("web").get(0);
        assertEquals("/api/v1/pods", next().getUrl().encodedPath());
        return pod;
    }

    private RecordedRequest next() throws Exception { return server.takeRequest(2, TimeUnit.SECONDS); }

    private void reply(String stdout, String stderr, int exitCode) {
        String status = exitCode == 0 ? "{\"status\":\"Success\"}"
                : "{\"status\":\"Failure\",\"reason\":\"NonZeroExitCode\",\"details\":{\"causes\":[{\"reason\":\"ExitCode\",\"message\":\"" + exitCode + "\"}]}}";
        server.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                socket.send(ByteString.encodeUtf8("\u0001" + stdout));
                socket.send(ByteString.encodeUtf8("\u0002" + stderr));
                socket.send(ByteString.encodeUtf8("\u0003" + status));
                socket.close(1000, null);
            }
        }).addHeader("Sec-WebSocket-Protocol", "v5.channel.k8s.io").build());
    }
}

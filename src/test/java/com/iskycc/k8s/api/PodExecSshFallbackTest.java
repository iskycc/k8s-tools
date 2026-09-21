package com.iskycc.k8s.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iskycc.k8s.K8sLogging;
import com.iskycc.k8s.ssh.SshConfig;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PodExecSshFallbackTest {
    private MockWebServer api;
    private SshServer ssh;
    private final AtomicInteger commands = new AtomicInteger();
    private final AtomicReference<Throwable> handlerError = new AtomicReference<Throwable>();
    private volatile String command;
    private volatile JsonObject input;
    private volatile byte[] stdout = "result中".getBytes(StandardCharsets.UTF_8);
    private volatile byte[] stderr = "diagnostic".getBytes(StandardCharsets.UTF_8);
    private volatile int exit = 0;
    private volatile boolean hang;
    private final CountDownLatch executing = new CountDownLatch(1);

    @Before public void start() throws Exception {
        api = new MockWebServer(); api.start(InetAddress.getByName("127.0.0.1"), 0);
        ssh = SshServer.setUpDefaultServer(); ssh.setHost("127.0.0.1"); ssh.setPort(0);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        ssh.setKeyPairProvider(KeyPairProvider.wrap(generator.generateKeyPair()));
        ssh.setPasswordAuthenticator((u, p, s) -> u.equals("exec-user") && p.equals("ssh-secret"));
        ssh.setCommandFactory((channel, cmd) -> new Command() {
            private InputStream in;
            private OutputStream out;
            private OutputStream err;
            private ExitCallback callback;
            private Thread thread;
            @Override public void setInputStream(InputStream stream) { in = stream; }
            @Override public void setOutputStream(OutputStream stream) { out = stream; }
            @Override public void setErrorStream(OutputStream stream) { err = stream; }
            @Override public void setExitCallback(ExitCallback value) { callback = value; }
            @Override public void start(ChannelSession session, Environment env) {
                commands.incrementAndGet(); command = cmd;
                thread = new Thread(() -> {
                    try {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
                        while ((n = in.read(buffer)) != -1) { bytes.write(buffer, 0, n); }
                        input = JsonParser.parseString(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
                        out.write(stdout); out.flush(); err.write(stderr); err.flush();
                        executing.countDown();
                        if (hang) { new CountDownLatch(1).await(30, TimeUnit.SECONDS); }
                        callback.onExit(exit);
                    } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                    catch (Exception e) {
                        if (!hang) { handlerError.set(e); }
                        callback.onExit(1);
                    }
                }, "mock-pod-exec-ssh");
                thread.setDaemon(true); thread.start();
            }
            @Override public void destroy(ChannelSession session) { if (thread != null) { thread.interrupt(); } }
        });
        ssh.start();
    }

    @After public void close() throws Exception {
        api.close(); ssh.stop(true);
    }

    private SshConfig config() { return SshConfig.builder().host("127.0.0.1").port(ssh.getPort())
            .username("exec-user").password("ssh-secret").connectTimeoutMs(2000).build(); }
    private K8sApiClient client() { return K8sApiClient.builder().apiServer(api.url("/").toString())
            .token("api-secret").execSshConfig(config()).build(); }
    private void version(String value) { api.enqueue(new MockResponse.Builder().body("{\"gitVersion\":\"" + value + "\"}").build()); }
    private void websocket() {
        api.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response r) {
                ws.send(ByteString.of(new byte[]{1, 'o', 'k'}));
                ws.send(ByteString.encodeUtf8("\u0003{\"status\":\"Success\"}")); ws.close(1000, null);
            }
        }).addHeader("Sec-WebSocket-Protocol", "v5.channel.k8s.io").build());
    }

    @Test public void oldVersionSelectsSshBeforeExecutionAndCachesSuccessfulProbe() throws Exception {
        version("v1.30.14-gke.123"); exit = 7;
        K8sApiClient client = client();
        for (int i = 0; i < 2; i++) {
            PodExecResult result = client.exec("default", "pod", PodExecOptions.builder().container("app").build(),
                    "printf", "%s", "a b';$(touch /tmp/should-not-run)\n中文", "");
            assertEquals(7, result.getExitCode()); assertEquals("result中", result.getStdout());
            assertEquals("diagnostic", result.getStderr());
            assertFalse(command.contains("api-secret")); assertFalse(command.contains("ssh-secret"));
            assertTrue(command.contains("--container='app'"));
            assertTrue(command.contains("KUBECTL_REMOTE_COMMAND_WEBSOCKETS=false"));
            assertTrue(input.getAsJsonArray("users").get(0).getAsJsonObject().getAsJsonObject("user")
                    .get("token").getAsString().equals("api-secret"));
            assertEquals(client.getApiServer(), input.getAsJsonArray("clusters").get(0).getAsJsonObject()
                    .getAsJsonObject("cluster").get("server").getAsString());
        }
        assertEquals(1, api.getRequestCount()); assertEquals("/version", api.takeRequest().getUrl().encodedPath());
        assertEquals(2, commands.get()); assertNull(handlerError.get());
    }

    @Test public void newVersionUsesOnlyWebsocketAndExplicitModesSkipVersion() {
        version("v1.31.0+k3s1"); websocket(); websocket();
        K8sApiClient client = client();
        assertEquals("ok", client.exec("default", "pod", "date").getStdout());
        assertEquals("ok", client.exec("default", "pod", "date").getStdout());
        assertEquals(3, api.getRequestCount()); assertEquals(0, commands.get());
        assertTrue(client.exec("default", "pod", PodExecOptions.builder().transport(PodExecOptions.Transport.SSH).build(), "date").isSuccess());
        assertEquals(3, api.getRequestCount()); assertEquals(1, commands.get());
        websocket();
        assertTrue(client().exec("default", "pod", PodExecOptions.builder().transport(PodExecOptions.Transport.WEBSOCKET).build(), "date").isSuccess());
        assertEquals(4, api.getRequestCount()); assertEquals(1, commands.get());
    }

    @Test public void versionParserHandlesSuffixesPrereleaseAndMissingGitVersion() {
        for (String value : Arrays.asList("v1.20.15", "1.30.9", "v1.31.0-alpha.2", "v1.31.0-rc.1")) {
            assertTrue(PodExecVersion.parse("{\"gitVersion\":\"" + value + "\"}").useSsh());
        }
        for (String value : Arrays.asList("v1.31.0", "v1.31.1-eks-123", "v1.32.0-beta.1", "v2.0.0")) {
            assertFalse(PodExecVersion.parse("{\"gitVersion\":\"" + value + "\"}").useSsh());
        }
        assertFalse(PodExecVersion.parse("{\"major\":\"1\",\"minor\":\"31+\"}").useSsh());
        assertTrue(PodExecVersion.parse("{\"major\":\"1\",\"minor\":\"9\"}").useSsh());
    }

    @Test public void failedOrInvalidVersionNeverExecutesNorGetsCached() {
        K8sApiClient client = client();
        api.enqueue(new MockResponse.Builder().code(403).body("forbidden").build());
        assertEquals(403, assertThrows(K8sApiException.class, () -> client.exec("default", "pod", "date")).getStatusCode());
        api.enqueue(new MockResponse.Builder().body("{\"gitVersion\":\"unrecognized\"}").build());
        assertEquals(PodExecException.Reason.VERSION_DETECTION,
                assertThrows(PodExecException.class, () -> client.exec("default", "pod", "date")).getFailureReason());
        assertEquals(0, commands.get());
        version("v1.30.0"); assertTrue(client.exec("default", "pod", "date").isSuccess());
        assertEquals(3, api.getRequestCount()); assertEquals(1, commands.get());
    }

    @Test public void websocketRejectionNeverReplaysViaSsh() {
        version("v1.31.1"); api.enqueue(new MockResponse.Builder().code(503).body("unavailable").addHeader("Retry-After", "0").build());
        assertEquals(503, assertThrows(K8sApiException.class, () -> client().exec("default", "pod", "date")).getStatusCode());
        assertEquals(2, api.getRequestCount()); assertEquals(0, commands.get());
    }

    @Test public void autoWebsocketTimeoutRetainsPartialOutputAndNeverSwitchesToSsh() {
        version("v1.31.1");
        api.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response r) {
                ws.send(ByteString.encodeUtf8("\u0001started"));
            }
        }).addHeader("Sec-WebSocket-Protocol", "v5.channel.k8s.io").build());
        PodExecException error = assertThrows(PodExecException.class, () -> client().exec("default", "pod",
                PodExecOptions.builder().timeoutMs(500).build(), "date"));
        assertEquals(PodExecException.Reason.TIMEOUT, error.getFailureReason());
        assertEquals("started", error.getPartialResult().getStdout());
        assertEquals(0, commands.get()); assertEquals(2, api.getRequestCount());
    }

    @Test public void sshOutputIsBoundedAndTimeoutPreservesPartialResult() {
        version("v1.30.0"); stdout = new byte[65536]; stderr = new byte[0]; hang = true;
        PodExecException limit = assertThrows(PodExecException.class, () -> client().exec("default", "pod",
                PodExecOptions.builder().maxOutputBytes(128).build(), "date"));
        assertEquals(PodExecException.Reason.OUTPUT_LIMIT, limit.getFailureReason());
        assertEquals(128, limit.getPartialResult().getStdoutBytes().length);
        stdout = "started".getBytes(StandardCharsets.UTF_8);
        PodExecException timeout = assertThrows(PodExecException.class, () -> client().exec("default", "pod",
                PodExecOptions.builder().transport(PodExecOptions.Transport.SSH).timeoutMs(1000).build(), "sleep", "60"));
        assertEquals(PodExecException.Reason.TIMEOUT, timeout.getFailureReason());
        assertEquals("started", timeout.getPartialResult().getStdout());
        assertEquals(2, commands.get());
    }

    @Test public void interruptRestoredAndNoCommandRetry() throws Exception {
        hang = true; AtomicReference<PodExecException> error = new AtomicReference<PodExecException>();
        AtomicBoolean interrupted = new AtomicBoolean(); K8sApiClient client = client();
        Thread caller = new Thread(() -> {
            try { client.exec("default", "pod", PodExecOptions.builder().transport(PodExecOptions.Transport.SSH).build(), "date"); }
            catch (PodExecException e) { error.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            caller.start(); assertTrue(executing.await(5, TimeUnit.SECONDS)); caller.interrupt(); caller.join(3000);
            assertFalse(caller.isAlive()); assertNotNull(error.get());
            assertEquals(PodExecException.Reason.INTERRUPTED, error.get().getFailureReason()); assertTrue(interrupted.get());
            assertEquals(1, commands.get());
        } finally { caller.interrupt(); caller.join(3000); }
    }

    @Test public void probeIsInsideTotalTimeoutAndSendsNoExec() {
        api.enqueue(new MockResponse.Builder().body("{\"gitVersion\":\"v1.20.0\"}").bodyDelay(2, TimeUnit.SECONDS).build());
        PodExecException error = assertThrows(PodExecException.class, () -> client().exec("default", "pod",
                PodExecOptions.builder().timeoutMs(300).build(), "date"));
        assertEquals(PodExecException.Reason.TIMEOUT, error.getFailureReason()); assertEquals(0, commands.get());
    }

    @Test public void sshConfigIsRequiredForForcedSshAndBadArgsFailBeforeProbe() {
        K8sApiClient direct = K8sApiClient.builder().apiServer(api.url("/").toString()).token("test").build();
        assertThrows(IllegalStateException.class, () -> direct.exec("default", "pod",
                PodExecOptions.builder().transport(PodExecOptions.Transport.SSH).build(), "date"));
        assertThrows(IllegalArgumentException.class, () -> client().exec("default", "../pod", "date"));
        assertThrows(IllegalArgumentException.class, () -> client().exec("default", "pod", "echo", "x\0y"));
        assertEquals(0, commands.get()); assertEquals(0, api.getRequestCount());
    }

    @Test public void sensitiveInputsAndOutputNeverEnterLogs() throws Exception {
        boolean old = K8sLogging.isDebugEnabled(); PrintStream saved = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, "UTF-8")) {
            System.setErr(capture); K8sLogging.setDebugEnabled(true); version("v1.20.0");
            client().execShell("default", "pod", "secret-command");
        } finally { System.setErr(saved); K8sLogging.setDebugEnabled(old); }
        String logs = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(logs.contains("Pod exec 执行通道 transport=SSH"));
        for (String secret : Arrays.asList("api-secret", "ssh-secret", "secret-command", "result中", "diagnostic")) {
            assertFalse(logs.contains(secret));
        }
    }

    @Test public void everyTransportChoiceIsLoggedOnceWithDebugDisabled() throws Exception {
        boolean old = K8sLogging.isDebugEnabled(); PrintStream saved = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, "UTF-8")) {
            System.setErr(capture); K8sLogging.setDebugEnabled(false);
            websocket();
            K8sApiClient.builder().apiServer(api.url("/").toString()).token("api-secret").build()
                    .execShell("default", "pod", "private-command");
            version("v1.30.0"); client().execShell("default", "pod", "private-command");
            version("v1.31.0"); websocket(); client().execShell("default", "pod", "private-command");
            client().execShell("default", "pod",
                    PodExecOptions.builder().transport(PodExecOptions.Transport.SSH).build(), "private-command");
            websocket(); client().execShell("default", "pod",
                    PodExecOptions.builder().transport(PodExecOptions.Transport.WEBSOCKET).build(), "private-command");
        } finally { System.setErr(saved); K8sLogging.setDebugEnabled(old); }
        String logs = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(5, logs.split("Pod exec 执行通道", -1).length - 1);
        for (String route : Arrays.asList("WEBSOCKET mode=AUTO version=-", "SSH mode=AUTO version=1.30",
                "WEBSOCKET mode=AUTO version=1.31", "SSH mode=SSH version=-", "WEBSOCKET mode=WEBSOCKET version=-")) {
            assertTrue(logs.contains("INFO com.iskycc.k8s.api.K8sApiClient - Pod exec 执行通道 transport=" + route));
        }
        assertTrue(logs.contains("namespace=default pod=pod container=-"));
        assertFalse(logs.contains(" DEBUG "));
        for (String secret : Arrays.asList("api-secret", "ssh-secret", "private-command", "result中", "diagnostic")) {
            assertFalse(logs.contains(secret));
        }
        assertEquals(5, api.getRequestCount()); assertEquals(2, commands.get());
    }

    @Test public void generatedShellPreservesArgvAndRemovesTemporaryConfig() throws Exception {
        org.junit.Assume.assumeTrue(Files.isExecutable(java.nio.file.Paths.get("/bin/sh")));
        Path dir = Files.createTempDirectory("pod-exec-shell-test"); Path marker = dir.resolve("injected");
        Path configPath = dir.resolve("config-path"); Path savedConfig = dir.resolve("received-config");
        Path kubectl = dir.resolve("kubectl");
        try {
            Files.write(kubectl, Arrays.asList("#!/bin/sh", "set -eu", "printf '%s' \"${1#--kubeconfig=}\" > \"$TEST_CONFIG_PATH\"",
                    "cat \"${1#--kubeconfig=}\" > \"$TEST_CONFIG_CONTENT\"", "shift", "printf '<%s>\\n' \"$@\"", "exit 7"), StandardCharsets.UTF_8);
            assertTrue(kubectl.toFile().setExecutable(true));
            String[] argv = {"printf", "a b'\";$(touch " + marker + ")\n中文", ""};
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", PodExecSshTransport.shellCommand("default", "pod",
                    PodExecOptions.builder().container("app").build(), argv));
            builder.environment().put("PATH", dir + ":" + System.getenv("PATH"));
            builder.environment().put("TEST_CONFIG_PATH", configPath.toString());
            builder.environment().put("TEST_CONFIG_CONTENT", savedConfig.toString());
            Process process = builder.start();
            try {
                process.getOutputStream().write("{\"test\":true}".getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
                while ((n = process.getInputStream().read(buffer)) != -1) { out.write(buffer, 0, n); }
                assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(7, process.exitValue());
                assertEquals("<--namespace=default>\n<exec>\n<pod>\n<--container=app>\n<-->\n<printf>\n<" + argv[1] + ">\n<>\n",
                        new String(out.toByteArray(), StandardCharsets.UTF_8));
                assertFalse(Files.exists(marker));
                assertFalse(Files.exists(java.nio.file.Paths.get(new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8))));
                assertEquals("{\"test\":true}", new String(Files.readAllBytes(savedConfig), StandardCharsets.UTF_8));
            } finally { process.destroyForcibly(); }
        } finally {
            Files.deleteIfExists(kubectl); Files.deleteIfExists(marker); Files.deleteIfExists(configPath);
            Files.deleteIfExists(savedConfig); Files.deleteIfExists(dir);
        }
    }
}

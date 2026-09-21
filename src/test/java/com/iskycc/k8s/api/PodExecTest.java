package com.iskycc.k8s.api;

import com.iskycc.k8s.K8sLogging;
import com.iskycc.k8s.mock.CertUtil;
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

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PodExecTest {
    private static final String TOKEN = "exec-sensitive-token";
    private static final String SUCCESS = "{\"status\":\"Success\"}";
    private MockWebServer server;
    private K8sApiClient client;

    @Before public void start() throws Exception {
        server = new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        client = K8sApiClient.builder().apiServer(server.url("/").toString()).token(TOKEN).build();
    }

    @After public void stop() { server.close(); }

    private void enqueue(String protocol, byte[]... frames) {
        MockResponse.Builder response = new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response ignored) {
                for (byte[] frame : frames) { ws.send(ByteString.of(frame)); }
                ws.close(1000, null);
            }
        });
        if (protocol != null) { response.setHeader("Sec-WebSocket-Protocol", protocol); }
        server.enqueue(response.build());
    }

    private static byte[] frame(int channel, String content) {
        byte[] text = content.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[text.length + 1];
        result[0] = (byte) channel;
        System.arraycopy(text, 0, result, 1, text.length);
        return result;
    }

    @Test public void argvAndUtf8BytesRemainIntactAcrossChannelsAndMessages() throws Exception {
        enqueue("v5.channel.k8s.io", new byte[]{1}, new byte[]{1, (byte) 0xe4},
                new byte[]{1, (byte) 0xb8, (byte) 0xad}, frame(2, "error\n"),
                frame(3, "{\"status\":"), frame(3, "\"Success\"}"), new byte[]{(byte) 255, 1});
        String[] command = {"printf", "a b", "", "$HOME; &?=/%#中"};
        PodExecResult result = client.exec("all", "pod-1", PodExecOptions.builder().container("sidecar").build(), command);
        assertTrue(result.isSuccess());
        assertEquals("中", result.getStdout());
        assertEquals("error\n", result.getStderr());
        byte[] copy = result.getStdoutBytes(); copy[0] = 0;
        assertArrayEquals("中".getBytes(StandardCharsets.UTF_8), result.getStdoutBytes());
        assertFalse(result.toString().contains("error\n"));
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("GET", request.getMethod());
        assertEquals("/api/v1/namespaces/all/pods/pod-1/exec", request.getUrl().encodedPath());
        assertEquals(Arrays.asList(command), request.getUrl().queryParameterValues("command"));
        assertEquals("sidecar", request.getUrl().queryParameter("container"));
        assertEquals("false", request.getUrl().queryParameter("stdin"));
        assertEquals("false", request.getUrl().queryParameter("tty"));
        assertEquals("true", request.getUrl().queryParameter("stderr"));
        assertEquals("Bearer " + TOKEN, request.getHeaders().get("Authorization"));
        assertEquals("v5.channel.k8s.io, v4.channel.k8s.io", request.getHeaders().get("Sec-WebSocket-Protocol"));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void v4AndNonzeroExitCodesAreResultsNotTransportErrors() {
        enqueue("v4.channel.k8s.io", frame(1, "partial"), frame(2, "bad"), frame(3,
                "{\"status\":\"Failure\",\"reason\":\"NonZeroExitCode\",\"details\":{\"causes\":[{\"reason\":\"ExitCode\",\"message\":\"7\"}]}}"));
        PodExecResult result = client.exec("default", "pod", "false");
        assertFalse(result.isSuccess()); assertEquals(7, result.getExitCode());
        assertEquals("partial", result.getStdout()); assertEquals("bad", result.getStderr());
    }

    @Test public void shellIsExplicitAndNotUsedForRegularExec() throws Exception {
        enqueue("v5.channel.k8s.io", frame(3, SUCCESS));
        client.execShell("default", "pod", "printf x | cat");
        assertEquals(Arrays.asList("/bin/sh", "-c", "printf x | cat"),
                server.takeRequest(2, TimeUnit.SECONDS).getUrl().queryParameterValues("command"));
        enqueue("v5.channel.k8s.io", frame(3, SUCCESS));
        client.exec("default", "pod", "printf x | cat");
        assertEquals(Arrays.asList("printf x | cat"),
                server.takeRequest(2, TimeUnit.SECONDS).getUrl().queryParameterValues("command"));
    }

    @Test public void httpErrorsAndRedirectsNeverReplayOrLoseStatus() {
        for (int code : new int[]{401, 403, 404, 302, 408}) {
            server.enqueue(new MockResponse.Builder().code(code).body("private-error-body")
                    .addHeader("Audit-Id", "test-audit").addHeader("Location", server.url("/redirect"))
                    .addHeader("Retry-After", "0").build());
            K8sApiException error = assertThrows(K8sApiException.class, () -> client.exec("default", "pod", "date"));
            assertEquals(code, error.getStatusCode());
            assertEquals("private-error-body", error.getResponseBody());
            assertTrue(error.getResponseHeaders().entrySet().stream().anyMatch(entry ->
                    "Audit-Id".equalsIgnoreCase(entry.getKey()) && Arrays.asList("test-audit").equals(entry.getValue())));
            assertFalse(error.getMessage().contains("private-error-body"));
        }
        server.enqueue(new MockResponse.Builder().code(503).body("unavailable").addHeader("Retry-After", "0").build());
        K8sApiException unavailable = assertThrows(K8sApiException.class, () -> client.exec("default", "pod", "date"));
        assertEquals(503, unavailable.getStatusCode());
        assertEquals("unavailable", unavailable.getResponseBody());
        assertEquals(6, server.getRequestCount());
    }

    @Test public void missingExitStatusAndInvalidProtocolsNeverMeanSuccess() {
        for (String protocol : new String[]{null, "v3.channel.k8s.io", "other", "v5.channel.k8s.io"}) {
            enqueue(protocol, frame(1, "partial"));
            PodExecException error = assertThrows(PodExecException.class, () -> client.exec("default", "pod", "date"));
            assertEquals(PodExecException.Reason.PROTOCOL, error.getFailureReason());
            assertEquals(-1, error.getPartialResult().getExitCode());
        }
        for (String status : new String[]{"bad-json", "{}", "{\"status\":\"Failure\",\"reason\":\"NonZeroExitCode\"}"}) {
            enqueue("v5.channel.k8s.io", frame(3, status));
            assertEquals(PodExecException.Reason.PROTOCOL,
                    assertThrows(PodExecException.class, () -> client.exec("default", "pod", "date")).getFailureReason());
        }
    }

    @Test public void remoteStartFailuresAndUnknownChannelsAreReported() {
        enqueue("v5.channel.k8s.io", frame(3, "{\"status\":\"Failure\",\"message\":\"private command\",\"reason\":\"InternalError\"}"));
        PodExecException error = assertThrows(PodExecException.class, () -> client.exec("default", "pod", "date"));
        assertEquals(PodExecException.Reason.REMOTE_ERROR, error.getFailureReason());
        assertFalse(error.getMessage().contains("private command"));
        assertTrue(error.getRemoteStatus().contains("private command"));
        enqueue("v5.channel.k8s.io", frame(0, "unexpected"));
        assertEquals(PodExecException.Reason.PROTOCOL,
                assertThrows(PodExecException.class, () -> client.exec("default", "pod", "date")).getFailureReason());
    }

    @Test public void outputLimitFailsWithBoundedPartialOutput() {
        enqueue("v5.channel.k8s.io", frame(1, "1234"), frame(2, "5678"), frame(3, SUCCESS));
        PodExecException error = assertThrows(PodExecException.class, () -> client.exec("default", "pod",
                PodExecOptions.builder().maxOutputBytes(6).build(), "date"));
        assertEquals(PodExecException.Reason.OUTPUT_LIMIT, error.getFailureReason());
        assertEquals("1234", error.getPartialResult().getStdout());
        assertEquals("56", error.getPartialResult().getStderr());
        assertEquals(1, server.getRequestCount());
    }

    @Test public void timeoutAndInterruptionCloseSocketWithoutReplaying() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        WebSocketListener hanging = new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) { socket.send(ByteString.of(frame(1, "started"))); }
            @Override public void onFailure(WebSocket socket, Throwable t, Response r) { disconnected.countDown(); }
            @Override public void onClosed(WebSocket socket, int code, String reason) { disconnected.countDown(); }
        };
        server.enqueue(new MockResponse.Builder().webSocketUpgrade(hanging).addHeader("Sec-WebSocket-Protocol", "v5.channel.k8s.io").build());
        PodExecException error = assertThrows(PodExecException.class, () -> client.exec("default", "pod",
                PodExecOptions.builder().timeoutMs(500).build(), "sleep", "60"));
        assertEquals(PodExecException.Reason.TIMEOUT, error.getFailureReason());
        assertEquals("started", error.getPartialResult().getStdout());
        assertTrue(disconnected.await(3, TimeUnit.SECONDS));
        CountDownLatch opened = new CountDownLatch(1);
        server.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) { opened.countDown(); }
        }).addHeader("Sec-WebSocket-Protocol", "v5.channel.k8s.io").build());
        AtomicReference<PodExecException> interruptedError = new AtomicReference<PodExecException>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            try { client.exec("default", "pod", "sleep", "60"); }
            catch (PodExecException e) { interruptedError.set(e); interruptRestored.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            thread.start(); assertTrue(opened.await(3, TimeUnit.SECONDS));
            thread.interrupt(); thread.join(3000);
            assertFalse(thread.isAlive()); assertNotNull(interruptedError.get());
            assertEquals(PodExecException.Reason.INTERRUPTED, interruptedError.get().getFailureReason());
            assertTrue(interruptRestored.get());
        } finally { thread.interrupt(); thread.join(3000); }
        assertEquals(2, server.getRequestCount());
    }

    @Test public void tlsUsesCaAndHostnameAndNeverAutoDegradesForExec() throws Exception {
        server.close();
        server = new MockWebServer();
        CertUtil.CertBundle cert = CertUtil.generateSelfSigned("localhost", new String[]{"localhost"}, new String[]{"127.0.0.1"});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(cert.toKeyStore("server", "test-password".toCharArray()), "test-password".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(keys.getKeyManagers(), null, null);
        server.useHttps(ssl.getSocketFactory()); server.start(InetAddress.getByName("127.0.0.1"), 0);
        K8sApiClient untrusted = K8sApiClient.builder().apiServer(server.url("/").toString()).token(TOKEN).build();
        assertThrows(PodExecException.class, () -> untrusted.exec("default", "pod", "date"));
        assertFalse(untrusted.isDegradedToInsecure());
        enqueue("v5.channel.k8s.io", frame(3, SUCCESS));
        K8sApiClient strict = K8sApiClient.builder().apiServer(server.url("/").toString()).token(TOKEN)
                .caCertPem(cert.getPem()).tlsAutoFallback(false).build();
        assertTrue(strict.exec("default", "pod", "date").isSuccess());
        enqueue("v5.channel.k8s.io", frame(3, SUCCESS));
        assertTrue(K8sApiClient.builder().apiServer(server.url("/").toString()).token(TOKEN)
                .insecureSkipTlsVerify(true).build().exec("default", "pod", "date").isSuccess());
        assertEquals(2, server.getRequestCount());
    }

    @Test public void chunkedHandshakeErrorStillPreservesStatusAndHeaders() {
        server.enqueue(new MockResponse.Builder().code(403).chunkedBody("forbidden", 2).addHeader("X-Test", "denied").build());
        K8sApiException error = assertThrows(K8sApiException.class, () -> client.exec("default", "pod", "date"));
        assertEquals(403, error.getStatusCode());
        // 底层握手客户端只读取 Content-Length 正文；分块正文不可用，但不能丢失 HTTP 状态。
        assertEquals("", error.getResponseBody());
        assertTrue(error.getResponseHeaders().entrySet().stream().anyMatch(entry ->
                "X-Test".equalsIgnoreCase(entry.getKey()) && Arrays.asList("denied").equals(entry.getValue())));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void trustedCertificateWithWrongHostnameDoesNotSendCommand() throws Exception {
        server.close(); server = new MockWebServer();
        CertUtil.CertBundle cert = CertUtil.generateSelfSigned("wrong.invalid", new String[]{"wrong.invalid"}, new String[0]);
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(cert.toKeyStore("server", "test-password".toCharArray()), "test-password".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(keys.getKeyManagers(), null, null);
        server.useHttps(ssl.getSocketFactory()); server.start(InetAddress.getByName("127.0.0.1"), 0);
        K8sApiClient strict = K8sApiClient.builder().apiServer(server.url("/").toString()).token(TOKEN)
                .caCertPem(cert.getPem()).build();
        assertThrows(PodExecException.class, () -> strict.exec("default", "pod", "date"));
        assertFalse(strict.isDegradedToInsecure());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void rejectsInvalidInputBeforeOpeningAnyConnection() {
        assertThrows(IllegalArgumentException.class, () -> client.exec(null, "pod", "date"));
        assertThrows(IllegalArgumentException.class, () -> client.exec("default", "../pod", "date"));
        assertThrows(IllegalArgumentException.class, () -> client.exec("default", "pod", new String[0]));
        assertThrows(IllegalArgumentException.class, () -> client.exec("default", "pod", "echo", null));
        assertThrows(IllegalArgumentException.class, () -> client.exec("default", "pod", "echo", "x\0y"));
        assertThrows(IllegalArgumentException.class, () -> PodExecOptions.builder().container("a/b").build());
        assertThrows(IllegalArgumentException.class, () -> PodExecOptions.builder().timeoutMs(0).build());
        assertThrows(IllegalArgumentException.class, () -> PodExecOptions.builder().maxOutputBytes(0).build());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void alreadyInterruptedThreadSendsNoCommand() {
        try {
            Thread.currentThread().interrupt();
            PodExecException error = assertThrows(PodExecException.class, () -> client.exec("default", "pod", "date"));
            assertEquals(PodExecException.Reason.INTERRUPTED, error.getFailureReason());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, server.getRequestCount());
        } finally { Thread.interrupted(); }
    }

    @Test public void stalledHandshakeTimesOutAndClosesConnection() throws Exception {
        try (java.net.ServerSocket raw = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch disconnected = new CountDownLatch(1);
            AtomicReference<Throwable> serverError = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                try (java.net.Socket connection = raw.accept()) {
                    connection.setSoTimeout(3000);
                    // 接收 Upgrade 请求但不回答，等待调用方超时关闭连接。
                    while (connection.getInputStream().read() != -1) { }
                    disconnected.countDown();
                } catch (Throwable e) { serverError.set(e); }
            });
            worker.setDaemon(true);
            worker.start();
            try {
                K8sApiClient pending = K8sApiClient.builder()
                        .apiServer("http://127.0.0.1:" + raw.getLocalPort()).token(TOKEN).build();
                PodExecException error = assertThrows(PodExecException.class, () -> pending.exec("default", "pod",
                        PodExecOptions.builder().timeoutMs(500).build(), "date"));
                assertEquals(PodExecException.Reason.TIMEOUT, error.getFailureReason());
                assertTrue(disconnected.await(3, TimeUnit.SECONDS));
            } finally { raw.close(); worker.join(3000); }
            assertFalse(worker.isAlive());
            if (serverError.get() != null) { throw new AssertionError(serverError.get()); }
        }
    }

    @Test public void eofRequiresCompleteStatusAndRejectsTruncatedFrames() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            final int mode = scenario;
            try (java.net.ServerSocket raw = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                AtomicReference<Throwable> serverError = new AtomicReference<Throwable>();
                Thread worker = new Thread(() -> {
                    try (java.net.Socket connection = raw.accept()) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                                connection.getInputStream(), StandardCharsets.US_ASCII));
                        String key = null, line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
                                key = line.substring(line.indexOf(':') + 1).trim();
                            }
                        }
                        String accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                        java.io.OutputStream out = connection.getOutputStream();
                        out.write(("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"
                                + "Sec-WebSocket-Accept: " + accept + "\r\nSec-WebSocket-Protocol: v4.channel.k8s.io\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        byte[] payload = frame(mode == 0 ? 3 : 1, mode == 0 ? SUCCESS : "partial");
                        out.write(0x82); out.write(payload.length + (mode == 2 ? 10 : 0)); out.write(payload); out.flush();
                    } catch (Throwable e) { serverError.set(e); }
                });
                worker.setDaemon(true); worker.start();
                try {
                    K8sApiClient rawClient = K8sApiClient.builder().apiServer("http://127.0.0.1:" + raw.getLocalPort()).token(TOKEN).build();
                    if (mode == 0) { assertTrue(rawClient.exec("default", "pod", "date").isSuccess()); }
                    else {
                        PodExecException error = assertThrows(PodExecException.class, () -> rawClient.exec("default", "pod", "date"));
                        assertEquals(-1, error.getPartialResult().getExitCode());
                        assertEquals(mode == 1 ? PodExecException.Reason.PROTOCOL : PodExecException.Reason.TRANSPORT,
                                error.getFailureReason());
                    }
                } finally { raw.close(); worker.join(3000); }
                assertFalse(worker.isAlive());
                if (serverError.get() != null) { throw new AssertionError(serverError.get()); }
            }
        }
    }

    @Test public void debugLogsDoNotContainCommandTokenOrOutput() throws Exception {
        boolean old = K8sLogging.isDebugEnabled(); PrintStream saved = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, "UTF-8")) {
            System.setErr(capture); K8sLogging.setDebugEnabled(true);
            enqueue("v5.channel.k8s.io", frame(1, "private-output"), frame(2, "private-stderr"), frame(3, SUCCESS));
            client.execShell("default", "pod", "private-command");
            server.enqueue(new MockResponse.Builder().code(403).body("private-body").build());
            assertThrows(K8sApiException.class, () -> client.exec("default", "pod", "private-command"));
        } finally { System.setErr(saved); K8sLogging.setDebugEnabled(old); }
        String logs = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(logs.contains("Pod exec 开始")); assertTrue(logs.contains("status=403"));
        for (String secret : new String[]{TOKEN, "private-output", "private-stderr", "private-command", "private-body"}) {
            assertFalse("敏感信息不能进入 exec 日志", logs.contains(secret));
        }
    }
}

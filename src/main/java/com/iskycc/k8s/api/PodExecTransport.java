package com.iskycc.k8s.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.iskycc.k8s.internal.LogSupport;
import com.neovisionaries.ws.client.OpeningHandshakeException;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 与普通缓冲 REST 请求独立的、单次使用的 Kubernetes remotecommand WebSocket 通道。 */
final class PodExecTransport {
    private static final Logger LOG = LoggerFactory.getLogger(PodExecTransport.class);
    private static final AtomicLong IDS = new AtomicLong();
    private static final String V5 = "v5.channel.k8s.io";
    private static final String V4 = "v4.channel.k8s.io";
    private static final int MAX_STATUS_BYTES = 65536;

    private PodExecTransport() { }

    static PodExecResult execute(String server, String token, SSLContext context, boolean verifyHostname,
                                 int connectTimeoutMs, String namespace,
                                 String podName, PodExecOptions options, String[] command) {
        return execute(server, token, context, verifyHostname, connectTimeoutMs, namespace, podName, options, command, null);
    }

    static PodExecResult execute(String server, String token, SSLContext context, boolean verifyHostname,
                                 int connectTimeoutMs, String namespace, String podName,
                                 PodExecOptions options, String[] command, PodExecOperation operation) {
        validate(namespace, podName, options, command);
        if (Thread.currentThread().isInterrupted()) {
            throw new PodExecException(PodExecException.Reason.INTERRUPTED, new InterruptedException(),
                    new byte[0], new byte[0], "");
        }
        String[] args = command.clone();
        StringBuilder url = new StringBuilder(server.replaceFirst("^http", "ws"));
        if (url.charAt(url.length() - 1) == '/') { url.setLength(url.length() - 1); }
        url.append("/api/v1/namespaces/").append(namespace).append("/pods/").append(podName)
                .append("/exec?stdin=false&stdout=true&stderr=true&tty=false");
        if (options.getContainer() != null) { url.append("&container=").append(options.getContainer()); }
        for (String arg : args) { url.append("&command=").append(encode(arg)); }
        Session session = new Session(options.getMaxOutputBytes());
        if (operation != null) { operation.onPartial(session::partialResult); }
        int connectLimit = connectTimeoutMs == 0 ? options.getTimeoutMs()
                : Math.min(connectTimeoutMs, options.getTimeoutMs());
        ExecutorService connector = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "k8s-pod-exec-connect");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> connecting = null;
        long id = IDS.incrementAndGet();
        long started = System.nanoTime();
        WebSocket socket = null;
        LogSupport.debug(LOG, "Pod exec 开始 execId={} server={} namespace={} pod={} container={} timeoutMs={}",
                id, LogSupport.endpoint(server), namespace, podName, options.getContainer(), options.getTimeoutMs());
        try {
            socket = new WebSocketFactory()
                    .setSocketFactory(new TrackedSocketFactory(SocketFactory.getDefault(), session))
                    .setSSLSocketFactory(new TrackedSocketFactory(context.getSocketFactory(), session))
                    .setVerifyHostname(verifyHostname)
                    .setConnectionTimeout(connectLimit).setSocketTimeout(options.getTimeoutMs())
                    .createSocket(url.toString()).addHeader("Authorization", "Bearer " + token)
                    .addHeader("User-Agent", "iskycc-k8s-tools").addProtocol(V5).addProtocol(V4)
                    .setMissingCloseFrameAllowed(true).addListener(session);
            final WebSocket connection = socket;
            connecting = connector.submit(() -> {
                try {
                    connection.connect();
                } catch (WebSocketException e) { session.connectionFailed(e); }
                catch (RuntimeException e) { session.abort(PodExecException.Reason.TRANSPORT, e); }
                finally { if (session.done.getCount() == 0) { close(connection); } }
            });
            long remaining = TimeUnit.MILLISECONDS.toNanos(options.getTimeoutMs()) - (System.nanoTime() - started);
            if (remaining <= 0 || !session.done.await(remaining, TimeUnit.NANOSECONDS)) {
                session.abort(PodExecException.Reason.TIMEOUT, null);
            }
            PodExecResult result = session.result();
            if (result.isSuccess()) {
                LogSupport.debug(LOG, "Pod exec 完成 execId={} namespace={} pod={} exitCode={} elapsedMs={}",
                        id, namespace, podName, result.getExitCode(), LogSupport.elapsedMs(started));
            } else {
                LOG.warn("Pod exec 命令返回非零状态 execId={} namespace={} pod={} exitCode={} elapsedMs={}",
                        id, namespace, podName, result.getExitCode(), LogSupport.elapsedMs(started));
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.abort(PodExecException.Reason.INTERRUPTED, e);
            LOG.warn("Pod exec 已中断 execId={} namespace={} pod={} elapsedMs={}",
                    id, namespace, podName, LogSupport.elapsedMs(started));
            throw session.exception(PodExecException.Reason.INTERRUPTED, e);
        } catch (IOException e) {
            throw session.exception(PodExecException.Reason.TRANSPORT, e);
        } catch (K8sApiException e) {
            LOG.warn("Pod exec 失败 execId={} namespace={} pod={} status={} failure={} elapsedMs={} errorType={}",
                    id, namespace, podName, e.getStatusCode(),
                    e instanceof PodExecException ? ((PodExecException) e).getFailureReason() : "HTTP",
                    LogSupport.elapsedMs(started), LogSupport.errorType(e));
            throw e;
        } finally {
            // 强制关闭底层 socket；不等待远端 close，且不自动 reconnect。
            session.closeSockets();
            close(socket);
            if (connecting != null) { connecting.cancel(true); }
            connector.shutdownNow();
        }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private static void close(WebSocket websocket) {
        if (websocket == null) { return; }
        Socket socket = websocket.getSocket();
        try { if (socket != null) { socket.close(); } }
        catch (IOException ignored) { /* 已关闭的连接不影响结果。 */ }
        websocket.disconnect(1000, null, 0);
    }

    static void validate(String namespace, String pod, PodExecOptions options, String[] args) {
        if (namespace == null || namespace.length() > 63
                || !namespace.matches("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException("namespace must be a concrete DNS label");
        }
        if (pod == null || pod.length() > 253
                || !pod.matches("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?(?:\\.[a-z0-9](?:[-a-z0-9]*[a-z0-9])?)*")) {
            throw new IllegalArgumentException("podName must be a DNS subdomain");
        }
        if (options == null) { throw new IllegalArgumentException("options is required"); }
        if (args == null || args.length == 0 || args[0] == null || args[0].trim().isEmpty()) {
            throw new IllegalArgumentException("command executable is required");
        }
        for (String arg : args) {
            if (arg == null || arg.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("command arguments must not be null or contain NUL");
            }
        }
    }

    /** 在连接前登记 socket，取消时也关闭正在连接的 socket，防止 DNS 返回过晚后仍执行命令。 */
    private static final class TrackedSocketFactory extends SSLSocketFactory {
        private final SocketFactory delegate;
        private final Session session;
        private TrackedSocketFactory(SocketFactory delegate, Session session) {
            this.delegate = delegate;
            this.session = session;
        }
        @Override public Socket createSocket() throws IOException { return session.track(delegate.createSocket()); }
        @Override public String[] getDefaultCipherSuites() { return ((SSLSocketFactory) delegate).getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return ((SSLSocketFactory) delegate).getSupportedCipherSuites(); }
        // nv-websocket-client 2.14 仅使用无参 createSocket；拒绝先连接再登记的路径。
        @Override public Socket createSocket(String host, int port) throws IOException { throw unsupported(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { throw unsupported(); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { throw unsupported(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { throw unsupported(); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException { throw unsupported(); }
        private IOException unsupported() { return new IOException("Only unconnected sockets are supported"); }
    }

    private static final class Session extends WebSocketAdapter {
        private final List<Socket> sockets = new ArrayList<Socket>();
        private final int maxOutput;
        private final CountDownLatch done = new CountDownLatch(1);
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final ByteArrayOutputStream status = new ByteArrayOutputStream();
        private final boolean[] closed = new boolean[4];
        private String protocol;
        private K8sApiException failure;
        private PodExecResult completed;

        private Session(int maxOutput) { this.maxOutput = maxOutput; }

        private synchronized Socket track(Socket socket) throws IOException {
            if (done.getCount() == 0) {
                socket.close();
                throw new IOException("Pod exec is already complete or cancelled");
            }
            sockets.add(socket);
            return socket;
        }

        private synchronized void closeSockets() {
            for (Socket socket : sockets) {
                try { socket.close(); } catch (IOException ignored) { /* 尽力释放全部连接。 */ }
            }
            sockets.clear();
        }

        @Override public synchronized void onConnected(WebSocket socket, Map<String, List<String>> headers) {
            if (done.getCount() == 0) { close(socket); return; }
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if ("Sec-WebSocket-Protocol".equalsIgnoreCase(entry.getKey()) && entry.getValue().size() == 1) {
                    protocol = entry.getValue().get(0);
                }
            }
            if (!V5.equals(protocol) && !V4.equals(protocol)) {
                abort(PodExecException.Reason.PROTOCOL, null);
                close(socket);
            }
        }

        @Override public synchronized void onTextMessage(WebSocket socket, String text) {
            abort(PodExecException.Reason.PROTOCOL, null);
            close(socket);
        }

        @Override public synchronized void onBinaryMessage(WebSocket socket, byte[] bytes) {
            if (done.getCount() == 0) { return; }
            if (bytes.length == 0) { abort(PodExecException.Reason.PROTOCOL, null); }
            else {
                int channel = bytes[0] & 0xff;
                if (channel == 255 && V5.equals(protocol)) {
                    int stream = bytes.length == 2 ? bytes[1] & 0xff : -1;
                    if (stream < 1 || stream > 3 || closed[stream]) { abort(PodExecException.Reason.PROTOCOL, null); }
                    else { closed[stream] = true; }
                } else if (channel < 1 || channel > 3 || closed[channel]) {
                    abort(PodExecException.Reason.PROTOCOL, null);
                } else {
                    int length = bytes.length - 1;
                    ByteArrayOutputStream target = channel == 1 ? stdout : channel == 2 ? stderr : status;
                    long available = channel == 3 ? MAX_STATUS_BYTES - status.size()
                            : (long) maxOutput - stdout.size() - stderr.size();
                    int accepted = (int) Math.min(available, length);
                    if (accepted > 0) { target.write(bytes, 1, accepted); }
                    if (accepted < length) {
                        abort(channel == 3 ? PodExecException.Reason.PROTOCOL : PodExecException.Reason.OUTPUT_LIMIT, null);
                    }
                }
            }
            if (done.getCount() == 0) { close(socket); }
        }

        @Override public synchronized void onDisconnected(WebSocket socket, WebSocketFrame serverClose,
                                                           WebSocketFrame clientClose, boolean closedByServer) {
            // 旧 kubelet 发出 Status 后直接 TCP EOF；仍必须收到完整合法 Status 才能成功。
            if (serverClose == null || serverClose.getCloseCode() == 1000) { finish(); }
            else { abort(PodExecException.Reason.TRANSPORT, null); }
        }

        @Override public synchronized void onError(WebSocket socket, WebSocketException error) {
            abort(PodExecException.Reason.TRANSPORT, error);
            close(socket);
        }

        private void connectionFailed(WebSocketException error) {
            if (error instanceof OpeningHandshakeException) {
                OpeningHandshakeException response = (OpeningHandshakeException) error;
                int code = response.getStatusLine() == null ? -1 : response.getStatusLine().getStatusCode();
                if (code > 0 && code != 101) {
                    byte[] body = response.getBody();
                    fail(new K8sApiException(new ApiResponse(code,
                            body == null ? "" : new String(body, 0, Math.min(body.length, MAX_STATUS_BYTES), StandardCharsets.UTF_8),
                            response.getHeaders())));
                    return;
                }
                abort(PodExecException.Reason.PROTOCOL, null);
                return;
            }
            abort(PodExecException.Reason.TRANSPORT, error);
        }

        private synchronized void finish() {
            if (done.getCount() == 0) { return; }
            try (JsonReader reader = new JsonReader(new StringReader(new String(status.toByteArray(), StandardCharsets.UTF_8)))) {
                reader.setStrictness(Strictness.STRICT);
                JsonElement parsed = JsonParser.parseReader(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT) { throw new IllegalArgumentException(); }
                if (!parsed.isJsonObject()) { throw new IllegalArgumentException(); }
                JsonObject value = parsed.getAsJsonObject();
                int exit = -1;
                if ("Success".equals(string(value, "status"))) { exit = 0; }
                else if ("Failure".equals(string(value, "status"))) {
                    if (!"NonZeroExitCode".equals(string(value, "reason"))) {
                        abort(PodExecException.Reason.REMOTE_ERROR, null);
                        return;
                    }
                    JsonArray causes = value.getAsJsonObject("details").getAsJsonArray("causes");
                    for (JsonElement item : causes) {
                        JsonObject cause = item.getAsJsonObject();
                        if ("ExitCode".equals(string(cause, "reason"))) {
                            String code = string(cause, "message");
                            if (!code.matches("[0-9]{1,3}")) { throw new IllegalArgumentException(); }
                            exit = Integer.parseInt(code);
                            if (exit < 1 || exit > 255) { throw new IllegalArgumentException(); }
                            break;
                        }
                    }
                }
                if (exit < 0) { throw new IllegalArgumentException(); }
                completed = new PodExecResult(exit, stdout.toByteArray(), stderr.toByteArray());
                done.countDown();
            } catch (RuntimeException | IOException e) {
                // Gson 的错误消息可能包含命令/输出；不将不可信正文放入异常原因链。
                abort(PodExecException.Reason.PROTOCOL, null);
            }
        }

        private static String string(JsonObject object, String key) { return object.get(key).getAsString(); }

        private synchronized void fail(K8sApiException error) {
            if (done.getCount() != 0) { failure = error; done.countDown(); }
        }

        private synchronized PodExecException exception(PodExecException.Reason reason, Throwable cause) {
            return new PodExecException(reason, cause, stdout.toByteArray(), stderr.toByteArray(),
                    new String(status.toByteArray(), StandardCharsets.UTF_8));
        }

        private synchronized PodExecResult partialResult() {
            return new PodExecResult(-1, stdout.toByteArray(), stderr.toByteArray());
        }

        private synchronized void abort(PodExecException.Reason reason, Throwable cause) { fail(exception(reason, cause)); }

        private synchronized PodExecResult result() {
            if (failure != null) { throw failure; }
            if (completed == null) { throw exception(PodExecException.Reason.PROTOCOL, null); }
            return completed;
        }
    }
}

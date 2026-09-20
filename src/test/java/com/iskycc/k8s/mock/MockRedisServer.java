package com.iskycc.k8s.mock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 仅实现缓存测试所需 RESP2 命令，使用真实 Jedis socket，不需要外部 Redis。 */
public final class MockRedisServer implements Closeable {
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
    private final Map<String, String> values = new HashMap<String, String>();
    private final List<String> commands = new ArrayList<String>();
    private volatile String failingCommand;

    public MockRedisServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        workers.submit(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    clients.add(client);
                    workers.submit(() -> serve(client));
                } catch (IOException e) {
                    if (!server.isClosed()) { throw new IllegalStateException(e); }
                }
            }
        });
    }

    public int getPort() { return server.getLocalPort(); }
    public synchronized String get(String key) { return values.get(key); }
    public synchronized void put(String key, String value) { values.put(key, value); }
    public synchronized void remove(String key) { values.remove(key); }
    public synchronized List<String> getCommands() { return new ArrayList<String>(commands); }
    public void failCommand(String name) { failingCommand = name; }

    private void serve(Socket client) {
        try (Socket socket = client; InputStream input = new BufferedInputStream(socket.getInputStream())) {
            OutputStream output = socket.getOutputStream();
            while (!server.isClosed()) {
                int first = input.read();
                if (first == -1) { return; }
                if (first != '*') { throw new IOException("Expected RESP array"); }
                int count = Integer.parseInt(line(input));
                List<String> args = new ArrayList<String>();
                for (int i = 0; i < count; i++) {
                    if (input.read() != '$') { throw new IOException("Expected RESP bulk string"); }
                    int length = Integer.parseInt(line(input));
                    byte[] bytes = new byte[length];
                    int offset = 0;
                    while (offset < length) {
                        int read = input.read(bytes, offset, length - offset);
                        if (read < 0) { throw new EOFException(); }
                        offset += read;
                    }
                    if (input.read() != '\r' || input.read() != '\n') { throw new IOException("Invalid RESP ending"); }
                    args.add(new String(bytes, StandardCharsets.UTF_8));
                }
                respond(args, output);
                output.flush();
            }
        } catch (IOException e) {
            // 客户端关闭或服务停止时结束此连接。
        } finally {
            clients.remove(client);
        }
    }

    private synchronized void respond(List<String> args, OutputStream output) throws IOException {
        String command = args.get(0).toUpperCase(java.util.Locale.ROOT);
        commands.add(command);
        if (command.equals(failingCommand)) {
            write(output, "-ERR simulated Redis failure\r\n");
            return;
        }
        switch (command) {
            case "CLIENT":
            case "SELECT":
            case "AUTH":
                write(output, "+OK\r\n");
                break;
            case "PING":
                write(output, "+PONG\r\n");
                break;
            case "GET":
                bulk(output, values.get(args.get(1)));
                break;
            case "MGET":
                write(output, "*" + (args.size() - 1) + "\r\n");
                for (int i = 1; i < args.size(); i++) { bulk(output, values.get(args.get(i))); }
                break;
            case "SET":
            case "MSET":
                if (args.size() % 2 == 0) { throw new IOException("Invalid SET arguments"); }
                for (int i = 1; i < args.size(); i += 2) { values.put(args.get(i), args.get(i + 1)); }
                write(output, "+OK\r\n");
                break;
            case "DEL":
                int removed = 0;
                for (int i = 1; i < args.size(); i++) { if (values.remove(args.get(i)) != null) { removed++; } }
                write(output, ":" + removed + "\r\n");
                break;
            default:
                write(output, "-ERR unsupported command\r\n");
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != '\r') {
            if (b < 0) { throw new EOFException(); }
            bytes.write(b);
        }
        if (input.read() != '\n') { throw new IOException("Invalid RESP line"); }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void bulk(OutputStream output, String value) throws IOException {
        if (value == null) { write(output, "$-1\r\n"); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        write(output, "$" + bytes.length + "\r\n");
        output.write(bytes);
        write(output, "\r\n");
    }

    private static void write(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        server.close();
        synchronized (clients) {
            for (Socket client : clients) { client.close(); }
        }
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("Redis mock threads did not stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}

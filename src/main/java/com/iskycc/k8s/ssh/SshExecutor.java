package com.iskycc.k8s.ssh;

import com.iskycc.k8s.K8sToolsException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.EnumSet;
import java.util.Set;

/**
 * 基于 Apache MINA SSHD 客户端的 SSH 命令执行器：
 * 连接 k8s master 节点并执行远程命令（如 kubectl）。
 *
 * <p>非线程安全，单实例对应单个 SSH 会话；同一会话可多次 exec。
 * 主机密钥默认全部接受（工具类场景不维护 known_hosts）。
 */
public class SshExecutor implements Closeable {

    private final SshConfig config;
    private SshClient client;
    private ClientSession session;

    public SshExecutor(SshConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    /**
     * 建立 SSH 会话并完成认证（幂等）。
     */
    public synchronized void connect() {
        if (session != null && session.isOpen()) {
            return;
        }
        try {
            client = SshClient.setUpDefaultClient();
            client.start();
            ClientSession s = client
                    .connect(config.getUsername(), config.getHost(), config.getPort())
                    .verify(config.getConnectTimeoutMs())
                    .getSession();
            if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().isEmpty()) {
                for (KeyPair kp : loadKeyPairs()) {
                    s.addPublicKeyIdentity(kp);
                }
            }
            if (config.getPassword() != null) {
                s.addPasswordIdentity(config.getPassword());
            }
            s.auth().verify(config.getConnectTimeoutMs());
            this.session = s;
        } catch (K8sToolsException e) {
            closeQuietly();
            throw e;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            closeQuietly();
            throw new K8sToolsException(
                    "SSH 连接失败 " + config.getUsername() + "@" + config.getHost() + ":"
                            + config.getPort() + " - " + rootMessage(e), e);
        }
    }

    private Iterable<KeyPair> loadKeyPairs() throws IOException, GeneralSecurityException {
        FilePasswordProvider provider = config.getPrivateKeyPassphrase() != null
                ? FilePasswordProvider.of(config.getPrivateKeyPassphrase())
                : FilePasswordProvider.EMPTY;
        try (InputStream in = Files.newInputStream(Paths.get(config.getPrivateKeyPath()))) {
            Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(
                    null, NamedResource.ofName(config.getPrivateKeyPath()), in, provider);
            if (keys == null) {
                throw new K8sToolsException("无法解析 SSH 私钥(格式不支持?): "
                        + config.getPrivateKeyPath());
            }
            return keys;
        }
    }

    /**
     * 执行远程命令并等待完成。
     *
     * @param command   完整命令字符串（由远端 shell 解释）
     * @param timeoutMs 等待超时（毫秒）
     */
    public ExecResult exec(String command, int timeoutMs) {
        connect();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ClientChannel channel = session.createExecChannel(command)) {
            channel.setOut(out);
            channel.setErr(err);
            channel.open().verify(config.getConnectTimeoutMs());

            Set<ClientChannelEvent> events =
                    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeoutMs);
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                throw new K8sToolsException("SSH 命令执行超时(" + timeoutMs + "ms): " + command);
            }
            Integer exit = channel.getExitStatus();
            return new ExecResult(exit == null ? -1 : exit,
                    new String(out.toByteArray(), StandardCharsets.UTF_8),
                    new String(err.toByteArray(), StandardCharsets.UTF_8));
        } catch (K8sToolsException e) {
            throw e;
        } catch (IOException e) {
            throw new K8sToolsException("SSH 命令执行失败: " + command + " - " + rootMessage(e), e);
        }
    }

    /** 使用默认 30s 超时执行命令。 */
    public ExecResult exec(String command) {
        return exec(command, 30000);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    private void closeQuietly() {
        try {
            close();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            session.close(false);
            session = null;
        }
        if (client != null) {
            client.stop();
            client = null;
        }
    }
}

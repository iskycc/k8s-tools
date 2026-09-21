package com.iskycc.k8s.ssh;

import com.iskycc.k8s.K8sToolsException;
import com.iskycc.k8s.internal.LogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
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
import java.util.Arrays;
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

    private static final Logger LOG = LoggerFactory.getLogger(SshExecutor.class);
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
        long started = System.nanoTime();
        String stage = "connect";
        LOG.info("SSH 连接开始 host={} port={} user={} passwordOnly={} timeoutMs={}",
                LogSupport.field(config.getHost()), config.getPort(), LogSupport.field(config.getUsername()),
                config.isPasswordOnly(), config.getConnectTimeoutMs());
        try {
            client = SshClient.setUpDefaultClient();
            if (config.isPasswordOnly()) {
                // 在 start/connect 前关闭所有隐式密钥来源，避免 ~/.ssh/config 中的 IdentityFile
                // 或默认身份加载先于密码认证；只保留密码及单密码交互认证。
                client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
                client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
                client.setAgentFactory(null);
                client.setUserAuthFactories(Arrays.<UserAuthFactory>asList(
                        UserAuthPasswordFactory.INSTANCE, UserAuthKeyboardInteractiveFactory.INSTANCE));
            }
            client.start();
            ClientSession s = client
                    .connect(config.getUsername(), config.getHost(), config.getPort())
                    .verify(config.getConnectTimeoutMs())
                    .getSession();
            if (!config.isPasswordOnly()
                    && config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().isEmpty()) {
                stage = "load-key";
                for (KeyPair kp : loadKeyPairs()) {
                    s.addPublicKeyIdentity(kp);
                }
            }
            if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                s.addPasswordIdentity(config.getPassword());
            }
            stage = "authenticate";
            s.auth().verify(config.getConnectTimeoutMs());
            this.session = s;
            LOG.info("SSH 连接成功 host={} port={} elapsedMs={}",
                    LogSupport.field(config.getHost()), config.getPort(), LogSupport.elapsedMs(started));
        } catch (K8sToolsException e) {
            logConnectFailure(stage, started, e);
            closeQuietly();
            throw e;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            logConnectFailure(stage, started, e);
            closeQuietly();
            throw new K8sToolsException(
                    "SSH 连接失败 " + config.getUsername() + "@" + config.getHost() + ":"
                            + config.getPort() + " - " + rootMessage(e), e);
        }
    }

    private void logConnectFailure(String stage, long started, Exception error) {
        LOG.error("SSH 连接失败 host={} port={} stage={} elapsedMs={} errorType={}",
                LogSupport.field(config.getHost()), config.getPort(), stage,
                LogSupport.elapsedMs(started), LogSupport.errorType(error));
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
        long started = System.nanoTime();
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
            LOG.debug("SSH 命令完成 host={} exitCode={} elapsedMs={} timeoutMs={}",
                    LogSupport.field(config.getHost()), exit == null ? -1 : exit,
                    LogSupport.elapsedMs(started), timeoutMs);
            return new ExecResult(exit == null ? -1 : exit,
                    new String(out.toByteArray(), StandardCharsets.UTF_8),
                    new String(err.toByteArray(), StandardCharsets.UTF_8));
        } catch (K8sToolsException e) {
            LOG.warn("SSH 命令执行超时 host={} elapsedMs={} timeoutMs={} errorType={}",
                    LogSupport.field(config.getHost()), LogSupport.elapsedMs(started), timeoutMs,
                    LogSupport.errorType(e));
            throw e;
        } catch (IOException e) {
            LOG.warn("SSH 命令失败 host={} elapsedMs={} timeoutMs={} errorType={}",
                    LogSupport.field(config.getHost()), LogSupport.elapsedMs(started), timeoutMs,
                    LogSupport.errorType(e));
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

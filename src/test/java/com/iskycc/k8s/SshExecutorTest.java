package com.iskycc.k8s;

import com.iskycc.k8s.ssh.SshConfig;
import com.iskycc.k8s.ssh.SshExecutor;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 用真实 loopback SSH 认证验证密码模式与私钥兼容性，不连接真实主机。 */
public class SshExecutorTest {
    private static final String PASSWORD = "mock-ssh-password";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private SshServer server;
    private Path home;
    private Path privateKey;
    private final AtomicInteger passwordAttempts = new AtomicInteger();
    private final AtomicInteger publicKeyAttempts = new AtomicInteger();

    @Before
    public void startServer() throws Exception {
        home = temporaryFolder.newFolder("home").toPath();
        privateKey = Files.createDirectories(home.resolve(".ssh")).resolve("id_rsa");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        try (OutputStream out = Files.newOutputStream(privateKey)) {
            new OpenSSHKeyPairResourceWriter().writePrivateKey(keyPair, "test key", null, out);
        }
        if (Files.getFileAttributeView(privateKey, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(privateKey.getParent(), PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(privateKey, PosixFilePermissions.fromString("rw-------"));
        }

        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(keyPair));
        server.setPasswordAuthenticator((user, password, session) -> {
            passwordAttempts.incrementAndGet();
            return "test-user".equals(user) && PASSWORD.equals(password);
        });
        server.setPublickeyAuthenticator((user, key, session) -> {
            publicKeyAttempts.incrementAndGet();
            return "test-user".equals(user) && keyPair.getPublic().equals(key);
        });
        server.setUserAuthFactories(Arrays.asList(
                UserAuthPublicKeyFactory.INSTANCE, UserAuthPasswordFactory.INSTANCE));
        server.start();
    }

    @After
    public void stopServer() throws Exception {
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    public void passwordIgnoresUsableDefaultPrivateKey() throws Exception {
        // 先证明默认客户端确实可以自动发现并用这把密钥登录，避免无效测试密钥造成假阳性。
        runWithIsolatedHome("implicit-key");
        assertTrue(publicKeyAttempts.get() > 0);
        assertEquals(0, passwordAttempts.get());
        publicKeyAttempts.set(0);
        runWithIsolatedHome("password");
        assertTrue(passwordAttempts.get() > 0);
        assertEquals("不应尝试可用的默认用户密钥", 0, publicKeyAttempts.get());
    }

    @Test
    public void passwordIgnoresLocalSshConfig() throws Exception {
        Files.write(home.resolve(".ssh/config"), Arrays.asList(
                "Host *", "    HostName 127.0.0.2", "    Port 1",
                "    IdentityFile ~/.ssh/id_rsa", "    IdentitiesOnly yes"), StandardCharsets.UTF_8);
        runWithIsolatedHome("password");
        assertTrue(passwordAttempts.get() > 0);
        assertEquals(0, publicKeyAttempts.get());
    }

    @Test
    public void incorrectPasswordDoesNotFallBackToUsableLocalKey() throws Exception {
        runWithIsolatedHome("wrong-password");
        assertTrue(passwordAttempts.get() > 0);
        assertEquals(0, publicKeyAttempts.get());
    }

    @Test
    public void forcedPasswordIgnoresExplicitUsablePrivateKey() {
        try (SshExecutor executor = new SshExecutor(config().password(PASSWORD)
                .privateKeyPath(privateKey.toString()).passwordOnly(true).build())) {
            executor.connect();
        }
        assertTrue(passwordAttempts.get() > 0);
        assertEquals(0, publicKeyAttempts.get());
    }

    @Test
    public void forcedPasswordDoesNotOpenMissingPrivateKey() {
        try (SshExecutor executor = new SshExecutor(config().password(PASSWORD)
                .privateKeyPath(home.resolve("missing-key").toString())
                .privateKeyPassphrase("unused-test-passphrase").passwordOnly(true).build())) {
            executor.connect();
        }
        assertTrue(passwordAttempts.get() > 0);
        assertEquals(0, publicKeyAttempts.get());
    }

    @Test
    public void passwordSupportsSinglePasswordKeyboardInteractive() {
        server.setUserAuthFactories(Collections.singletonList(UserAuthKeyboardInteractiveFactory.INSTANCE));
        try (SshExecutor executor = new SshExecutor(config().password(PASSWORD).build())) {
            executor.connect();
        }
        assertTrue(passwordAttempts.get() > 0);
        assertEquals(0, publicKeyAttempts.get());
    }

    @Test
    public void explicitPrivateKeyAuthenticationStillWorks() throws Exception {
        runWithIsolatedHome("key");
        assertTrue(publicKeyAttempts.get() > 0);
        assertEquals(0, passwordAttempts.get());
    }

    @Test
    public void mixedCredentialsKeepExistingKeyPreference() throws Exception {
        runWithIsolatedHome("mixed");
        assertTrue(publicKeyAttempts.get() > 0);
        assertEquals(0, passwordAttempts.get());
    }

    @Test
    public void forcedPasswordRequiresNonEmptyPassword() {
        for (String password : new String[]{null, ""}) {
            try {
                config().passwordOnly(true).privateKeyPath(privateKey.toString()).password(password).build();
                fail("passwordOnly 必须有密码，不能回退到私钥");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("password"));
            }
        }
    }

    private SshConfig.Builder config() {
        return SshConfig.builder().host("127.0.0.1").port(server.getPort())
                .username("test-user").connectTimeoutMs(5000);
    }

    private void runWithIsolatedHome(String mode) throws Exception {
        // MINA 缓存默认 .ssh 路径，使用独立 JVM 避免读取真实 home 或污染其他测试。
        Path output = temporaryFolder.newFile().toPath();
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-Duser.home=" + home, "-cp", classpath,
                AuthenticationProbe.class.getName(), String.valueOf(server.getPort()), mode)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue("密码认证子进程应按时结束", process.waitFor(20, TimeUnit.SECONDS));
            String result = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            assertEquals(result, 0, process.exitValue());
            assertFalse("错误输出不应包含密码", result.contains(PASSWORD));
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class AuthenticationProbe {
        private AuthenticationProbe() { }

        public static void main(String[] args) throws Exception {
            if ("implicit-key".equals(args[1])) {
                try (SshClient client = SshClient.setUpDefaultClient()) {
                    client.start();
                    try (ClientSession session = client.connect("test-user", "127.0.0.1", Integer.parseInt(args[0]))
                            .verify(5000).getSession()) {
                        session.auth().verify(5000);
                    }
                }
                return;
            }
            boolean wrongPassword = "wrong-password".equals(args[1]);
            SshConfig.Builder builder = SshConfig.builder().host("127.0.0.1").port(Integer.parseInt(args[0]))
                    .username("test-user").connectTimeoutMs(5000);
            if (!"key".equals(args[1])) {
                builder.password(wrongPassword ? "incorrect-password" : PASSWORD);
            }
            if ("key".equals(args[1]) || "mixed".equals(args[1])) {
                builder.privateKeyPath(Paths.get(System.getProperty("user.home"), ".ssh", "id_rsa").toString());
            }
            try (SshExecutor executor = new SshExecutor(builder.build())) {
                try {
                    executor.connect();
                    if (wrongPassword) {
                        throw new AssertionError("错误密码不应通过本地私钥认证成功");
                    }
                } catch (K8sToolsException e) {
                    if (!wrongPassword) {
                        throw e;
                    }
                    assertNotNull("认证失败应保留原因", e.getCause());
                }
            }
        }
    }
}

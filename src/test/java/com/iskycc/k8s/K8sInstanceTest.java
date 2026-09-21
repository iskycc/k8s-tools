package com.iskycc.k8s;

import com.google.gson.JsonParser;
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.mock.MockK8sApiServer;
import com.iskycc.k8s.mock.MockK8sMasterServer;
import com.iskycc.k8s.mock.MockRedisServer;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class K8sInstanceTest {
    private static final String TOKEN = "instance-token-fixture";
    private static final String SSH_PASSWORD = " ssh-password-@:#%+中 ";
    private static final String REDIS_PASSWORD = " redis-password-@:/#%+中 ";
    private static final String TOKEN_KEY = "127.0.0.1ServiceToken";

    @Test
    public void configurationPreservesPasswordsNormalizesHostsAndForcesPasswordOnly() {
        K8sInstance instance = new K8sInstance(" 192.0.2.10 ", 2222, " root ", SSH_PASSWORD,
                REDIS_PASSWORD, " redis.example.com ", 16379);
        assertEquals("192.0.2.10", instance.getIp()); assertEquals(2222, instance.getPort());
        assertEquals("root", instance.getUsername()); assertEquals(SSH_PASSWORD, instance.getPassword());
        assertEquals(REDIS_PASSWORD, instance.getRedisPassword()); assertEquals("redis.example.com", instance.getRedisIp());
        assertEquals(16379, instance.getRedisPort());
        assertTrue(instance.sshConfig().isPasswordOnly()); assertNull(instance.sshConfig().getPrivateKeyPath());
        assertEquals(SSH_PASSWORD, instance.sshConfig().getPassword());
        URI uri = URI.create(instance.redisUrl());
        assertEquals(":" + REDIS_PASSWORD, uri.getUserInfo()); assertEquals("/0", uri.getPath());
        assertEquals("redis.example.com", uri.getHost()); assertEquals(16379, uri.getPort());
        assertTrue(uri.getRawUserInfo().contains("%2B")); assertTrue(uri.getRawUserInfo().contains("%20"));
        assertFalse(instance.toString().contains(SSH_PASSWORD)); assertFalse(instance.toString().contains(REDIS_PASSWORD));
    }

    @Test
    public void ipv6AndNoPasswordDoNotRequireNetworkOrManualUrlEncoding() {
        for (String host : Arrays.asList("2001:db8::1", "[2001:db8::1]")) {
            K8sInstance instance = new K8sInstance(host, 22, "root", "password", null, host, 6379);
            assertEquals("2001:db8::1", instance.getIp()); assertEquals("2001:db8::1", instance.sshConfig().getHost());
            assertEquals("redis://[2001:db8::1]:6379/0", instance.redisUrl());
            assertNull(URI.create(instance.redisUrl()).getUserInfo());
        }
        K8sInstance noPassword = new K8sInstance("192.0.2.10", 22, "root", "password", "", "127.0.0.1", 6379);
        assertEquals("redis://127.0.0.1:6379/0", noPassword.redisUrl());
    }

    @Test
    public void invalidConfigurationFailsLocallyWithoutEchoingInput() {
        for (String host : Arrays.asList(null, "", " ", "redis://user:secret@host", "user:secret@host",
                "host/path", "host:22", "host?password=secret", "host#secret", "bad host", "bad\nhost", "[broken]")) {
            IllegalArgumentException ssh = assertThrows(IllegalArgumentException.class,
                    () -> new K8sInstance(host, 22, "root", SSH_PASSWORD, REDIS_PASSWORD, "localhost", 6379));
            IllegalArgumentException redis = assertThrows(IllegalArgumentException.class,
                    () -> new K8sInstance("localhost", 22, "root", SSH_PASSWORD, REDIS_PASSWORD, host, 6379));
            for (IllegalArgumentException error : Arrays.asList(ssh, redis)) {
                assertFalse(error.getMessage().contains("secret")); assertNull(error.getCause());
            }
        }
        for (int port : new int[]{-1, 0, 65536}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new K8sInstance("localhost", port, "root", "password", null, "localhost", 6379));
            assertThrows(IllegalArgumentException.class,
                    () -> new K8sInstance("localhost", 22, "root", "password", null, "localhost", port));
        }
        for (String username : Arrays.asList(null, "", " \t")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new K8sInstance("localhost", 22, username, "password", null, "localhost", 6379));
        }
        for (String password : Arrays.asList(null, "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new K8sInstance("localhost", 22, "root", password, null, "localhost", 6379));
        }
        assertThrows(IllegalArgumentException.class, () -> K8sTools.init(null));
        assertThrows(IllegalArgumentException.class, () -> K8sTools.refresh(null));
    }

    @Test
    public void initAuthenticatesDiscoversAndReusesCacheWhileExposingFullClient() throws Exception {
        try (MockRedisServer redis = new MockRedisServer(); MockK8sApiServer api = new MockK8sApiServer(TOKEN);
             MockK8sMasterServer master = master(api, TOKEN)) {
            master.start();
            K8sInstance instance = instance(master, redis, REDIS_PASSWORD);
            K8sApiClient first = K8sTools.init(instance);
            assertEquals(Arrays.asList("AUTH", REDIS_PASSWORD), redis.getAuthArguments());
            assertEquals(TOKEN, redis.get(TOKEN_KEY)); assertEquals(api.getBaseUrl(), first.getApiServer());
            assertDisconnected(redis);
            assertEquals("v1.28.2", first.getVersion().getGitVersion());
            api.enqueueResponse(200, "{\"metadata\":{\"resourceVersion\":\"1\"},\"items\":["
                    + "{\"metadata\":{\"namespace\":\"team\",\"name\":\"web\"},\"spec\":{\"containers\":[{\"name\":\"app\"}]}}]}");
            assertEquals("team", first.searchPods("web").get(0).getNamespace());
            api.enqueueResponse(201, "{\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"settings\"}}");
            first.configMaps("team").create(JsonParser.parseString("{\"metadata\":{\"name\":\"settings\"}}").getAsJsonObject());
            assertEquals("POST", api.getRequests().get(2).method);
            int before = master.getExecutedCommands().size();
            K8sApiClient cached = K8sTools.init(instance);
            assertEquals(before, master.getExecutedCommands().size());
            // /version=1.28：保留 SSH 回退配置。mock 不执行真正的 kubectl exec，返回 127。
            assertEquals(127, cached.exec("team", "web", "date").getExitCode());
            assertEquals(before + 1, master.getExecutedCommands().size());
            master.close();
            assertEquals("v1.28.2", K8sTools.init(instance).getVersion().getGitVersion());
            assertEquals(1, Collections.frequency(redis.getCommands(), "MSET"));
            assertDisconnected(redis);
            redis.close();
            assertEquals("v1.28.2", first.getVersion().getGitVersion());
        }
    }

    @Test
    public void refreshReturnsNewClientAndDoesNotReplayFailedWriteOrChangeOldClient() throws Exception {
        try (MockRedisServer redis = new MockRedisServer(); MockK8sApiServer api = new MockK8sApiServer(TOKEN);
             MockK8sMasterServer master = master(api, TOKEN)) {
            master.start(); K8sInstance instance = instance(master, redis, null);
            K8sTools.init(instance);
            assertFalse(redis.getCommands().contains("AUTH"));
            redis.put(TOKEN_KEY, "expired"); redis.put("192.0.2.20ServiceToken", "other-cluster");
            K8sApiClient old = K8sTools.init(instance);
            int before = master.getExecutedCommands().size();
            assertEquals(401, assertThrows(K8sApiException.class, () -> old.configMaps("team").create(
                    JsonParser.parseString("{\"metadata\":{\"name\":\"settings\"}}").getAsJsonObject())).getStatusCode());
            assertEquals(1, api.getRequestCount()); assertEquals(before, master.getExecutedCommands().size());
            K8sApiClient fresh = K8sTools.refresh(instance);
            assertEquals(1, api.getRequestCount()); assertTrue(master.getExecutedCommands().size() > before);
            assertEquals(TOKEN, redis.get(TOKEN_KEY)); assertEquals("other-cluster", redis.get("192.0.2.20ServiceToken"));
            assertEquals(1, Collections.frequency(redis.getCommands(), "DEL"));
            assertEquals("v1.28.2", fresh.getVersion().getGitVersion());
            assertEquals(401, assertThrows(K8sApiException.class, old::getVersion).getStatusCode());
            assertDisconnected(redis);
        }
    }

    @Test
    public void optionsAreForwardedWithoutChangingCallerAndClustersDoNotShareGlobalClient() throws Exception {
        try (MockRedisServer redisA = new MockRedisServer(); MockRedisServer redisB = new MockRedisServer();
             MockK8sApiServer apiA = new MockK8sApiServer(TOKEN); MockK8sApiServer apiB = new MockK8sApiServer("second-token");
             MockK8sMasterServer masterA = master(apiA, TOKEN); MockK8sMasterServer masterB = master(apiB, "second-token")) {
            masterA.start(); masterB.start();
            ServiceTokenFetcher.Options options = new ServiceTokenFetcher.Options().clusterRole("view");
            K8sApiClient a = K8sTools.init(instance(masterA, redisA, null), options);
            K8sApiClient b = K8sTools.init(instance(masterB, redisB, null));
            assertEquals(apiA.getBaseUrl(), a.getApiServer()); assertEquals(apiB.getBaseUrl(), b.getApiServer());
            assertEquals("v1.28.2", a.getVersion().getGitVersion()); assertEquals("v1.28.2", b.getVersion().getGitVersion());
            assertEquals(TOKEN, apiA.getLastAuthorization().substring("Bearer ".length()));
            assertEquals("Bearer second-token", apiB.getLastAuthorization());
            assertTrue(masterA.getExecutedCommands().stream().anyMatch(command -> command.contains("--clusterrole=view")));
            K8sTools.refresh(instance(masterA, redisA, null), options);
            // 原 Options 未被注入内部已关闭的缓存池，可继续走无 Redis 的旧入口。
            int commands = redisA.getCommands().size();
            assertEquals(apiA.getBaseUrl(), K8sApiClient.fromSsh(instance(masterA, redisA, null).sshConfig(), options).getApiServer());
            assertEquals(commands, redisA.getCommands().size());
            assertDisconnected(redisA); assertDisconnected(redisB);
        }
    }

    @Test
    public void redisAndSshFailuresRemainVisibleAndCloseConnections() throws Exception {
        try (MockRedisServer redis = new MockRedisServer(); MockK8sApiServer api = new MockK8sApiServer(TOKEN);
             MockK8sMasterServer master = master(api, TOKEN)) {
            master.start(); K8sInstance instance = instance(master, redis, REDIS_PASSWORD);
            redis.failCommand("AUTH");
            K8sToolsException error = assertThrows(K8sToolsException.class, () -> K8sTools.init(instance));
            assertNotNull(error.getCause()); assertTrue(master.getExecutedCommands().isEmpty());
            assertDisconnected(redis); redis.failCommand(null);
            K8sTools.init(instance);
            K8sInstance bad = new K8sInstance("127.0.0.1", master.getPort(), "root", "wrong-password", REDIS_PASSWORD,
                    "127.0.0.1", redis.getPort());
            assertThrows(K8sToolsException.class, () -> K8sTools.refresh(bad));
            assertNull(redis.get(TOKEN_KEY)); assertNull(redis.get("127.0.0.1ApiServerUrl"));
            assertDisconnected(redis);
        }
    }

    @Test
    public void initializationLogsNeverPrintRawOrEncodedPasswords() throws Exception {
        PrintStream previous = System.err; boolean debug = K8sLogging.isDebugEnabled();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, "UTF-8");
             MockRedisServer redis = new MockRedisServer(); MockK8sApiServer api = new MockK8sApiServer(TOKEN);
             MockK8sMasterServer master = master(api, TOKEN)) {
            System.setErr(capture); K8sLogging.setDebugEnabled(true); master.start();
            K8sTools.init(instance(master, redis, REDIS_PASSWORD));
            redis.failCommand("AUTH");
            assertThrows(K8sToolsException.class, () -> K8sTools.init(instance(master, redis, REDIS_PASSWORD)));
        } finally { System.setErr(previous); K8sLogging.setDebugEnabled(debug); }
        String logs = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(logs.contains("API 客户端就绪")); assertTrue(logs.contains("SSH 客户端初始化失败"));
        for (String value : Arrays.asList(SSH_PASSWORD, REDIS_PASSWORD, TOKEN, "redis-password-", "ssh-password-")) {
            assertFalse(logs.contains(value));
        }
    }

    private static MockK8sMasterServer master(MockK8sApiServer api, String token) {
        return new MockK8sMasterServer(MockK8sMasterServer.SaScenario.LEGACY_AUTO_SECRET,
                "root", SSH_PASSWORD, api.getBaseUrl(), token, api.getCaCertPem());
    }

    private static K8sInstance instance(MockK8sMasterServer master, MockRedisServer redis, String password) {
        return new K8sInstance("127.0.0.1", master.getPort(), "root", SSH_PASSWORD, password, "127.0.0.1", redis.getPort());
    }

    private static void assertDisconnected(MockRedisServer redis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (redis.getConnectedClientCount() != 0 && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertEquals(0, redis.getConnectedClientCount());
    }
}

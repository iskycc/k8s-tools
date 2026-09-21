package com.iskycc.k8s;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.mock.MockK8sApiServer;
import com.iskycc.k8s.mock.MockK8sMasterServer;
import com.iskycc.k8s.mock.MockRedisServer;
import com.iskycc.k8s.mock.CertUtil;
import com.iskycc.k8s.ssh.MasterInfo;
import com.iskycc.k8s.ssh.RedisServiceTokenCache;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;
import com.iskycc.k8s.ssh.SshConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Collections;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RedisCredentialsTest {
    private static final String TOKEN = "new-cluster-token-for-test";
    private static final String TOKEN_KEY = "127.0.0.1ServiceToken";
    private static final String URL_KEY = "127.0.0.1ApiServerUrl";
    private MockRedisServer redis;
    private JedisPool pool;
    private MockK8sApiServer api;
    private MockK8sMasterServer master;
    private SshConfig config;

    @Before
    public void start() throws Exception {
        redis = new MockRedisServer();
        pool = new JedisPool("127.0.0.1", redis.getPort());
        api = new MockK8sApiServer(TOKEN);
        master = new MockK8sMasterServer(MockK8sMasterServer.SaScenario.LEGACY_AUTO_SECRET,
                "root", "example-password", api.getBaseUrl(), TOKEN, api.getCaCertPem());
        master.start();
        config = SshConfig.builder().host("127.0.0.1").port(master.getPort())
                .username("root").password("example-password").build();
    }

    @After
    public void stop() throws Exception {
        if (master != null) { master.close(); }
        if (api != null) { api.close(); }
        if (pool != null) { pool.close(); }
        if (redis != null) { redis.close(); }
    }

    private ServiceTokenFetcher.Options options() {
        return new ServiceTokenFetcher.Options().redisCache(new RedisServiceTokenCache(pool))
                .tokenWaitIntervalMs(1);
    }

    private ServiceTokenFetcher fetcher() {
        return new ServiceTokenFetcher(config, options());
    }

    private K8sApiClient.Builder managedClient() {
        return K8sApiClient.builder().redisUrl("redis://127.0.0.1:" + redis.getPort() + "/0");
    }

    private void assertRedisDisconnected() throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (redis.getConnectedClientCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("客户端管理的 Redis socket 应已关闭", 0, redis.getConnectedClientCount());
    }

    @Test
    public void clientManagesRedisAndReusesCacheAcrossInstances() throws Exception {
        K8sApiClient.Builder builder = managedClient();
        K8sApiClient first = builder.fromSsh(config);
        assertEquals(TOKEN, redis.get(TOKEN_KEY));
        assertEquals(api.getBaseUrl(), redis.get(URL_KEY));
        assertRedisDisconnected();
        master.close();
        assertEquals("v1.28.2", builder.fromSsh(config).getVersion().getGitVersion());
        assertEquals("v1.28.2", managedClient().fromSsh(config).getVersion().getGitVersion());
        assertEquals(1, Collections.frequency(redis.getCommands(), "MSET"));
        assertRedisDisconnected();
        redis.close(); // 构造完成后 HTTP 查询不依赖 Redis 的存活。
        assertEquals("v1.28.2", first.getVersion().getGitVersion());
    }

    @Test
    public void redisHitRetainsSshConfigForOldClusterExec() throws Exception {
        managedClient().fromSsh(config);
        int before = master.getExecutedCommands().size();
        K8sApiClient cached = managedClient().fromSsh(config);
        assertEquals(before, master.getExecutedCommands().size());
        // mock /version 为 1.28：应选 SSH；模拟 master 没有 kubectl exec 处理器，明确返回 127。
        api.enqueueResponse(200, "{\"items\":[{\"metadata\":{\"namespace\":\"default\",\"name\":\"pod\"},"
                + "\"spec\":{\"containers\":[{\"name\":\"app\"}]}}]}");
        assertEquals(127, cached.searchPodsDetailed("pod").get(0).exec("date").getExitCode());
        assertEquals(before + 1, master.getExecutedCommands().size());
        assertTrue(master.getExecutedCommands().get(before).startsWith("set -eu\numask 077\n"));
        assertRedisDisconnected();
        K8sApiClient staticClient = K8sApiClient.fromSsh(config, options());
        assertEquals(127, staticClient.exec("default", "pod", "date").getExitCode());
    }

    @Test
    public void managedRefreshReplacesCredentialsWithoutReplayingFailedWrite() throws Exception {
        managedClient().fromSsh(config);
        redis.put(TOKEN_KEY, "expired-token");
        redis.put("192.0.2.20ServiceToken", "another-master");
        int sshCommands = master.getExecutedCommands().size();
        K8sApiClient expired = managedClient().fromSsh(config);
        K8sApiException error = assertThrows(K8sApiException.class,
                () -> expired.configMaps("default").create(JsonParser.parseString(
                        "{\"metadata\":{\"name\":\"demo\"}}").getAsJsonObject()));
        assertEquals(401, error.getStatusCode());
        assertEquals(1, api.getRequestCount());
        assertEquals(sshCommands, master.getExecutedCommands().size());
        K8sApiClient fresh = managedClient().refreshCache(true).fromSsh(config);
        assertEquals(1, api.getRequestCount());
        assertEquals(TOKEN, redis.get(TOKEN_KEY));
        assertEquals(1, Collections.frequency(redis.getCommands(), "DEL"));
        assertEquals("another-master", redis.get("192.0.2.20ServiceToken"));
        assertTrue(master.getExecutedCommands().size() > sshCommands);
        assertEquals("v1.28.2", fresh.getVersion().getGitVersion());
        assertRedisDisconnected();
    }

    @Test
    public void managedClientClosesConnectionsOnRedisAndSshFailure() throws Exception {
        for (String command : new String[]{"MGET", "MSET", "DEL"}) {
            redis.failCommand(command);
            K8sToolsException error = assertThrows(K8sToolsException.class,
                    () -> managedClient().refreshCache("DEL".equals(command)).fromSsh(config));
            assertNotNull(error.getCause());
            assertRedisDisconnected();
            redis.failCommand(null);
        }
        managedClient().fromSsh(config);
        SshConfig bad = SshConfig.builder().host("127.0.0.1").port(master.getPort())
                .username("root").password("wrong-password").build();
        assertThrows(K8sToolsException.class, () -> managedClient().refreshCache(true).fromSsh(bad));
        assertNull(redis.get(TOKEN_KEY));
        assertNull(redis.get(URL_KEY));
        assertRedisDisconnected();
    }

    @Test
    public void managedClientCopiesOptionsAndDoesNotCloseExternalPool() throws Exception {
        try (MockRedisServer externalRedis = new MockRedisServer();
                JedisPool externalPool = new JedisPool("127.0.0.1", externalRedis.getPort())) {
            ServiceTokenFetcher.Options options = new ServiceTokenFetcher.Options()
                    .redisCache(new RedisServiceTokenCache(externalPool)).clusterRole("view")
                    .apiServerOverride(api.getBaseUrl()).tokenWaitIntervalMs(1);
            managedClient().fromSsh(config, options);
            assertTrue(externalRedis.getCommands().isEmpty());
            assertRedisDisconnected();
            int commands = master.getExecutedCommands().size();
            managedClient().fromSsh(config, options);
            assertEquals(commands, master.getExecutedCommands().size());
            // 原配置未被注入已关闭的内部池，仍写入调用方的独立 Redis。
            assertEquals("v1.28.2", K8sApiClient.fromSsh(config, options).getVersion().getGitVersion());
            assertEquals(TOKEN, externalRedis.get(TOKEN_KEY));
            try (Jedis jedis = externalPool.getResource()) { assertEquals("PONG", jedis.ping()); }
        }
        // 未配置 Redis 时不访问 Redis，默认仍能通过 SSH 获取。
        int redisCommands = redis.getCommands().size();
        assertEquals("v1.28.2", K8sApiClient.builder().fromSsh(config).getVersion().getGitVersion());
        assertEquals(redisCommands, redis.getCommands().size());
    }

    @Test
    public void managedClientHonorsStrictTlsAndDefaultFirstWrite() throws Exception {
        assertEquals("v1.28.2", managedClient().insecureSkipTlsVerify(false).tlsAutoFallback(false)
                .fromSsh(config).getVersion().getGitVersion());
        JsonObject metadata = JsonParser.parseString(redis.get("127.0.0.1ServiceTokenMetadata")).getAsJsonObject();
        metadata.addProperty("caCertPem", CertUtil.generateSelfSigned("wrong-ca",
                new String[]{"localhost"}, new String[]{"127.0.0.1"}).getPem());
        redis.put("127.0.0.1ServiceTokenMetadata", metadata.toString());
        K8sApiClient strict = managedClient().insecureSkipTlsVerify(false).tlsAutoFallback(false).fromSsh(config);
        assertEquals(-1, assertThrows(K8sApiException.class, strict::getVersion).getStatusCode());
        assertFalse(strict.isDegradedToInsecure());
        K8sApiClient permissive = managedClient().fromSsh(config);
        api.enqueueResponse(201, "{\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"managed-demo\"}}");
        permissive.configMaps("default").create(JsonParser.parseString(
                "{\"metadata\":{\"name\":\"managed-demo\"}}").getAsJsonObject());
        assertFalse(permissive.isDegradedToInsecure());
        assertRedisDisconnected();
    }

    @Test
    public void managedRedisUrlSendsPasswordAclAndDecodedCredentials() throws Exception {
        String[][] cases = {
                {":example-password", "", "example-password"},
                {"app-user:example-password", "app-user", "example-password"},
                {":p%40ss%3Aword%2F%23%25%2B%20%E4%B8%AD", "", "p@ss:word/#%+ 中"}
        };
        for (String[] item : cases) {
            K8sApiClient client = K8sApiClient.builder()
                    .redisUrl("redis://" + item[0] + "@127.0.0.1:" + redis.getPort() + "/2")
                    .fromSsh(config);
            assertEquals("v1.28.2", client.getVersion().getGitVersion());
            assertEquals(item[1].isEmpty() ? Arrays.asList("AUTH", item[2])
                    : Arrays.asList("AUTH", item[1], item[2]), redis.getAuthArguments());
            assertEquals(2, redis.getSelectedDatabase());
            assertRedisDisconnected();
        }
        int sshCommands = master.getExecutedCommands().size();
        redis.failCommand("AUTH");
        K8sToolsException error = assertThrows(K8sToolsException.class, () -> K8sApiClient.builder()
                .redisUrl("redis://:example-password@127.0.0.1:" + redis.getPort() + "/2").fromSsh(config));
        assertNotNull(error.getCause());
        assertFalse(error.getMessage().contains("example-password"));
        assertEquals(sshCommands, master.getExecutedCommands().size());
        assertRedisDisconnected();
    }

    @Test
    public void managedConfigurationFailsBeforeNetworkAndDoesNotEchoRedisCredentials() {
        for (String url : new String[]{"redis://user:secret value@127.0.0.1", "https://user:secret@127.0.0.1",
                "redis://secret@127.0.0.1:6379/0", "redis://127.0.0.1/-1",
                "redis://127.0.0.1:65536", "redis://127.0.0.1/0?password=secret"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> K8sApiClient.builder().redisUrl(url).fromSsh(config));
            assertFalse(error.getMessage().contains("secret"));
            assertNull(error.getCause());
        }
        assertThrows(IllegalArgumentException.class,
                () -> K8sApiClient.builder().refreshCache(true).fromSsh(config));
        assertThrows(IllegalArgumentException.class, () -> managedClient().connectTimeoutMs(-1).fromSsh(config));
        assertThrows(IllegalArgumentException.class, () -> managedClient().fromSsh(null));
        assertThrows(IllegalArgumentException.class, () -> managedClient().token("explicit-token").fromSsh(config));
        assertThrows(IllegalArgumentException.class, () -> managedClient().build());
        assertTrue(redis.getCommands().isEmpty());
        assertTrue(master.getExecutedCommands().isEmpty());
    }

    @Test
    public void storesStringsAtomicallyAndNewInstancesReuseCacheWithoutSsh() throws Exception {
        MasterInfo first = fetcher().fetch();
        assertEquals(TOKEN, redis.get(TOKEN_KEY));
        assertEquals(api.getBaseUrl(), redis.get(URL_KEY));
        assertEquals(1, Collections.frequency(redis.getCommands(), "MSET"));
        assertEquals(0, Collections.frequency(redis.getCommands(), "SET"));
        int commands = master.getExecutedCommands().size();
        master.close(); // 模拟再次启动时 SSH 不可用，缓存仍应可用。
        MasterInfo cached = fetcher().fetch();
        assertEquals(first.getToken(), cached.getToken());
        assertEquals(first.getCaCertPem(), cached.getCaCertPem());
        assertEquals(first.getServiceAccount(), cached.getServiceAccount());
        assertEquals(first.getApiServerUrl(), cached.getApiServerUrl());
        assertEquals(commands, master.getExecutedCommands().size());
        assertEquals("v1.28.2", K8sApiClient.fromSsh(config, options()).getVersion().getGitVersion());
        assertEquals(0, pool.getNumActive());
        try (Jedis jedis = pool.getResource()) { assertEquals("PONG", jedis.ping()); }
    }

    @Test
    public void refreshAfter401DeletesBothValuesAndFetchesAgainWithoutReplayingWrites() {
        fetcher().fetch();
        redis.put(TOKEN_KEY, "expired-token");
        K8sApiClient client = K8sApiClient.fromSsh(config, options());
        int commands = master.getExecutedCommands().size();
        K8sApiException error = assertThrows(K8sApiException.class,
                () -> client.configMaps("default").create(JsonParser.parseString(
                        "{\"metadata\":{\"name\":\"demo\"}}").getAsJsonObject()));
        assertEquals(401, error.getStatusCode());
        assertEquals(1, api.getRequestCount());
        assertEquals(commands, master.getExecutedCommands().size());
        MasterInfo fresh = fetcher().refresh();
        assertEquals(TOKEN, fresh.getToken());
        assertEquals(TOKEN, redis.get(TOKEN_KEY));
        assertTrue(master.getExecutedCommands().size() > commands);
        assertEquals(1, Collections.frequency(redis.getCommands(), "DEL"));
        assertEquals("v1.28.2", K8sApiClient.fromSsh(config, options()).getVersion().getGitVersion());
    }

    @Test
    public void invalidAddressAndIncompleteOrCorruptedEntriesRefetch() {
        fetcher().fetch();
        for (String key : new String[]{TOKEN_KEY, URL_KEY, "127.0.0.1ServiceTokenMetadata"}) {
            int commands = master.getExecutedCommands().size();
            redis.remove(key);
            assertEquals(TOKEN, fetcher().fetch().getToken());
            assertTrue(master.getExecutedCommands().size() > commands);
        }
        redis.put(URL_KEY, "not-an-api-url");
        assertEquals(api.getBaseUrl(), fetcher().fetch().getApiServerUrl());
        redis.put("127.0.0.1ServiceTokenMetadata", "invalid-json");
        assertEquals(TOKEN, fetcher().fetch().getToken());
    }

    @Test
    public void changedOptionsDoNotReuseCredentialsForAnotherIdentity() {
        fetcher().fetch();
        int commands = master.getExecutedCommands().size();
        MasterInfo changed = new ServiceTokenFetcher(config, options()
                .clusterRole("view").apiServerOverride("https://192.0.2.11:7443")).fetch();
        assertEquals("https://192.0.2.11:7443", changed.getApiServerUrl());
        assertTrue(master.getExecutedCommands().size() > commands);
    }

    @Test
    public void explicitInvalidationDeletesOnlyThisMasterAndFailedRefreshLeavesNoStalePair() {
        fetcher().fetch();
        redis.put("192.0.2.20ServiceToken", "another-token");
        redis.put("192.0.2.20ApiServerUrl", "https://192.0.2.20:6443");
        fetcher().invalidateCache();
        assertNull(redis.get(TOKEN_KEY));
        assertNull(redis.get(URL_KEY));
        assertNull(redis.get("127.0.0.1ServiceTokenMetadata"));
        assertEquals("another-token", redis.get("192.0.2.20ServiceToken"));
        fetcher().fetch();
        SshConfig bad = SshConfig.builder().host("127.0.0.1").port(master.getPort())
                .username("root").password("wrong-password").build();
        assertThrows(K8sToolsException.class, () -> new ServiceTokenFetcher(bad, options()).refresh());
        assertNull(redis.get(TOKEN_KEY));
        assertNull(redis.get(URL_KEY));
    }

    @Test
    public void redisFailuresAreVisibleAndConnectionsAreReturned() {
        for (String command : new String[]{"MGET", "MSET", "DEL"}) {
            redis.failCommand(command);
            K8sToolsException e = assertThrows(K8sToolsException.class,
                    () -> { if ("DEL".equals(command)) { fetcher().invalidateCache(); } else { fetcher().fetch(); } });
            assertNotNull(e.getCause());
            assertFalse(e.getMessage().contains(TOKEN));
            assertEquals(0, pool.getNumActive());
            redis.failCommand(null);
        }
    }

    @Test
    public void fromSshAllowsFirstWriteWithWrongCaWithoutTlsFallback() throws Exception {
        ServiceTokenFetcher.Options options = options();
        fetcher().fetch();
        JsonObject metadata = JsonParser.parseString(redis.get("127.0.0.1ServiceTokenMetadata")).getAsJsonObject();
        metadata.addProperty("caCertPem", CertUtil.generateSelfSigned("wrong-ca",
                new String[]{"localhost"}, new String[]{"127.0.0.1"}).getPem());
        redis.put("127.0.0.1ServiceTokenMetadata", metadata.toString());
        K8sApiClient client = K8sApiClient.fromSsh(config, options);
        api.enqueueResponse(201, "{\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"demo\"}}");
        assertEquals("ConfigMap", client.configMaps("default").create(JsonParser.parseString(
                "{\"metadata\":{\"name\":\"demo\"}}").getAsJsonObject()).get("kind").getAsString());
        assertEquals(1, api.getRequestCount());
        assertFalse(client.isDegradedToInsecure());
    }
}

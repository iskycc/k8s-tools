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

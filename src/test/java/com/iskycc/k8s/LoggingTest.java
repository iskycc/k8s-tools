package com.iskycc.k8s;

import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.internal.LogSupport;
import com.iskycc.k8s.mock.MockK8sApiServer;
import com.iskycc.k8s.mock.MockK8sMasterServer;
import com.iskycc.k8s.mock.MockRedisServer;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;
import com.iskycc.k8s.ssh.SshConfig;
import com.iskycc.k8s.ssh.SshExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 验证真实日志输出可定位故障，且正常/失败路径均不输出敏感数据。 */
public class LoggingTest {
    private static final String TOKEN = "sensitive-bearer-token-fixture";
    private static final String PASSWORD = "sensitive-ssh-password-fixture";
    private static final String BODY = "sensitive-secret-body-fixture";
    private static final String AUDIT = "ab000000-1234-4321-abcd-123456789012";
    private PrintStream previousError;
    private PrintStream capture;
    private ByteArrayOutputStream output;
    private boolean previousDebugEnabled;

    @Before
    public void captureLogs() throws Exception {
        previousDebugEnabled = K8sLogging.isDebugEnabled();
        K8sLogging.setDebugEnabled(true);
        previousError = System.err;
        output = new ByteArrayOutputStream();
        capture = new PrintStream(output, true, "UTF-8");
        System.setErr(capture);
    }

    @After
    public void restoreOutput() {
        K8sLogging.setDebugEnabled(previousDebugEnabled);
        System.setErr(previousError);
        capture.close();
    }

    @Test
    public void apiLogsStatusTimingAndAuditWithoutBodiesHeadersOrQuery() throws Exception {
        try (MockK8sApiServer api = new MockK8sApiServer(TOKEN)) {
            K8sApiClient client = K8sApiClient.builder().apiServer(api.getBaseUrl()).token(TOKEN)
                    .insecureSkipTlsVerify(true).build();
            api.enqueueResponse(200, BODY, Collections.singletonMap("Audit-Id", AUDIT));
            assertEquals(BODY, client.request("POST", "/api/v1/namespaces/default/secrets?token=" + TOKEN,
                    Collections.singletonMap("password", PASSWORD), BODY, "application/json").getBody());
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Audit-Id", AUDIT);
            headers.put("X-Private", PASSWORD);
            api.enqueueResponse(403, BODY, headers);
            assertEquals(BODY, assertThrows(K8sApiException.class,
                    () -> client.getRaw("/api/v1/nodes?token=" + TOKEN)).getResponseBody());
            api.enqueueResponse(404, BODY, Collections.singletonMap("Audit-Id", TOKEN));
            assertThrows(K8sApiException.class, () -> client.getRaw("/missing"));
            assertEquals("日志不能引入业务请求重放", 3, api.getRequestCount());
        }
        String logs = logs();
        assertTrue(logs.contains("API 请求开始 requestId="));
        assertTrue(logs.contains("method=POST"));
        assertTrue(logs.contains("path=/api/v1/namespaces/default/secrets status=200"));
        assertTrue(logs.contains("status=403 auditId=" + AUDIT));
        assertTrue(logs.contains("status=404 auditId=-"));
        assertTrue(logs.contains("elapsedMs="));
        assertFalse(logs.contains("?token="));
        assertSafe(logs);
    }

    @Test
    public void sshRedisLogsCacheReuseRefreshAndFailureStagesWithoutCredentials() throws Exception {
        try (MockRedisServer redis = new MockRedisServer();
             MockK8sApiServer api = new MockK8sApiServer(TOKEN);
             MockK8sMasterServer master = new MockK8sMasterServer(MockK8sMasterServer.SaScenario.FRESH_CLUSTER,
                     "root", PASSWORD, api.getBaseUrl(), TOKEN, api.getCaCertPem())) {
            master.start();
            SshConfig ssh = SshConfig.builder().host("127.0.0.1").port(master.getPort())
                    .username("root").password(PASSWORD).passwordOnly(true).build();
            String redisUrl = "redis://127.0.0.1:" + redis.getPort() + "/0";
            ServiceTokenFetcher.Options options = new ServiceTokenFetcher.Options().tokenWaitIntervalMs(1);
            K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh, options);
            K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh, options);
            K8sApiClient.builder().redisUrl(redisUrl).refreshCache(true).fromSsh(ssh, options);
            // 远端可能在 stdout/stderr 中回显命令；日志不得记录原始命令或输出。
            try (SshExecutor executor = new SshExecutor(ssh)) {
                executor.exec("echo " + BODY + " " + PASSWORD);
            }
            redis.failCommand("MGET");
            assertThrows(K8sToolsException.class,
                    () -> K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh, options));
            redis.failCommand(null);
            SshConfig invalid = SshConfig.builder().host("127.0.0.1").port(master.getPort())
                    .username("root").password("wrong-" + PASSWORD).passwordOnly(true).build();
            assertThrows(K8sToolsException.class,
                    () -> K8sApiClient.builder().redisUrl(redisUrl).refreshCache(true).fromSsh(invalid, options));
        }
        String logs = logs();
        assertTrue(logs.contains("SSH 连接成功"));
        assertTrue(logs.contains("SSH 命令完成"));
        assertTrue(logs.contains("source=kubeconfig"));
        assertTrue(logs.contains("source=ssh"));
        assertTrue(logs.contains("source=redis"));
        assertTrue(logs.contains("Redis 凭据已删除"));
        assertTrue(logs.contains("stage=cache.read"));
        assertTrue(logs.contains("stage=authenticate"));
        assertTrue(logs.contains("stage=ssh.connect"));
        assertTrue(logs.contains("attempt="));
        assertSafe(logs);
        assertFalse(logs.contains("BEGIN CERTIFICATE"));
    }

    @Test
    public void tlsFailureAndFallbackAreVisibleWithoutChangingRetryPolicy() throws Exception {
        try (MockK8sApiServer api = new MockK8sApiServer(TOKEN)) {
            K8sApiClient fallback = K8sApiClient.builder().apiServer(api.getBaseUrl()).token(TOKEN).build();
            fallback.getVersion();
            assertTrue(fallback.isDegradedToInsecure());
            K8sApiClient strict = K8sApiClient.builder().apiServer(api.getBaseUrl()).token(TOKEN)
                    .insecureSkipTlsVerify(false).tlsAutoFallback(false).build();
            assertEquals(-1, assertThrows(K8sApiException.class, strict::getVersion).getStatusCode());
            assertFalse(strict.isDegradedToInsecure());
        }
        assertTrue(logs().contains("TLS 校验失败，自动降级并重试"));
        assertTrue(logs().contains("status=-1"));
        assertSafe(logs());
    }

    @Test
    public void diagnosticFieldsRejectCredentialUrlsAndForgedLines() {
        assertEquals("redis://127.0.0.1:6379", LogSupport.endpoint(
                "redis://user:" + PASSWORD + "@127.0.0.1:6379/0?token=" + TOKEN));
        assertEquals("-", LogSupport.endpoint("redis://bad " + PASSWORD));
        assertEquals("host__FORGED", LogSupport.field("host\r\nFORGED"));
        RuntimeException cause = new RuntimeException(BODY);
        SshConfig ssh = SshConfig.builder().host("127.0.0.1").password(PASSWORD).build();
        SshExecutor failing = new SshExecutor(ssh) {
            @Override public void connect() { throw cause; }
        };
        assertEquals(cause, assertThrows(RuntimeException.class,
                () -> new ServiceTokenFetcher(ssh, null, failing).fetch()));
        assertTrue(logs().contains("stage=ssh.connect"));
        assertTrue(logs().contains("errorType=RuntimeException"));
        assertSafe(logs());
    }

    @Test
    public void productionSwitchSuppressesAllComponentsDebugButKeepsNecessaryLogs() throws Exception {
        // 测试 provider 已开启本库 DEBUG；验证开关自身能禁止输出，而非依靠 provider 过滤。
        K8sLogging.setDebugEnabled(false);
        try (MockRedisServer redis = new MockRedisServer();
             MockK8sApiServer api = new MockK8sApiServer(TOKEN);
             MockK8sMasterServer master = new MockK8sMasterServer(MockK8sMasterServer.SaScenario.FRESH_CLUSTER,
                     "root", PASSWORD, api.getBaseUrl(), TOKEN, api.getCaCertPem())) {
            master.start();
            SshConfig ssh = SshConfig.builder().host("127.0.0.1").port(master.getPort())
                    .username("root").password(PASSWORD).passwordOnly(true).build();
            String redisUrl = "redis://127.0.0.1:" + redis.getPort() + "/0";
            ServiceTokenFetcher.Options options = new ServiceTokenFetcher.Options().tokenWaitIntervalMs(1);
            K8sApiClient client = K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh, options);
            K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh, options);
            client.getVersion();
            api.enqueueResponse(403, BODY);
            assertThrows(K8sApiException.class, client::listNodes);
            redis.failCommand("MGET");
            assertThrows(K8sToolsException.class,
                    () -> K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh, options));
            assertFalse(logs().contains("DEBUG com.iskycc.k8s"));
            assertFalse(logs().contains("SSH 命令完成"));
            assertFalse(logs().contains("API 请求开始"));
            assertFalse(logs().contains("等待 token Secret"));
            assertTrue(logs().contains("SSH 连接成功"));
            assertTrue(logs().contains("source=redis"));
            assertTrue(logs().contains("WARN com.iskycc.k8s.api.K8sApiClient"));
            assertTrue(logs().contains("status=403"));
            assertTrue(logs().contains("ERROR com.iskycc.k8s.ssh.RedisServiceTokenCache"));
            assertTrue(logs().contains("stage=cache.read"));
            assertSafe(logs());

            // 运行时切换立即作用于同一个已构造客户端，不需要重新获取凭据。
            output.reset();
            K8sLogging.setDebugEnabled(true);
            client.getVersion();
            assertTrue(logs().contains("API 请求开始"));
            assertTrue(logs().contains("API 请求完成"));
            output.reset();
            K8sLogging.setDebugEnabled(false);
            client.getVersion();
            assertFalse(logs().contains("DEBUG com.iskycc.k8s"));
            assertEquals(4, api.getRequestCount());
        }
    }

    @Test
    public void startupPropertyDefaultsToOffAndCanExplicitlyEnableDebug() throws Exception {
        assertEquals("false", startupSwitch(null));
        assertEquals("true", startupSwitch("true"));
        assertEquals("false", startupSwitch("false"));
    }

    private String startupSwitch(String value) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (value != null) { command.add("-D" + K8sLogging.DEBUG_PROPERTY + "=" + value); }
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(StartupProbe.class.getName());
        ProcessBuilder builder = new ProcessBuilder(command);
        // 子进程独立验证默认值，不继承测试宿主可能注入的 JVM 开关。
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process process = builder.start();
        try {
            assertTrue("开关探测应及时完成", process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            try (InputStream input = process.getInputStream();
                 ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                int valueRead;
                while ((valueRead = input.read()) != -1) { result.write(valueRead); }
                return new String(result.toByteArray(), StandardCharsets.UTF_8).trim();
            }
        } finally {
            process.destroyForcibly();
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        }
    }

    /** 子 JVM 入口只验证初始化读取，不连接任何服务。 */
    public static final class StartupProbe {
        public static void main(String[] args) {
            System.out.println(K8sLogging.isDebugEnabled());
        }
    }

    private String logs() { return new String(output.toByteArray(), StandardCharsets.UTF_8); }

    private void assertSafe(String logs) {
        assertFalse("日志不应包含 token", logs.contains(TOKEN));
        assertFalse("日志不应包含密码", logs.contains(PASSWORD));
        assertFalse("日志不应包含命令、正文或异常消息", logs.contains(BODY));
    }
}

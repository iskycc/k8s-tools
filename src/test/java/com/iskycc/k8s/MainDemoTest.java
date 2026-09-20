package com.iskycc.k8s;

import com.iskycc.k8s.mock.MockK8sApiServer;
import com.iskycc.k8s.mock.MockK8sMasterServer;
import com.iskycc.k8s.mock.MockRedisServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 命令行入口的 E2E 演示：对模拟集群完整执行 Main（SSH 取 token -> 调 API -> 打印集群数据）。
 */
public class MainDemoTest {

    private static final String TOKEN = "mock-jwt-token-for-demo";
    private static MockK8sApiServer apiServer;
    private static MockK8sMasterServer master;

    @BeforeClass
    public static void startCluster() throws Exception {
        apiServer = new MockK8sApiServer(TOKEN);
        master = new MockK8sMasterServer(MockK8sMasterServer.SaScenario.FRESH_CLUSTER,
                "root", "demo-password", apiServer.getBaseUrl(), TOKEN, apiServer.getCaCertPem());
        master.start();
    }

    @AfterClass
    public static void stopCluster() throws Exception {
        if (master != null) {
            master.close();
        }
        if (apiServer != null) {
            apiServer.close();
        }
    }

    @Test
    public void mainRunsFullFlowAgainstSimulatedCluster() throws Exception {
        assertMainFlow();
    }

    @Test
    public void redisCacheSurvivesRepeatedCliRunsAndCanBeRefreshed() throws Exception {
        try (MockRedisServer redis = new MockRedisServer()) {
            String url = "redis://127.0.0.1:" + redis.getPort() + "/0";
            assertMainFlow("--redis-url", url);
            int commands = master.getExecutedCommands().size();
            assertEquals(TOKEN, redis.get("127.0.0.1ServiceToken"));
            assertEquals(apiServer.getBaseUrl(), redis.get("127.0.0.1ApiServerUrl"));
            assertMainFlow("--redis-url", url);
            assertEquals(commands, master.getExecutedCommands().size());
            assertMainFlow("--redis-url", url, "--refresh-cache");
            assertTrue(master.getExecutedCommands().size() > commands);
        }
    }

    @Test
    public void strictTlsRemainsAvailable() throws Exception {
        assertMainFlow("--strict-tls");
    }

    @Test
    public void passwordOnlyIgnoresCliKeyAndRunsFullFlow() throws Exception {
        Path directory = Files.createTempDirectory("k8s-cli-missing-key-");
        try {
            assertMainFlow("--key", directory.resolve("missing-key").toString(), "--password-only");
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void passwordOnlyWithoutPasswordExitsBeforeConnecting() throws Exception {
        Path output = Files.createTempFile("k8s-cli-validation-", ".txt");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", classpath, Main.class.getName(),
                "--host", "127.0.0.1", "--password-only", "--key", "unused-key")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(2, process.exitValue());
            String result = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            assertTrue(result.contains("--password-only 必须同时提供非空 --password"));
            assertTrue(!result.contains("[1/3]"));
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    @Test
    public void helpExplainsPasswordOnly() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream capture = new PrintStream(output, true, "UTF-8")) {
            System.setOut(capture);
            Main.main(new String[]{"--help"});
        } finally {
            System.setOut(original);
        }
        String help = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(help.contains("--password-only"));
        assertTrue(help.contains("SSH agent"));
        assertTrue(help.contains("--redis-url"));
        assertTrue(help.contains("--refresh-cache"));
        assertTrue(help.contains("--strict-tls"));
        assertTrue(help.contains("默认开启"));
    }

    private void assertMainFlow(String... additionalArgs) throws Exception {
        List<String> args = new ArrayList<String>(Arrays.asList(
                "--host", "127.0.0.1",
                "--port", String.valueOf(master.getPort()),
                "--user", "root",
                "--password", "demo-password",
                "--redis-url", "", // 测试默认不继承真实环境的 Redis 配置。
                "--namespace", "default"));
        args.addAll(Arrays.asList(additionalArgs));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(buf, true, "UTF-8"));
        try {
            Main.main(args.toArray(new String[0]));
        } catch (Exception e) {
            System.setOut(original);
            throw new AssertionError("Main 执行失败", e);
        } finally {
            System.setOut(original);
        }

        String out = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        System.out.println("---- Main 演示输出 ----");
        System.out.println(out);

        assertTrue("应完成 SSH 取 token 步骤: " + out, out.contains("获取 service token"));
        assertTrue("应打印集群版本 v1.28.2", out.contains("v1.28.2"));
        assertTrue("应打印 node-1 Ready=true", out.contains("node-1") && out.contains("Ready=true"));
        assertTrue("应打印 node-2 Ready=false", out.contains("Ready=false"));
        assertTrue("应打印 nginx pod Running", out.contains("nginx-7d9f8c6b5-x2k4p") && out.contains("Running"));
        assertTrue("应打印 kubernetes service", out.contains("kubernetes") && out.contains("10.96.0.1"));
        assertTrue("应打印 nginx deployment 3/3", out.contains("replicas=3") && out.contains("ready=3"));
        // token 不应出现在输出中
        assertTrue("输出不应泄露完整 token", !out.contains(TOKEN));
    }
}

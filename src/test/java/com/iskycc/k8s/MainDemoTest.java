package com.iskycc.k8s;

import com.iskycc.k8s.mock.MockK8sApiServer;
import com.iskycc.k8s.mock.MockK8sMasterServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(buf, true, "UTF-8"));
        try {
            Main.main(new String[]{
                    "--host", "127.0.0.1",
                    "--port", String.valueOf(master.getPort()),
                    "--user", "root",
                    "--password", "demo-password",
                    "--namespace", "default"
            });
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

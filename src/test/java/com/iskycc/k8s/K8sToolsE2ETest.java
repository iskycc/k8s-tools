package com.iskycc.k8s;

import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.api.model.Deployment;
import com.iskycc.k8s.api.model.Namespace;
import com.iskycc.k8s.api.model.Node;
import com.iskycc.k8s.api.model.Pod;
import com.iskycc.k8s.api.model.Service;
import com.iskycc.k8s.api.model.VersionInfo;
import com.iskycc.k8s.mock.CertUtil;
import com.iskycc.k8s.mock.MockK8sApiServer;
import com.iskycc.k8s.mock.MockK8sMasterServer;
import com.iskycc.k8s.ssh.MasterInfo;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;
import com.iskycc.k8s.ssh.SshConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 端到端测试：完整模拟「SSH(Apache SSHD) 登录 master -> SA 检查/删除重建 -> 获取永久 token
 * -> API 地址自动发现 -> 忽略自签名证书调用 k8s API」。
 *
 * <p>拓扑（全部运行在 127.0.0.1 上）：
 * <pre>
 *  ServiceTokenFetcher --SSH(sshd-client)--> MockK8sMasterServer (模拟 master, 4 种 SA 场景)
 *          |  永久 token(SA secret) + 自动发现的 apiServerUrl + ca.crt PEM
 *          v
 *  K8sApiClient --HTTPS(Bearer Token, TLS 自动降级)--> MockK8sApiServer (模拟 kube-apiserver)
 * </pre>
 */
public class K8sToolsE2ETest {

    /** 模拟签发出的 ServiceAccount 永久 token（JWT 形态的假 token）。 */
    private static final String SA_TOKEN =
            "eyJhbGciOiJSUzI1NiIsImtpZCI6Im1vY2sta2V5In0."
            + "eyJpc3MiOiJrdWJlcm5ldGVzL3NlcnZpY2VhY2NvdW50Iiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9uYW1lc3BhY2UiOiJrdWJlLXN5c3RlbSJ9."
            + "mock-signature";

    private static final String SSH_USER = "root";
    private static final String SSH_PASSWORD = "mock-master-password";

    private static MockK8sApiServer apiServer;
    /** 全新集群：SA 不存在 → 创建 + 手动创建永久 secret（controller 延迟填充）。 */
    private static MockK8sMasterServer freshMaster;
    /** 老集群：SA 自动 secret 直接复用。 */
    private static MockK8sMasterServer legacyMaster;
    /** 幂等重跑：SA 存在 + 手动 secret 已有 token；kubeconfig 损坏 → API 地址走 admin.conf。 */
    private static MockK8sMasterServer rerunMaster;
    /** SA 存在但 token 不可得 → 删除重建；API 地址发现全失败 → host:6443 兜底。 */
    private static MockK8sMasterServer brokenMaster;

    /** 测试用 Options：缩短等待 controller 填充的轮询间隔。 */
    private static ServiceTokenFetcher.Options fastOptions() {
        return new ServiceTokenFetcher.Options()
                .tokenWaitRetries(10)
                .tokenWaitIntervalMs(50)
                .commandTimeoutMs(10000);
    }

    @BeforeClass
    public static void startCluster() throws Exception {
        apiServer = new MockK8sApiServer(SA_TOKEN);
        freshMaster = newMaster(MockK8sMasterServer.SaScenario.FRESH_CLUSTER);
        legacyMaster = newMaster(MockK8sMasterServer.SaScenario.LEGACY_AUTO_SECRET);
        rerunMaster = newMaster(MockK8sMasterServer.SaScenario.EXISTING_MANUAL_SECRET);
        brokenMaster = newMaster(MockK8sMasterServer.SaScenario.BROKEN_SA);
    }

    private static MockK8sMasterServer newMaster(MockK8sMasterServer.SaScenario scenario)
            throws Exception {
        MockK8sMasterServer m = new MockK8sMasterServer(scenario,
                SSH_USER, SSH_PASSWORD, apiServer.getBaseUrl(), SA_TOKEN, apiServer.getCaCertPem());
        m.start();
        return m;
    }

    @AfterClass
    public static void stopCluster() throws Exception {
        for (MockK8sMasterServer m : new MockK8sMasterServer[]{
                brokenMaster, rerunMaster, legacyMaster, freshMaster}) {
            if (m != null) {
                m.close();
            }
        }
        if (apiServer != null) {
            apiServer.close();
        }
    }

    private static SshConfig sshConfig(MockK8sMasterServer master, String password) {
        return SshConfig.builder()
                .host("127.0.0.1")
                .port(master.getPort())
                .username(SSH_USER)
                .password(password)
                .connectTimeoutMs(10000)
                .build();
    }

    private static MasterInfo fetchFrom(MockK8sMasterServer master) {
        return new ServiceTokenFetcher(sshConfig(master, SSH_PASSWORD), fastOptions()).fetch();
    }

    // ==================================================================
    // 主流程 E2E：全新集群，创建 SA + 永久 token secret + 自动发现 API 地址
    // ==================================================================

    @Test
    public void e2e_freshCluster_createSaAndPermanentToken_thenCallApi() {
        MasterInfo info = fetchFrom(freshMaster);

        assertEquals("API Server 地址应自动发现(kubectl config view)",
                apiServer.getBaseUrl(), info.getApiServerUrl());
        assertEquals("token 应为 SA secret 中的永久 token(base64 解码后)",
                SA_TOKEN, info.getToken());
        assertNotNull("应带回 master 上的 ca.crt", info.getCaCertPem());
        assertTrue(info.getCaCertPem().contains("BEGIN CERTIFICATE"));
        assertFalse(info.toString().contains(SA_TOKEN));

        List<String> cmds = freshMaster.getExecutedCommands();
        assertTrue("应先检查 SA 是否存在",
                cmds.contains("kubectl -n kube-system get sa k8s-tools"));
        assertTrue("SA 不存在时应创建",
                cmds.contains("kubectl create serviceaccount k8s-tools -n kube-system"));
        assertTrue("应创建 service-account-token 类型的永久 secret",
                cmds.contains("kubectl -n kube-system create secret generic k8s-tools-token"
                        + " --type=kubernetes.io/service-account-token"));
        assertTrue("应将 secret 绑定到 SA",
                cmds.contains("kubectl -n kube-system annotate secret k8s-tools-token"
                        + " kubernetes.io/service-account.name=k8s-tools --overwrite"));
        int tokenReads = 0;
        for (String c : cmds) {
            if (c.startsWith("kubectl -n kube-system get secret k8s-tools-token")) {
                tokenReads++;
            }
        }
        assertTrue("controller 延迟填充时应重试读取(tokenReads=" + tokenReads + ")", tokenReads >= 3);
        for (String c : cmds) {
            assertFalse("全新集群不应触发 SA 删除: " + c, c.contains("delete sa"));
        }

        // ---- 使用永久 token + CA 证书调用 k8s API ----
        K8sApiClient client = K8sApiClient.fromMasterInfo(info);
        assertEquals(apiServer.getBaseUrl(), client.getApiServer());

        VersionInfo version = client.getVersion();
        assertEquals("v1.28.2", version.getGitVersion());
        assertEquals("linux/amd64", version.getPlatform());

        List<Node> nodes = client.listNodes();
        assertEquals(2, nodes.size());
        assertEquals("node-1", nodes.get(0).getName());
        assertTrue("node-1 应为 Ready", nodes.get(0).isReady());
        assertEquals("10.4.4.8", nodes.get(0).getInternalIp());
        assertFalse("node-2 应为 NotReady", nodes.get(1).isReady());

        List<Namespace> namespaces = client.listNamespaces();
        assertEquals(3, namespaces.size());
        assertEquals("default", namespaces.get(0).getName());
        assertEquals("Active", namespaces.get(0).getPhase());
        assertEquals("Terminating", namespaces.get(2).getPhase());

        List<Pod> defaultPods = client.listPods("default");
        assertEquals(1, defaultPods.size());
        Pod nginx = defaultPods.get(0);
        assertEquals("nginx-7d9f8c6b5-x2k4p", nginx.getName());
        assertEquals("Running", nginx.getPhase());
        assertEquals("node-1", nginx.getNodeName());
        assertEquals("10.244.1.5", nginx.getStatus().getPodIP());
        assertEquals(3, client.listPods(null).size());
        assertEquals("coredns-5b7c9d8f6-abcde", client.listPods("kube-system").get(0).getName());

        List<Service> services = client.listServices("default");
        assertEquals(2, services.size());
        assertEquals("kubernetes", services.get(0).getName());
        assertEquals("ClusterIP", services.get(0).getType());
        assertEquals("10.96.0.1", services.get(0).getClusterIP());
        assertEquals(443, services.get(0).getSpec().getPorts().get(0).getPort());
        assertEquals("NodePort", services.get(1).getType());
        assertEquals(Integer.valueOf(30080), services.get(1).getSpec().getPorts().get(0).getNodePort());

        List<Deployment> deployments = client.listDeployments("default");
        assertEquals(2, deployments.size());
        assertEquals("nginx", deployments.get(0).getName());
        assertEquals(Integer.valueOf(3), deployments.get(0).getReplicas());
        assertEquals(3, deployments.get(0).getReadyReplicas());
        assertEquals(1, deployments.get(1).getReadyReplicas());

        assertEquals("Bearer " + SA_TOKEN, apiServer.getLastAuthorization());
    }

    // ==================================================================
    // 老集群：复用 SA 自动生成的 token secret
    // ==================================================================

    @Test
    public void e2e_legacyCluster_reuseAutoGeneratedSecretToken() {
        MasterInfo info = fetchFrom(legacyMaster);

        assertEquals("老集群应直接复用 SA 自动生成的 secret 中的永久 token",
                SA_TOKEN, info.getToken());

        List<String> cmds = legacyMaster.getExecutedCommands();
        assertTrue(cmds.contains("kubectl -n kube-system get sa k8s-tools"));
        assertTrue(cmds.contains("kubectl -n kube-system get sa k8s-tools -o jsonpath={.secrets[0].name}"));
        assertTrue(cmds.contains("kubectl -n kube-system get secret k8s-tools-token-mock42 -o jsonpath={.data.token}"));
        for (String c : cmds) {
            assertFalse("不应手动创建 secret: " + c, c.contains("create secret generic"));
            assertFalse("不应 annotate secret: " + c, c.contains("annotate secret"));
            assertFalse("token 可得，不应删除 SA: " + c, c.contains("delete sa"));
        }

        K8sApiClient client = K8sApiClient.fromMasterInfo(info);
        assertEquals("v1.28.2", client.getVersion().getGitVersion());
    }

    // ==================================================================
    // 幂等重跑：SA 与手动 secret 均已存在，直接复用；API 地址走 admin.conf
    // ==================================================================

    @Test
    public void e2e_rerun_reuseExistingManualSecret_andApiUrlFromAdminConf() {
        MasterInfo info = fetchFrom(rerunMaster);

        assertEquals(SA_TOKEN, info.getToken());
        assertEquals("kubeconfig 损坏时应自动从 /etc/kubernetes/admin.conf 发现 API 地址",
                "https://10.96.0.1:6443", info.getApiServerUrl());

        List<String> cmds = rerunMaster.getExecutedCommands();
        assertTrue(cmds.contains("kubectl -n kube-system get secret k8s-tools-token -o jsonpath={.data.token}"));
        for (String c : cmds) {
            assertFalse("token 可得，不应删除 SA: " + c, c.contains("delete sa"));
            assertFalse("secret 已有 token，不应重复创建: " + c, c.contains("create secret generic"));
        }
    }

    // ==================================================================
    // 核心需求：SA 已存在但 token 不可得 → 先删除再重建；API 地址走 host:6443 兜底
    // ==================================================================

    @Test
    public void e2e_brokenSa_deletedAndRecreated_thenPermanentTokenObtained() {
        MasterInfo info = fetchFrom(brokenMaster);

        assertEquals("删除重建后应拿到新的永久 token", SA_TOKEN, info.getToken());
        assertEquals("kubeconfig 与 admin.conf 都失败时应兜底 https://<ssh-host>:6443",
                "https://127.0.0.1:6443", info.getApiServerUrl());

        List<String> cmds = brokenMaster.getExecutedCommands();
        int idxDeleteSa = cmds.indexOf("kubectl -n kube-system delete sa k8s-tools --ignore-not-found");
        assertTrue("SA 存在但 token 不可得时应先删除 SA", idxDeleteSa >= 0);
        int idxRecreate = cmds.indexOf("kubectl create serviceaccount k8s-tools -n kube-system");
        assertTrue("删除后应重建 SA", idxRecreate > idxDeleteSa);
        assertTrue("应清理残留的手动 secret",
                cmds.contains("kubectl -n kube-system delete secret k8s-tools-token --ignore-not-found"));
        assertTrue("残留 secret 导致 AlreadyExists 时应容忍并继续",
                cmds.contains("kubectl -n kube-system create secret generic k8s-tools-token"
                        + " --type=kubernetes.io/service-account-token"));
        assertTrue(cmds.contains("kubectl -n kube-system annotate secret k8s-tools-token"
                + " kubernetes.io/service-account.name=k8s-tools --overwrite"));
        // 删除前读取损坏 secret 的记录
        assertTrue(cmds.contains("kubectl -n kube-system get secret k8s-tools-token-broken -o jsonpath={.data.token}"));
    }

    @Test
    public void e2e_brokenSa_recreateDisabled_throws() throws Exception {
        // 独立实例，避免与 e2e_brokenSa_deletedAndRecreated 共享读取计数状态
        MockK8sMasterServer dedicated = newMaster(MockK8sMasterServer.SaScenario.BROKEN_SA);
        try {
            ServiceTokenFetcher.Options opts = fastOptions()
                    .recreateSaWhenTokenUnobtainable(false);
            try {
                new ServiceTokenFetcher(sshConfig(dedicated, SSH_PASSWORD), opts).fetch();
                fail("禁用自动重建时，SA 存在但 token 不可得应抛异常");
            } catch (K8sToolsException e) {
                assertTrue("异常信息应说明原因: " + e.getMessage(),
                        e.getMessage().contains("无法获取永久 token"));
            }
            for (String c : dedicated.getExecutedCommands()) {
                assertFalse("禁用重建时不应删除 SA: " + c, c.contains("delete sa"));
            }
        } finally {
            dedicated.close();
        }
    }

    // ==================================================================
    // TLS：忽略自签名证书（自动降级 / 无 CA 默认 insecure）
    // ==================================================================

    @Test
    public void e2e_selfSignedCert_ignoredByTlsAutoFallback() throws Exception {
        // 用一个与 apiserver 证书不匹配的"过期/错误 CA"构建客户端 → 校验必失败 → 应自动降级重试成功
        String wrongCa = CertUtil.generateSelfSigned("wrong-ca",
                new String[]{"localhost"}, new String[]{"127.0.0.1"}).getPem();
        K8sApiClient client = K8sApiClient.builder()
                .apiServer(apiServer.getBaseUrl())
                .token(SA_TOKEN)
                .caCertPem(wrongCa)
                .build();

        assertEquals("v1.28.2", client.getVersion().getGitVersion());
        assertTrue("应已自动降级为 trust-all", client.isDegradedToInsecure());
        assertEquals(2, client.listNodes().size());
    }

    @Test
    public void e2e_noCaFromMaster_defaultsToInsecure_andWorks() {
        MasterInfo noCa = new MasterInfo(apiServer.getBaseUrl(), SA_TOKEN, null,
                "k8s-tools", "kube-system");
        K8sApiClient client = K8sApiClient.fromMasterInfo(noCa);
        assertEquals("master 拿不到 CA 时应默认忽略自签名证书",
                "v1.28.2", client.getVersion().getGitVersion());
    }

    @Test
    public void e2e_strictTls_failsWhenAutoFallbackDisabled() {
        K8sApiClient strict = K8sApiClient.builder()
                .apiServer(apiServer.getBaseUrl())
                .token(SA_TOKEN)
                .tlsAutoFallback(false) // 显式关闭自动降级
                .build();
        try {
            strict.getVersion();
            fail("严格模式下未信任的自签名证书应导致 TLS 失败");
        } catch (K8sApiException e) {
            assertEquals("网络级错误 statusCode 应为 -1", -1, e.getStatusCode());
        }
    }

    // ==================================================================
    // 其余异常路径
    // ==================================================================

    @Test
    public void e2e_sshWrongPassword_fails() {
        try {
            new ServiceTokenFetcher(
                    sshConfig(freshMaster, "wrong-password"), fastOptions()).fetch();
            fail("错误密码应抛出 K8sToolsException");
        } catch (K8sToolsException e) {
            assertTrue("异常信息应包含 SSH 失败原因: " + e.getMessage(),
                    e.getMessage().contains("SSH"));
        }
    }

    @Test
    public void e2e_apiInvalidToken_returns401() {
        K8sApiClient bad = K8sApiClient.builder()
                .apiServer(apiServer.getBaseUrl())
                .token("invalid-token")
                .caCertPem(apiServer.getCaCertPem())
                .build();
        try {
            bad.getVersion();
            fail("非法 token 应收到 401");
        } catch (K8sApiException e) {
            assertEquals(401, e.getStatusCode());
            assertTrue(e.getResponseBody().contains("Unauthorized"));
        }
    }

    @Test
    public void e2e_apiUnknownPath_returns404() {
        K8sApiClient client = K8sApiClient.fromMasterInfo(fetchFrom(freshMaster));
        try {
            client.getRaw("/api/v1/not-exist");
            fail("未知路径应收到 404");
        } catch (K8sApiException e) {
            assertEquals(404, e.getStatusCode());
        }
    }
}

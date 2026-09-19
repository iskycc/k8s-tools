package com.iskycc.k8s.api;

import com.iskycc.k8s.ssh.MasterInfo;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * K8sApiClient 构建参数与 URL 规范化的单元测试（不发起网络请求）。
 */
public class K8sApiClientTest {

    @Test
    public void builderRequiresApiServerAndToken() {
        try {
            K8sApiClient.builder().token("t").build();
            fail("缺少 apiServer 应报错");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            K8sApiClient.builder().apiServer("https://127.0.0.1:6443").build();
            fail("缺少 token 应报错");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void apiServerTrailingSlashIsStripped() {
        K8sApiClient client = K8sApiClient.builder()
                .apiServer("https://127.0.0.1:6443///")
                .token("t")
                .build();
        assertEquals("https://127.0.0.1:6443", client.getApiServer());
    }

    @Test
    public void getRawRejectsPathWithoutLeadingSlash() {
        K8sApiClient client = K8sApiClient.builder()
                .apiServer("https://127.0.0.1:6443")
                .token("t")
                .build();
        try {
            client.getRaw("api/v1/pods");
            fail("path 必须以 / 开头");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void fromMasterInfoMapsFields() {
        MasterInfo info = new MasterInfo("https://10.0.0.1:6443/", "tok", null,
                "k8s-tools", "kube-system");
        K8sApiClient client = K8sApiClient.fromMasterInfo(info);
        assertEquals("https://10.0.0.1:6443", client.getApiServer());
        assertEquals("MasterInfo 应携带原始 token", "tok", info.getToken());
        assertTrue("toString 应掩码 token: " + info, info.toString().contains("token=***"));
    }
}

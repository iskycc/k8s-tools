package com.iskycc.k8s.ssh;

/**
 * 从 master 节点获取到的集群访问信息。
 */
public final class MasterInfo {

    private final String apiServerUrl;
    private final String token;
    /** /etc/kubernetes/pki/ca.crt 的 PEM 内容，可能为 null（获取失败或未开启）。 */
    private final String caCertPem;
    private final String serviceAccount;
    private final String serviceAccountNamespace;

    public MasterInfo(String apiServerUrl, String token, String caCertPem,
                      String serviceAccount, String serviceAccountNamespace) {
        this.apiServerUrl = apiServerUrl;
        this.token = token;
        this.caCertPem = caCertPem;
        this.serviceAccount = serviceAccount;
        this.serviceAccountNamespace = serviceAccountNamespace;
    }

    public String getApiServerUrl() {
        return apiServerUrl;
    }

    public String getToken() {
        return token;
    }

    public String getCaCertPem() {
        return caCertPem;
    }

    public String getServiceAccount() {
        return serviceAccount;
    }

    public String getServiceAccountNamespace() {
        return serviceAccountNamespace;
    }

    @Override
    public String toString() {
        // token 不落日志
        return "MasterInfo{apiServerUrl=" + apiServerUrl
                + ", token=" + maskToken(token)
                + ", caCert=" + (caCertPem != null ? "present" : "absent")
                + ", serviceAccount=" + serviceAccountNamespace + ":" + serviceAccount + "}";
    }

    private static String maskToken(String token) {
        if (token == null || token.length() <= 12) {
            return "***";
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
    }
}

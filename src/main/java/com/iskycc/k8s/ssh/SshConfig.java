package com.iskycc.k8s.ssh;

/**
 * master 节点 SSH 连接配置。支持密码与私钥两种认证方式。
 */
public final class SshConfig {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String privateKeyPath;
    private final String privateKeyPassphrase;
    private final boolean passwordOnly;
    private final int connectTimeoutMs;

    private SshConfig(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.privateKeyPath = b.privateKeyPath;
        this.privateKeyPassphrase = b.privateKeyPassphrase;
        this.passwordOnly = b.passwordOnly
                || ((b.privateKeyPath == null || b.privateKeyPath.isEmpty())
                    && b.password != null && !b.password.isEmpty());
        this.connectTimeoutMs = b.connectTimeoutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public String getPrivateKeyPassphrase() {
        return privateKeyPassphrase;
    }

    /** 是否仅用密码认证；仅配置密码时自动启用。 */
    public boolean isPasswordOnly() {
        return passwordOnly;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    @Override
    public String toString() {
        return "SshConfig{" + username + "@" + host + ":" + port + "}";
    }

    public static final class Builder {
        private String host;
        private int port = 22;
        private String username = "root";
        private String password;
        private String privateKeyPath;
        private String privateKeyPassphrase;
        private boolean passwordOnly;
        private int connectTimeoutMs = 15000;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder privateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
            return this;
        }

        public Builder privateKeyPassphrase(String privateKeyPassphrase) {
            this.privateKeyPassphrase = privateKeyPassphrase;
            return this;
        }

        /**
         * 强制仅使用密码认证，忽略配置中的私钥及私钥口令、本地 SSH 配置、默认密钥与 SSH agent。
         * 支持 password 及单密码 keyboard-interactive，不进行用户公钥签名认证。
         * 必须同时提供非空 password；未设置私钥路径时自动使用此模式。
         *
         * @param passwordOnly true 强制使用密码；false 保留按凭据选择的默认行为
         * @return 当前 builder
         */
        public Builder passwordOnly(boolean passwordOnly) {
            this.passwordOnly = passwordOnly;
            return this;
        }

        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public SshConfig build() {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("SSH host is required");
            }
            if (username == null || username.trim().isEmpty()) {
                throw new IllegalArgumentException("SSH username is required");
            }
            if (passwordOnly && (password == null || password.isEmpty())) {
                throw new IllegalArgumentException("password is required when passwordOnly is enabled");
            }
            if ((password == null || password.isEmpty())
                    && (privateKeyPath == null || privateKeyPath.isEmpty())) {
                throw new IllegalArgumentException("either password or privateKeyPath is required");
            }
            return new SshConfig(this);
        }
    }
}

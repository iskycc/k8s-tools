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
    private final int connectTimeoutMs;

    private SshConfig(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.privateKeyPath = b.privateKeyPath;
        this.privateKeyPassphrase = b.privateKeyPassphrase;
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
            if ((password == null || password.isEmpty())
                    && (privateKeyPath == null || privateKeyPath.isEmpty())) {
                throw new IllegalArgumentException("either password or privateKeyPath is required");
            }
            return new SshConfig(this);
        }
    }
}

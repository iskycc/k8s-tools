package com.iskycc.k8s.api;

/** 一次非交互式 Pod Exec 的选项；无 stdin、无 TTY，stdout/stderr 分离。 */
public final class PodExecOptions {
    /** AUTO 有 SSH 配置时按 1.31 门槛选路；其余两项显式指定传输。 */
    public enum Transport { AUTO, WEBSOCKET, SSH }

    private final Transport transport;
    private final String container;
    private final int timeoutMs;
    private final int maxOutputBytes;

    private PodExecOptions(Builder builder) {
        transport = builder.transport;
        container = builder.container;
        timeoutMs = builder.timeoutMs;
        maxOutputBytes = builder.maxOutputBytes;
    }

    public static Builder builder() { return new Builder(); }
    public Transport getTransport() { return transport; }
    public String getContainer() { return container; }
    public int getTimeoutMs() { return timeoutMs; }
    public int getMaxOutputBytes() { return maxOutputBytes; }

    public static final class Builder {
        private Transport transport = Transport.AUTO;
        private String container;
        private int timeoutMs = 30000;
        private int maxOutputBytes = 4 * 1024 * 1024;

        /** 默认 AUTO；显式 WEBSOCKET/SSH 不查询版本，也不在执行失败后切换。 */
        public Builder transport(Transport value) { transport = value; return this; }

        /** 多容器 Pod 建议显式指定；为空时遵循 API Server 或远端 kubectl 的选择规则。 */
        public Builder container(String value) { container = value; return this; }
        /** 含版本探测、连接和执行的总时限，默认 30 秒；超时关闭连接，不保证终止远端进程。 */
        public Builder timeoutMs(int value) { timeoutMs = value; return this; }
        /** stdout+stderr 字节总上限，默认 4 MiB；超过后失败并保留已接收部分。 */
        public Builder maxOutputBytes(int value) { maxOutputBytes = value; return this; }

        public PodExecOptions build() {
            if (transport == null) { throw new IllegalArgumentException("transport is required"); }
            if (container != null && (container.length() > 63
                    || !container.matches("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"))) {
                throw new IllegalArgumentException("container must be a DNS label");
            }
            if (timeoutMs <= 0) { throw new IllegalArgumentException("timeoutMs must be > 0"); }
            if (maxOutputBytes <= 0) { throw new IllegalArgumentException("maxOutputBytes must be > 0"); }
            return new PodExecOptions(this);
        }
    }
}

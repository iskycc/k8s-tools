package com.iskycc.k8s.api;

/** Exec 协议、超时或连接失败；不推断远端执行状态，也不自动重试。 */
public final class PodExecException extends K8sApiException {
    private static final long serialVersionUID = 1L;
    public enum Reason { TIMEOUT, INTERRUPTED, OUTPUT_LIMIT, PROTOCOL, TRANSPORT, REMOTE_ERROR, VERSION_DETECTION }

    private final Reason failureReason;
    private final PodExecResult partialResult;
    private final String remoteStatus;

    PodExecException(Reason reason, Throwable cause, byte[] stdout, byte[] stderr, String remoteStatus) {
        super("Pod exec failed: " + reason, cause);
        failureReason = reason;
        partialResult = new PodExecResult(-1, stdout, stderr);
        this.remoteStatus = remoteStatus;
    }

    public Reason getFailureReason() { return failureReason; }
    /** 已接收的部分输出；exitCode=-1 表示未知，不能将部分输出当作执行成功。 */
    public PodExecResult getPartialResult() { return partialResult; }
    /** 错误通道原始内容（最多 64 KiB），可能含命令或敏感信息；不自动写入日志。 */
    public String getRemoteStatus() { return remoteStatus; }
}

package com.iskycc.k8s.api;

import java.nio.charset.StandardCharsets;

/** 容器进程的执行结果；非零退出码也正常返回，传输失败另抛异常。 */
public final class PodExecResult {
    private final int exitCode;
    private final byte[] stdout;
    private final byte[] stderr;

    PodExecResult(int exitCode, byte[] stdout, byte[] stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout.clone();
        this.stderr = stderr.clone();
    }

    public int getExitCode() { return exitCode; }
    public boolean isSuccess() { return exitCode == 0; }
    public String getStdout() { return new String(stdout, StandardCharsets.UTF_8); }
    public String getStderr() { return new String(stderr, StandardCharsets.UTF_8); }
    public byte[] getStdoutBytes() { return stdout.clone(); }
    public byte[] getStderrBytes() { return stderr.clone(); }

    /** 命令输出可能含凭据，toString 只返回长度和退出码。 */
    @Override public String toString() {
        return "PodExecResult{exitCode=" + exitCode + ", stdoutBytes=" + stdout.length
                + ", stderrBytes=" + stderr.length + "}";
    }
}

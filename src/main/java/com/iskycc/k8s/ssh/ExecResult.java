package com.iskycc.k8s.ssh;

/**
 * 远程命令执行结果。
 */
public final class ExecResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;

    public ExecResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    /** stdout+stderr 合并输出，便于错误诊断。 */
    public String combinedOutput() {
        return stdout + stderr;
    }

    @Override
    public String toString() {
        return "ExecResult{exitCode=" + exitCode
                + ", stdout=" + abbreviate(stdout)
                + ", stderr=" + abbreviate(stderr) + "}";
    }

    private static String abbreviate(String s) {
        if (s.length() <= 200) {
            return s;
        }
        return s.substring(0, 200) + "...(" + s.length() + " chars)";
    }
}

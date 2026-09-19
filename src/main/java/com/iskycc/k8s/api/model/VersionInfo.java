package com.iskycc.k8s.api.model;

/**
 * /version 响应。
 */
public class VersionInfo {
    private String major;
    private String minor;
    private String gitVersion;
    private String gitCommit;
    private String buildDate;
    private String goVersion;
    private String compiler;
    private String platform;

    public String getMajor() { return major; }
    public String getMinor() { return minor; }
    public String getGitVersion() { return gitVersion; }
    public String getGitCommit() { return gitCommit; }
    public String getBuildDate() { return buildDate; }
    public String getGoVersion() { return goVersion; }
    public String getCompiler() { return compiler; }
    public String getPlatform() { return platform; }

    @Override
    public String toString() {
        return "VersionInfo{gitVersion=" + gitVersion + ", platform=" + platform + "}";
    }
}

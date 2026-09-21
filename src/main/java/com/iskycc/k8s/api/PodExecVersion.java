package com.iskycc.k8s.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 1.31 是 AUTO 的保守策略门槛，不是 WebSocket/v4 的最低实现版本。 */
final class PodExecVersion {
    private static final Pattern VERSION = Pattern.compile("^v?([0-9]+)\\.([0-9]+)(?:\\.[0-9]+)?(?:[-+].*)?$");
    private final int major;
    private final int minor;
    private final boolean prerelease;

    private PodExecVersion(int major, int minor, boolean prerelease) {
        this.major = major; this.minor = minor; this.prerelease = prerelease;
    }

    static PodExecVersion parse(String json) {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String version = object.has("gitVersion") && !object.get("gitVersion").isJsonNull()
                    ? object.get("gitVersion").getAsString() : "";
            Matcher matcher = VERSION.matcher(version);
            if (matcher.matches()) {
                return new PodExecVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        version.matches(".*-(?:alpha|beta|rc)(?:[.0-9].*)?"));
            }
            String major = object.get("major").getAsString();
            String minor = object.get("minor").getAsString();
            if (!major.matches("[0-9]+") || !minor.matches("[0-9]+\\+?")) { throw new IllegalArgumentException(); }
            return new PodExecVersion(Integer.parseInt(major), Integer.parseInt(minor.replace("+", "")), false);
        } catch (RuntimeException e) {
            // 不把可能含敏感内容的 /version 正文或解析消息放入异常链。
            throw new PodExecException(PodExecException.Reason.VERSION_DETECTION, null, new byte[0], new byte[0], "");
        }
    }

    boolean useSsh() { return major < 1 || (major == 1 && (minor < 31 || (minor == 31 && prerelease))); }
    @Override public String toString() { return major + "." + minor + (prerelease ? "-prerelease" : ""); }
}

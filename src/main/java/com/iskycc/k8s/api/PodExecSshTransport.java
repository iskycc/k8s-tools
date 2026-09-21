package com.iskycc.k8s.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.iskycc.k8s.ssh.SshConfig;
import com.iskycc.k8s.ssh.SshExecutor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 通过 SSH 上的 kubectl 执行；只使用调用方当前 API 身份，不读取远端已有 kubeconfig。 */
final class PodExecSshTransport {
    private PodExecSshTransport() { }

    static PodExecResult execute(SshConfig ssh, String server, String token, String caPem, boolean insecure,
                                 String namespace, String pod, PodExecOptions options, String[] command,
                                 PodExecOperation operation) {
        try (SshExecutor executor = new SshExecutor(ssh)) {
            operation.onCancel(executor::cancel);
            ByteArrayInputStream input = new ByteArrayInputStream(kubeconfig(server, token, caPem, insecure));
            int exit = executor.execStreaming(shellCommand(namespace, pod, options, command), input,
                    operation.output(false), operation.output(true), operation.remainingMs());
            return operation.result(exit);
        } catch (IOException | RuntimeException e) {
            operation.check();
            if (e instanceof PodExecException) { throw (PodExecException) e; }
            throw operation.error(PodExecException.Reason.TRANSPORT, e);
        }
    }

    private static byte[] kubeconfig(String server, String token, String caPem, boolean insecure) {
        JsonObject cluster = new JsonObject();
        cluster.addProperty("server", server);
        if (insecure) { cluster.addProperty("insecure-skip-tls-verify", true); }
        else if (caPem != null && !caPem.trim().isEmpty()) {
            cluster.addProperty("certificate-authority-data", Base64.getEncoder()
                    .encodeToString(caPem.getBytes(StandardCharsets.UTF_8)));
        }
        JsonObject user = new JsonObject(); user.addProperty("token", token);
        JsonObject context = new JsonObject(); context.addProperty("cluster", "target"); context.addProperty("user", "caller");
        JsonObject config = new JsonObject();
        config.addProperty("apiVersion", "v1"); config.addProperty("kind", "Config");
        config.add("clusters", entry("target", "cluster", cluster));
        config.add("users", entry("caller", "user", user));
        config.add("contexts", entry("exec", "context", context)); config.addProperty("current-context", "exec");
        return config.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonArray entry(String name, String key, JsonObject value) {
        JsonObject entry = new JsonObject(); entry.addProperty("name", name); entry.add(key, value);
        JsonArray list = new JsonArray(); list.add(entry); return list;
    }

    static String shellCommand(String namespace, String pod, PodExecOptions options, String[] args) {
        // 凭据通过 stdin 传给 chmod 600 的临时配置，不出现在 SSH 命令/进程参数中。
        StringBuilder script = new StringBuilder("set -eu\numask 077\n")
                .append("config=$(mktemp /tmp/k8s-tools-exec.XXXXXX)\n")
                .append("trap 'rm -f \"$config\"' 0\ntrap 'exit 130' HUP INT TERM\n")
                .append("cat >\"$config\"\n")
                .append("KUBECTL_REMOTE_COMMAND_WEBSOCKETS=false kubectl --kubeconfig=\"$config\" --namespace=")
                .append(quote(namespace)).append(" exec ").append(quote(pod));
        if (options.getContainer() != null) { script.append(" --container=").append(quote(options.getContainer())); }
        script.append(" --");
        for (String arg : args) { script.append(' ').append(quote(arg)); }
        return script.append('\n').toString();
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}

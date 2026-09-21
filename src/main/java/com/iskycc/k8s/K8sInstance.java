package com.iskycc.k8s;

import com.iskycc.k8s.internal.LogSupport;
import com.iskycc.k8s.ssh.SshConfig;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;

/**
 * 一个目标 Kubernetes 集群的 SSH 与 Redis 连接配置。构造时只校验参数，不连接网络。
 * IP/端口指 master 的 SSH 地址；API Server 地址由初始化流程自动发现。
 * Redis 使用默认用户、普通 TCP 和数据库 0；密码传原文，内部负责 URL 编码。
 */
public final class K8sInstance {
    private final String ip;
    private final int port;
    private final String username;
    private final String password;
    private final String redisPassword;
    private final String redisIp;
    private final int redisPort;

    /**
     * @param ip master SSH 的 IPv4、IPv6 或主机名，不含协议与端口
     * @param port SSH 端口，1 至 65535
     * @param username SSH 用户名
     * @param password SSH 密码，必须非空；不读取本地私钥或 SSH agent
     * @param redisPassword Redis 原始密码；null 或空字符串表示无密码，空格仍是密码的一部分
     * @param redisIp Redis 的 IPv4、IPv6 或主机名，不含协议与端口
     * @param redisPort Redis 端口，1 至 65535
     */
    public K8sInstance(String ip, int port, String username, String password,
                       String redisPassword, String redisIp, int redisPort) {
        this.port = requirePort(port, "SSH");
        this.redisPort = requirePort(redisPort, "Redis");
        this.ip = requireHost(ip, "SSH");
        this.redisIp = requireHost(redisIp, "Redis");
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("SSH username is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("SSH password is required");
        }
        this.username = username.trim();
        this.password = password;
        this.redisPassword = redisPassword;
    }

    public String getIp() { return ip; }
    public int getPort() { return port; }
    public String getUsername() { return username; }
    /** 敏感配置，不应写入日志或序列化给外部调用方。 */
    public String getPassword() { return password; }
    /** 敏感配置，值为原文而非 URL 编码后的密码。 */
    public String getRedisPassword() { return redisPassword; }
    public String getRedisIp() { return redisIp; }
    public int getRedisPort() { return redisPort; }

    SshConfig sshConfig() {
        return SshConfig.builder().host(ip).port(port).username(username)
                .password(password).passwordOnly(true).build();
    }

    String redisUrl() {
        String host = redisIp.indexOf(':') >= 0 ? "[" + redisIp + "]" : redisIp;
        String auth = "";
        if (redisPassword != null && !redisPassword.isEmpty()) {
            try {
                auth = ":" + URLEncoder.encode(redisPassword, "UTF-8").replace("+", "%20") + "@";
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException("UTF-8 is required", e);
            }
        }
        return "redis://" + auth + host + ":" + redisPort + "/0";
    }

    private static int requirePort(int port, String service) {
        if (port < 1 || port > 65535) { throw new IllegalArgumentException(service + " port must be between 1 and 65535"); }
        return port;
    }

    private static String requireHost(String value, String service) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(service + " host is required"); }
        String host = value.trim();
        if (host.startsWith("[") && host.endsWith("]") && host.indexOf(':') >= 0) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            if (!host.matches("[A-Za-z0-9.:-]+")) { throw new URISyntaxException("", "Invalid host"); }
            // URI 的 host 参数校验不做 DNS 查询，也不接受 URL、userinfo 或内嵌端口。
            URI uri = new URI("tcp", null, host, 1, null, null, null);
            if (uri.getHost() == null) { throw new URISyntaxException("", "Invalid host"); }
        } catch (URISyntaxException e) {
            // 错误输入可能误填了含密码的 URL，不在消息或 cause 中回显原值。
            throw new IllegalArgumentException(service + " host must be an IPv4, IPv6 or hostname without scheme or port");
        }
        return host;
    }

    @Override
    public String toString() {
        return "K8sInstance{ip=" + LogSupport.field(ip) + ", port=" + port
                + ", username=" + LogSupport.field(username) + ", password=***, redisIp="
                + LogSupport.field(redisIp) + ", redisPort=" + redisPort + ", redisPassword=***}";
    }
}

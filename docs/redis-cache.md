# Redis 凭据缓存与自动接入

本功能从正式版 `1.3.0` 起提供，使用方引用 `io.github.iskycc:k8s-tools:1.3.0`；`1.2.1` 及之前版本不包含 Redis 缓存和 `fromSsh` 入口。源码开发构建为 `1.3.0-SNAPSHOT`，本次不发布快照。Jedis 按要求固定为 `5.2.0`。

## 键与读取流程

以 master IP `192.0.2.10` 为例：

| Redis key | 类型 | value |
| --- | --- | --- |
| `192.0.2.10ServiceToken` | String | 原始 ServiceAccount Bearer Token，无 `Bearer ` 前缀 |
| `192.0.2.10ApiServerUrl` | String | 自动发现的 API 地址，例如 `https://192.0.2.10:6443` |
| `192.0.2.10ServiceTokenMetadata` | String | 内部 JSON 元数据：格式版本、SA/namespace、获取配置与 CA，用于保持凭据归属和 TLS 行为 |

key 直接使用 `SshConfig.host` 去除首尾空白后拼接后缀，不加冒号、不拼入 token 内容。请将 `host` 配置为 master IP；若填主机名，就使用该主机名字面量作为前缀，不进行 DNS 反查。

配置 `Options.redisCache(...)` 后，`fetch()` 先用一次 `MGET` 读取以上键：

1. token、地址和匹配的元数据齐全时，返回缓存结果，**不连接 SSH、不执行 kubectl，也不向 Kubernetes 探测有效性**。应用重启或新建 fetcher 后仍复用 Redis。
2. 缺键、空 token、地址格式错误、元数据损坏，或 SA/namespace/RBAC/发现配置改变时，通过 SSH 获取新凭据，发现 API 地址，再用一次 `MSET` 原子替换全部键。
3. 不配置 Redis 时保留每次 SSH 获取的行为；Redis 读写失败会抛 `K8sToolsException` 并保留原因，不把故障当作缓存未命中，也不静默跳过写入。

不设置 TTL，覆盖写入会清除旧 TTL；这不代表 token 永久有效。Redis 服务重启后的数据保留取决于 Redis 自身的 AOF/RDB 配置。每个 IP 保存一套凭据，切换配置会替换该 IP 的缓存；并发缓存未命中可能各自执行 SSH，本功能没有分布式锁。多键原子操作面向同一 Redis 实例/数据库，当前不支持 Redis Cluster 跨槽缓存。

## Java 接入：无需手工填写 API 地址或证书

```java
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.ssh.RedisServiceTokenCache;
import com.iskycc.k8s.ssh.ServiceTokenFetcher;
import com.iskycc.k8s.ssh.SshConfig;
import redis.clients.jedis.JedisPool;

import java.net.URI;

SshConfig ssh = SshConfig.builder()
        .host("192.0.2.10")
        .username("root")
        .password("<从应用配置读取的 SSH 密码>")
        .passwordOnly(true)
        .build();

// 应用已提供 JedisPool 时直接复用；在应用停止时统一关闭连接池。
try (JedisPool pool = new JedisPool(URI.create("redis://127.0.0.1:6379/0"))) {
    RedisServiceTokenCache cache = new RedisServiceTokenCache(pool);
    ServiceTokenFetcher.Options options = new ServiceTokenFetcher.Options()
            .redisCache(cache);

    K8sApiClient client = K8sApiClient.fromSsh(ssh, options);
    System.out.println(client.getVersion().getGitVersion());
    client.listPods("default");
}
```

`fromSsh` 默认直接跳过 HTTPS 证书和主机名校验，第一次请求即可增删查改，不需要先 GET 才触发降级。SSH 缓存未命中时仍可能创建 SA、Secret、cluster-admin 绑定及按原策略重建 SA，详见 [README](../README.md#serviceaccount-与-token)。Redis 连接池的认证、数据库、TLS、超时等参数由调用方通过 Jedis 配置；库只借用并归还连接，不关闭传入的池。

API 地址发现顺序为远端当前 kubeconfig → `/etc/kubernetes/admin.conf` → `https://<masterIP>:6443`。无效输出继续尝试下一来源；发现的 `localhost`、IPv4 回环/通配地址、`[::1]` 或 `[::]` 会换成 master 地址，并保留端口。IPv6 回退地址自动加方括号。显式 `apiServerOverride` 仍优先，且不会改写。自动发现不创建网络隧道，发现的普通域名或内网 IP 仍需从应用机器可达。

## 凭据失效时删除与刷新

仅删除当前 master 的缓存：

```java
cache.invalidate(ssh.getHost());
// 下一次 fromSsh / fetch 因未命中而通过 SSH 获取。
K8sApiClient client = K8sApiClient.fromSsh(ssh, options);
```

也可以一次完成“删除后重新获取”：

```java
ServiceTokenFetcher fetcher = new ServiceTokenFetcher(ssh, options);
com.iskycc.k8s.ssh.MasterInfo fresh = fetcher.refresh();
K8sApiClient refreshedClient = K8sApiClient.builder()
        .apiServer(fresh.getApiServerUrl())
        .token(fresh.getToken())
        .insecureSkipTlsVerify(true)
        .build();
```

`invalidateCache()` 是 fetcher 上的同等删除入口。删除成功后若 SSH 获取失败，旧缓存不会恢复。刷新只删除 Redis 数据，不会强制删除 Kubernetes 中仍可读取的 SA/Secret；若服务端 Secret 本身仍包含失效 token，需要修复集群凭据后再次刷新。已有 `K8sApiClient` 不会随 Redis 改变，刷新后需构造新客户端。

HTTP 401 可作为重新获取凭据的信号；403 通常表示权限不足，刷新不会补齐已有绑定权限。网络错误不一定表示地址失效。库不会自动删除缓存或重放业务请求；调用方判断原因后调用刷新接口，再决定是否重试，尤其不能盲目重试已可能生效的写操作。

## CLI

```bash
export K8S_TOOLS_REDIS_URL='redis://127.0.0.1:6379/0'
java -cp 'target/k8s-tools-1.3.0-SNAPSHOT.jar:target/dependency/*' \
  com.iskycc.k8s.Main --host 192.0.2.10 --user root \
  --password '<SSH 密码>' --password-only
```

也可传 `--redis-url <redis://...>`，优先于环境变量。再次运行会复用缓存；增加 `--refresh-cache` 会先删除当前 master 的缓存再获取。未配置 Redis 时不启用缓存，`--refresh-cache` 会报参数错误。

CLI 默认直接跳过证书与主机名校验；`--strict-tls` 使用发现/缓存的 CA 或 JVM 信任库并关闭自动降级。Java 严格模式继续使用 `fetch()` 的 `MasterInfo` 加 Builder 的 `insecureSkipTlsVerify(false).tlsAutoFallback(false)`，见 [Java API 指南](library-api.md#通过-ssh-获取凭据后严格连接)。

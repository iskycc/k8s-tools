# Redis 凭据缓存与自动接入

Redis 缓存和静态 `K8sApiClient.fromSsh` 从正式版 `1.3.0` 起提供。本文的 **客户端托管 Redis** 入口 `K8sApiClient.builder().redisUrl(...).fromSsh(...)` 从正式版 `1.5.0` 起提供，使用 `io.github.iskycc:k8s-tools:1.6.0`；也可从源码 `mvn clean install` 后引用本地 `1.6.0-SNAPSHOT`。Jedis 固定为 `5.2.0`，使用方无需直接引用它的 API。

## 键与读取流程

以 master IP `192.0.2.10` 为例：

| Redis key | 类型 | value |
| --- | --- | --- |
| `192.0.2.10ServiceToken` | String | 原始 ServiceAccount Bearer Token，无 `Bearer ` 前缀 |
| `192.0.2.10ApiServerUrl` | String | 自动发现的 API 地址，例如 `https://192.0.2.10:6443` |
| `192.0.2.10ServiceTokenMetadata` | String | 内部 JSON 元数据：格式版本、SA/namespace、获取配置与 CA，用于保持凭据归属和 TLS 行为 |

key 直接使用 `SshConfig.host` 去除首尾空白后拼接后缀，不加冒号、不拼入 token 内容。请将 `host` 配置为 master IP；若填主机名，就使用该主机名字面量作为前缀，不进行 DNS 反查。

配置客户端的 `redisUrl(...)` 后，`fromSsh` 内部先用一次 `MGET` 读取以上键：

1. token、地址和匹配的元数据齐全时，返回缓存结果，**不连接 SSH、不执行 kubectl，也不向 Kubernetes 探测有效性**。应用重启或新建客户端后仍复用 Redis。
2. 缺键、空 token、地址格式错误、元数据损坏，或 SA/namespace/RBAC/发现配置改变时，通过 SSH 获取新凭据，发现 API 地址，再用一次 `MSET` 原子替换全部键。
3. 不配置 Redis 时保留每次 SSH 获取的行为；Redis 读写失败会抛 `K8sToolsException` 并保留原因，不把故障当作缓存未命中，也不静默跳过写入。

不设置 TTL，覆盖写入会清除旧 TTL；这不代表 token 永久有效。Redis 服务重启后的数据保留取决于 Redis 自身的 AOF/RDB 配置。每个 IP 保存一套凭据，切换配置会替换该 IP 的缓存；并发缓存未命中可能各自执行 SSH，本功能没有分布式锁。多键原子操作面向同一 Redis 实例/数据库，当前不支持 Redis Cluster 跨槽缓存。

## Java 接入：无需手工填写 API 地址或证书

需要一份直接运行的 `main` 程序时，使用 [K8sAllQueriesExample.java](examples/K8sAllQueriesExample.java)，按[运行指南](examples/all-queries.md)配置 SSH 和 Redis 后，即可演示本库各类查询入口、Discovery、CRD 和分页。

```java
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.ssh.SshConfig;

SshConfig ssh = SshConfig.builder()
        .host("192.0.2.10")
        .username("root")
        .password("<从应用配置读取的 SSH 密码>")
        .passwordOnly(true)
        .build();

K8sApiClient client = K8sApiClient.builder()
        .redisUrl("redis://127.0.0.1:6379/0")
        .fromSsh(ssh);
System.out.println(client.getVersion().getGitVersion());
client.listPods("default");
```

调用方只提供 SSH 和 Redis 连接配置；客户端内部创建连接池、判断缓存、获取凭据，并在 `fromSsh` 返回或抛异常前关闭自建池。业务查询期间不保持 Redis 连接，无需调用 `close()`。URI 支持 `redis://`、`rediss://`、认证及数据库编号；未指定 `redisUrl` 或传入空白值时不启用缓存。库本身不读取环境变量，Demo 和 CLI 负责将配置传入。

`fromSsh` 默认直接跳过 HTTPS 证书和主机名校验，首次写请求也适用。缓存未命中时 SSH 仍可能创建 SA、Secret、cluster-admin 绑定及按原策略重建 SA，详见 [README](../README.md#serviceaccount-与-token)。严格 TLS 可在同一 Builder 上配置：

```java
K8sApiClient strictClient = K8sApiClient.builder()
        .redisUrl("redis://127.0.0.1:6379/0")
        .insecureSkipTlsVerify(false)
        .tlsAutoFallback(false)
        .fromSsh(ssh);
```

严格模式使用发现/缓存的 CA，没有 CA 时使用 JVM 信任库。HTTP 超时仍通过 `connectTimeoutMs` 和 `readTimeoutMs` 设置。需要指定 SA、RBAC 或地址覆盖时，用 `fromSsh(ssh, new ServiceTokenFetcher.Options()...)`；客户端复制配置后使用，不修改原对象。`apiServer`、`token`、`caCertPem` 属于直接凭据接入的 `build()` 参数，不能与 `fromSsh` 混用；`redisUrl`、`refreshCache` 仅用于 `fromSsh`。

### Redis URL 带密码的格式

| 场景 | URL 示例 |
| --- | --- |
| 无密码 | `redis://127.0.0.1:6379/0` |
| 只有密码（默认用户 / requirepass） | `redis://:example-password@127.0.0.1:6379/0` |
| ACL 用户名和密码 | `redis://app-user:example-password@127.0.0.1:6379/0` |
| Redis 开启 TLS | `rediss://app-user:example-password@redis.example.com:6380/0` |

只有密码时，密码前的 `:` 不能省略；最后的 `/0` 是数据库编号，例如 `/2` 使用数据库 2。`rediss://` 要求 Redis 服务端支持 TLS，与 Kubernetes 的跳过证书设置无关。

密码含特殊字符时，仅对密码部分按 UTF-8 做百分号编码，再拼入 URL，不能编码整个 URL。例如测试密码 `p@ss:word/#%+ 中` 中，`@` → `%40`、`:` → `%3A`、`/` → `%2F`、`#` → `%23`、`%` → `%25`、`+` → `%2B`、空格 → `%20`。不要把空格写成 `+`，Jedis 不把 `+` 当作空格解码。Java 8 可这样构造：

```java
String encodedPassword = java.net.URLEncoder.encode(redisPassword, "UTF-8")
        .replace("+", "%20"); // 该代码放入可抛异常的方法中。
String redisUrl = "redis://:" + encodedPassword + "@127.0.0.1:6379/0";
K8sApiClient client = K8sApiClient.builder().redisUrl(redisUrl).fromSsh(ssh);
```

示例环境变量（仅示例密码）：

```bash
export K8S_TOOLS_REDIS_URL='redis://:p%40ss%3Aword%2F%23%25%2B%20%E4%B8%AD@127.0.0.1:6379/0'
```

上述示例对应密码 `p@ss:word/#%+ 中`。认证格式依据 [Jedis 5.2.0 的 URI 解析实现](https://github.com/redis/jedis/blob/v5.2.0/src/main/java/redis/clients/jedis/util/JedisURIHelper.java)，本项目测试同时核对实际发出的 AUTH 参数、数据库编号和特殊字符还原。

API 地址发现顺序为远端当前 kubeconfig → `/etc/kubernetes/admin.conf` → `https://<masterIP>:6443`。无效输出继续尝试下一来源；发现的 `localhost`、IPv4 回环/通配地址、`[::1]` 或 `[::]` 会换成 master 地址，并保留端口。IPv6 回退地址自动加方括号。显式 `apiServerOverride` 仍优先，且不会改写。自动发现不创建网络隧道，发现的普通域名或内网 IP 仍需从应用机器可达。

## 凭据失效时删除与刷新

通过客户端一次完成“删除后重新获取”：

```java
K8sApiClient refreshedClient = K8sApiClient.builder()
        .redisUrl("redis://127.0.0.1:6379/0")
        .refreshCache(true)
        .fromSsh(ssh);
```

`refreshCache(true)` 必须同时配置 `redisUrl`，默认值为 false。它删除该 master 的三个缓存键，再通过 SSH 获取和保存；删除成功后若 SSH 获取失败，旧缓存不会恢复。刷新不强制删除 Kubernetes 中仍可读取的 SA/Secret；服务端 Secret 本身仍包含失效 token 时，需要先修复集群凭据。已有客户端不会随 Redis 改变，业务方应使用返回的新客户端。

已发布的外部池接口 `Options.redisCache(...)`、`ServiceTokenFetcher.invalidateCache()` / `refresh()` 仍保留兼容，外部池仍由原调用方关闭；新接入直接使用上面的客户端入口。当新 Builder 的 `redisUrl` 与旧 Options 的外部缓存同时配置时，客户端使用 `redisUrl`，不修改也不关闭外部池。

HTTP 401 可作为重新获取凭据的信号；403 通常表示权限不足，刷新不会补齐已有绑定权限。网络错误不一定表示地址失效。库不会自动删除缓存或重放业务请求；调用方判断原因后调用刷新接口，再决定是否重试，尤其不能盲目重试已可能生效的写操作。

## CLI

```bash
export K8S_TOOLS_REDIS_URL='redis://127.0.0.1:6379/0'
java -cp 'target/k8s-tools-1.6.0-SNAPSHOT.jar:target/dependency/*' \
  com.iskycc.k8s.Main --host 192.0.2.10 --user root \
  --password '<SSH 密码>' --password-only
```

也可传 `--redis-url <redis://...>`，优先于环境变量。再次运行会复用缓存；增加 `--refresh-cache` 会先删除当前 master 的缓存再获取。未配置 Redis 时不启用缓存，`--refresh-cache` 会报参数错误。

CLI 默认直接跳过证书与主机名校验；`--strict-tls` 使用发现/缓存的 CA 或 JVM 信任库并关闭自动降级。Java 严格模式见上面的客户端示例；使用已发布 `1.3.0` 时仍可按 [Java API 指南](library-api.md#通过-ssh-获取凭据后严格连接)使用原有入口。

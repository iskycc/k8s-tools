# K8sInstance：统一初始化与完整工具调用

`K8sInstance` 和 `K8sTools` 是当前源码新增的公共入口，**已发布的 `1.6.0` 不包含**。先在本仓库执行 `mvn install`，业务项目引用本地 `io.github.iskycc:k8s-tools:1.6.0-SNAPSHOT`。本次未发布新版本或远端快照。

## 七个参数初始化目标集群

```java
import com.iskycc.k8s.K8sInstance;
import com.iskycc.k8s.K8sTools;
import com.iskycc.k8s.api.K8sApiClient;

K8sInstance instance = new K8sInstance(
        "192.0.2.10", 22, "root", "<SSH 原始密码>",
        "<Redis 原始密码>", "192.0.2.20", 6379);

K8sApiClient tools = K8sTools.init(instance);
```

构造参数顺序如下，端口均为 `int`：

| 参数 | 含义 |
| --- | --- |
| `ip` | master 的 SSH IP/主机名；支持 IPv4、IPv6，不能传带协议或端口的 URL |
| `port` | SSH 端口，例如 22；不是 Kubernetes API 的 6443 |
| `username` | SSH 用户名 |
| `password` | SSH 原始密码，必填；强制仅密码模式，不读取本地私钥或 SSH agent |
| `redisPassword` | Redis 原始密码；null 或空字符串表示无认证；空格不被裁剪 |
| `redisIp` | Redis IP/主机名；IPv6 可传 `2001:db8::1` 或 `[2001:db8::1]` |
| `redisPort` | Redis 端口，例如 6379 |

构造对象只校验参数，`init` 才连接 Redis，命中缓存时不连接 SSH；未命中则通过 SSH 获取凭据与 API 地址。每个实例必须配置 Redis，使用默认用户、普通 TCP、数据库 0；无需拼接 Redis URL，也不需要直接引用 Jedis。密码按原文传入，内部自动对 `@ : / # % +`、中文、空格等进行编码，**不要预先 URL 编码**。

返回值是完整 `K8sApiClient`，没有“当前集群”全局变量。可以保存多个返回值并分别调用；一个客户端不会因初始化另一个实例而改变目标。初始化结束或失败后内部 Redis 连接都会关闭，客户端无需 `close()`。缓存仍按 master IP 分键，同一个 Redis 数据库中的相同 master IP 共用一套缓存，SSH 端口不会成为独立的缓存键。

`K8sInstance` 不可变，其 `toString()` 会隐藏密码；密码 getter 或 JSON 序列化仍会暴露配置，因此不要把整个配置对象作为业务响应或日志正文。

## 全部工具功能沿用客户端方法

| 功能 | 调用示例 |
| --- | --- |
| 版本、节点、命名空间 | `tools.getVersion()`、`tools.listNodes()`、`tools.listNamespaces()` |
| 跨 namespace 简易搜索 | `tools.searchPods("web")`、`searchConfigMaps("config")`、`searchServices("web")` |
| 跨 namespace 详细搜索 | `tools.searchPodsDetailed("web")`，其他资源使用对应 `Detailed` 方法 |
| 资源增删查改 | `tools.configMaps(ns).create/get/replace/delete(...)`；Pod、Service、Deployment 等同样适用 |
| Patch / Apply | `tools.deployments(ns).patch(...)`、`apply(...)` |
| 选择器、分页 | `tools.pods(ns).list(options)`、`listAll(options)` |
| 任意资源、CRD | `tools.resource(definition)` 或 `tools.resource(apiVersion, plural)` |
| Discovery | `tools.discoverApiVersions()`、`tools.discoverResources("apps/v1")` |
| 子资源、扩缩容 | `tools.deployments(ns).subresource(name, "status").get()`、`scale(name, replicas)` |
| Pod 命令 | `tools.exec(ns, pod, "ls", "-al", "/tmp")` 或 `tools.execShell(ns, pod, "ls -al /tmp")` |

例如搜索后展示全部候选：

```java
for (com.iskycc.k8s.api.model.PodSummary pod : tools.searchPods("nginx")) {
    System.out.println(pod.getNamespace() + "/" + pod.getPodName()
            + " containers=" + pod.getContainerNames());
}
```

选定目标后执行命令（namespace、podName、container 应来自选定的同一个对象）：

```java
com.iskycc.k8s.api.PodExecResult result = tools.execShell(
        namespace, podName,
        com.iskycc.k8s.api.PodExecOptions.builder().container(container).build(),
        "ls -al /tmp");
System.out.print(result.getStdout());
System.err.print(result.getStderr());
System.out.println("退出码：" + result.getExitCode());
```

Pod Exec 保留 WebSocket/旧集群 SSH 自动选择行为，不会因使用此入口丢失 SSH 配置。完整参数和行为见 [公共 API](library-api.md)、[14 类资源搜索](resource-search.md)、[Pod Exec](pod-exec.md)。

## 显式刷新凭据

```java
// 在确认凭据需要刷新后调用，并使用返回的新客户端。
tools = K8sTools.refresh(instance);
```

刷新先删除该 master 的 token、API 地址及关联元数据，再 SSH 获取；获取失败时不会恢复旧缓存。旧客户端的凭据不变，业务请求不自动重放。401 可作为排查信号，403 通常是权限不足，不能依靠刷新保证修复。

已有自定义 SA/RBAC/地址发现配置时，沿用 `ServiceTokenFetcher.Options`，初始化和刷新传入相同配置：

```java
com.iskycc.k8s.ssh.ServiceTokenFetcher.Options options =
        new com.iskycc.k8s.ssh.ServiceTokenFetcher.Options().clusterRole("view");
tools = K8sTools.init(instance, options);
tools = K8sTools.refresh(instance, options); // 仅在需要时调用
```

不会修改传入的 Options；实例中的 Redis 配置优先于旧的外部缓存。已存在的 ClusterRoleBinding 不会被自动改为 `view`，该角色也不保证有 Pod Exec/写权限。

初始化沿用 `fromSsh` 的行为：默认跳过 HTTPS 证书及主机名校验，缓存未命中时可能创建 SA、Secret、cluster-admin 绑定，并在无法读取 token 时重建 SA。需要严格 TLS、自定义网络超时、私钥 SSH、Redis ACL 用户、TLS 或其他数据库时，继续使用原有 [Builder 接入](redis-cache.md#java-接入无需手工填写-api-地址或证书)；七参数入口采用上述固定配置。

## 可运行 main 示例

[K8sInstanceExample.java](examples/K8sInstanceExample.java) 从环境变量读取七个参数，展示版本、跨 namespace Pod/ConfigMap/Service 搜索及 Discovery；初始化后的请求均为查询。

```bash
mvn -B package dependency:copy-dependencies -DincludeScope=runtime
mkdir -p target/examples
javac -encoding UTF-8 -source 8 -target 8 \
  -cp 'target/classes:target/dependency/*' -d target/examples \
  docs/examples/K8sInstanceExample.java
java -cp 'target/examples:target/classes:target/dependency/*' K8sInstanceExample --help

export K8S_MASTER_IP='192.0.2.10'
export K8S_SSH_PORT='22'
export K8S_SSH_USER='root'
export K8S_SSH_PASSWORD='<SSH 原始密码>'
export K8S_REDIS_IP='192.0.2.20'
export K8S_REDIS_PORT='6379'
export K8S_REDIS_PASSWORD='<Redis 原始密码>'
java -cp 'target/examples:target/classes:target/dependency/*' K8sInstanceExample nginx
```

环境变量由示例读取，库不自动读取；两个密码均按原样传入。Windows classpath 分隔符改为 `;`，并使用对应 shell 的环境变量语法。

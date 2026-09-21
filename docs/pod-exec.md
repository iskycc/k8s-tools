# 在 Pod 容器中执行命令

从 **`1.5.5`** 起提供 `K8sApiClient.exec` / `execShell`、版本判断及 SSH 回退。业务项目引用 `io.github.iskycc:k8s-tools:1.6.0`，配置见 [Maven 指南](maven-usage.md)；`1.5.2` 不含这些接口。从源码构建时可执行 `mvn clean install` 并引用本地 `1.6.0-SNAPSHOT`，本次不发布该快照。

需要先通过关键词查找 Pod，再执行命令或容器内测试脚本时，参见[完整 main 示例](examples/pod-search-exec.md)与 [Java 源码](examples/K8sPodSearchExecExample.java)。

## 最小调用

```java
import com.iskycc.k8s.api.K8sApiClient;
import com.iskycc.k8s.api.PodExecResult;

// 已有 API 地址、token 和 CA 时，全流程无需 SSH 或本地 kubectl。
K8sApiClient client = K8sApiClient.builder()
        .apiServer("https://127.0.0.1:6443")
        .token(serviceToken)
        .caCertPem(caPem)
        .insecureSkipTlsVerify(false)
        .tlsAutoFallback(false)
        .build();

PodExecResult result = client.exec("default", "my-pod", "ls", "-l", "/tmp");
System.out.print(result.getStdout());
System.err.print(result.getStderr());
int exitCode = result.getExitCode();
boolean succeeded = result.isSuccess();
```

`namespace` 必须是具体命名空间；`"all"` 在此表示真实名称为 all 的命名空间。`podName` 是 Pod 名称。`command` 为 `String...`，也可传入 `String[]`；第一个元素是可执行文件，后续元素是独立参数，空参数、空格和 Unicode 会保留。不会调用本机 shell，也不会隐式解析管道、变量或重定向。

```java
// 整条命令字符串这样传入；容器需要有 /bin/sh。
PodExecResult result = client.execShell("default", "my-pod", "ls -al /tmp");
// 不经过 shell 时逐个传参，容器只需有 ls。
PodExecResult argvResult = client.exec("default", "my-pod", "ls", "-al", "/tmp");

// argv：含空格、$、分号的参数仍是一个普通参数。
client.exec("default", "my-pod", "printf", "%s", "hello world; $HOME");

// 若需要一整段 shell 命令，使用显式入口，要求容器内有 /bin/sh。
PodExecResult shellResult = client.execShell("default", "my-pod", "pwd && ls -la /tmp");
// 等价于 client.exec("default", "my-pod", "/bin/sh", "-c", "pwd && ls -la /tmp")。
```

不要把 `"ls -l /tmp"` 作为 `exec` 的单个参数；这会寻找文件名为 `ls -l /tmp` 的可执行文件。业务参数优先逐个传给 `exec`；`execShell` 的脚本文本由调用方负责，不应拼接不可信输入。

## 旧集群自动选择 SSH

已有 `fromSsh` 调用方直接复用返回的客户端，无需额外配置：

```java
K8sApiClient client = K8sApiClient.builder()
        .redisUrl(redisUrl) // 不需要缓存时可省略。
        .fromSsh(sshConfig);
PodExecResult result = client.exec("default", "my-pod", "ls", "-l", "/tmp");
```

默认 `AUTO` 在执行命令前选择通道：

| 条件 | 行为 |
| --- | --- |
| 有 SSH 配置，Kubernetes < 1.31 | 通过 SSH 调用远端 `kubectl exec` |
| 有 SSH 配置，Kubernetes >= 1.31 | 直连 API Server WebSocket |
| 没有 SSH 配置 | 直接使用 WebSocket，不探测版本 |

**1.31 是本库采用的保守兼容门槛，不是 WebSocket 首次受支持的版本。** 1.31 的 alpha/beta/rc 也选择 SSH。首次 AUTO 调用通过当前 token 请求 `GET /version`；成功解析的版本缓存在客户端实例内，后续调用复用。并发首次调用可能分别探测；集群升级后应重建客户端。版本请求失败或响应无法识别时直接报错，不执行命令，也不缓存失败。

`fromSsh`（含静态入口）会在客户端内存中保留 SSH 配置，Redis 命中也一样；Redis 仍只缓存集群凭据，不存 SSH 密码或私钥。**缓存命中仅免除初始化时的 SSH 获取，旧集群 exec 仍需 SSH 登录。** 每次 SSH exec 使用独立连接并在调用结束关闭。直接使用 API 凭据时，可在 Builder 增加 `.execSshConfig(sshConfig)`；从 `MasterInfo` 构造不会自动获得 SSH 配置。

可针对单次调用指定通道，跳过版本探测：

```java
PodExecOptions options = PodExecOptions.builder()
        .transport(PodExecOptions.Transport.SSH) // 或 WEBSOCKET；默认 AUTO。
        .build();
PodExecResult result = client.exec("default", "my-pod", options, "date");
```

显式 SSH 但未配置 SSH 时抛出 `IllegalStateException`。若已确认旧集群支持 WebSocket，可强制 `WEBSOCKET`；若新版集群的代理不支持升级，可显式选择 `SSH`。**通道一经选定，任何握手、权限或执行失败都不会切换通道重试**，避免命令执行两次。

## 指定容器与限制

```java
import com.iskycc.k8s.api.PodExecOptions;

PodExecOptions options = PodExecOptions.builder()
        .container("app")
        .timeoutMs(60000)
        .maxOutputBytes(8 * 1024 * 1024)
        .build();
PodExecResult result = client.exec("default", "my-pod", options, "printenv", "LANG");
// execShell(namespace, podName, options, command) 同样支持这些选项。
```

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| transport | AUTO | 按上述版本与 SSH 配置选择，也可强制 WEBSOCKET / SSH |
| container | 不指定 | WebSocket 由 API Server 选择或拒绝；SSH 遵循远端 kubectl 的选择规则。多容器 Pod 建议明确指定 |
| timeoutMs | 30000 | 调用总时限，含版本探测（如需）、连接、认证、握手和执行，必须大于 0 |
| maxOutputBytes | 4194304 | 收集的 stdout + stderr 字节总上限，必须大于 0；超出即失败 |

成功执行返回 stdout、stderr 和退出码；退出码非零仍返回 `PodExecResult`，由业务方判断。字符串按 UTF-8 解码，跨消息的多字节字符会合并后解码。二进制输出使用 `getStdoutBytes()` / `getStderrBytes()`；返回的数组是副本。

SSH 模式返回的是远端 kubectl 的 stdout、stderr 和退出码；stderr 可能同时含容器输出与 kubectl 的诊断信息。Pod 不存在、权限不足或 kubectl 连接 API 失败通常也表现为非零退出码，不能仅凭退出码区分业务失败与 kubectl 失败。

该入口面向一次命令的结果收集，等价于 `kubectl exec -n namespace pod -- command...`，固定 `stdin=false`、`tty=false`，因此 stdout/stderr 分离。**不提供 `-it` 交互终端、标准输入或终端尺寸调整**；交互式 shell、需要读取 stdin 的程序不适用。

## 错误处理

```java
import com.iskycc.k8s.api.K8sApiException;
import com.iskycc.k8s.api.PodExecException;

try {
    PodExecResult result = client.exec("default", "my-pod", "date");
    // 业务方根据 result.getExitCode() 处理命令结果。
} catch (PodExecException e) {
    PodExecException.Reason reason = e.getFailureReason();
    PodExecResult partial = e.getPartialResult(); // exitCode = -1，不能认定成功或失败退出
    // reason: TIMEOUT / INTERRUPTED / OUTPUT_LIMIT / PROTOCOL / TRANSPORT
    //         / REMOTE_ERROR / VERSION_DETECTION
} catch (K8sApiException e) {
    int httpStatus = e.getStatusCode(); // 例如 401、403、404、503
    // e.getResponseBody()、e.getResponseHeaders() 供业务方按需检查。
}
```

- WebSocket 只有完整、合法的 Kubernetes Status 才能确定退出码。没有 Status、截断或非法协议不能当作成功；旧服务端在完整 Status 后直接关闭 TCP 的情况兼容处理。SSH 需要有效的 SSH exit-status；缺失时抛出 TRANSPORT。
- `VERSION_DETECTION` 表示 `/version` 返回了无法识别的版本。探测时的 HTTP 错误保留为 `K8sApiException`；SSH 认证或连接错误为 TRANSPORT。SSH 内的 kubectl 错误通过结果返回，不转换成 HTTP 状态异常。
- `REMOTE_ERROR` 表示 Kubernetes 无法启动命令等远端执行错误；`getRemoteStatus()` 提供最多 64 KiB 的状态正文。HTTP 握手错误保留状态码、响应头及底层库可读取的最多 64 KiB 正文；底层握手只读取 Content-Length 正文，分块传输或不完整响应的正文可能为空。
- 超时、中断、断线、输出超限都会关闭本次连接。**断开连接不保证终止容器里的进程**，也不能证明命令没有执行；不要不加判断地重新执行有副作用的命令。
- 不自动重试、重连或跟随重定向，包括 `503 + Retry-After: 0`。调用线程被中断时保留中断标记。每次调用管理自己的连接，调用方无需 close 客户端。
- 输出上限限制本库收集的结果。WebSocket 库仍需解码单条消息，不能把该限制视为应对恶意超大 WebSocket 帧的硬性堆内存边界；大文件传输不适合此接口。

## 集群与权限

WebSocket 模式使用 API Server 的 `GET /api/v1/namespaces/{namespace}/pods/{pod}/exec` 升级，协商 `v5.channel.k8s.io` 或 `v4.channel.k8s.io`。代理/Ingress 必须允许 WebSocket Upgrade 并保留子协议头。Java 通道不实现 SPDY；旧集群通过 SSH 上的 kubectl 提供兼容执行，并设置 `KUBECTL_REMOTE_COMMAND_WEBSOCKETS=false`。协议背景见 [Kubernetes WebSocket 迁移说明](https://kubernetes.io/blog/2024/08/20/websockets-transition/)。

SSH 主机需要可用的 POSIX shell、`mktemp`、`cat`、`rm`、与集群兼容的 `kubectl`，且 `/tmp` 可写、能够访问客户端配置的 API Server 地址。该地址不会为 SSH 重写；回环地址或仅本机可达的代理地址可能不适用于另一台 SSH 主机。应用本机无需安装 kubectl。

SSH 执行使用客户端当前的 API 地址、token、CA 与 TLS 模式，**不借用 SSH 用户已有 kubeconfig 的管理员身份**。凭据通过 SSH stdin 写入权限为 600 的临时 kubeconfig，不放进命令参数；正常退出与可处理的终止信号会删除文件。强制断线、进程被强杀或主机故障不保证及时清理文件或终止远端命令，应仅使用可信的 SSH 主机。

token 需要 Pod exec 子资源权限，示例 Role（还需按实际 SA 配置 RoleBinding）：

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: pod-executor
  namespace: default
rules:
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["get", "create"]
  # SSH 模式的 kubectl 会先读取 Pod；纯 WebSocket 模式可省略此项。
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get"]
```

不同 Kubernetes 版本对 WebSocket 的授权要求可能不同，同时授予 `get`、`create` 可覆盖升级请求和新版本的执行权限检查。WebSocket 不预先查询 Pod；SSH 模式的 kubectl 需要读取 Pod。AUTO 还需允许 `GET /version`（non-resource URL，集群通常已授予认证用户；若受限需另配 ClusterRole）。是否允许执行最终由服务端 RBAC 和准入配置决定。

Exec 复用客户端当前的 token、CA 和 TLS 校验模式，但自身**不会因 TLS 失败自动降级**。`fromSsh` 默认已跳过校验，exec 同样使用该设置；要求严格校验时同时配置 `insecureSkipTlsVerify(false)` 与 `tlsAutoFallback(false)`。若同一客户端此前因普通 GET 降级，exec 会使用其降级后的模式。

严格模式下建议显式提供 CA。未提供 CA 时，WebSocket/版本探测使用 JVM 信任库，远端 kubectl 使用其系统信任库；仅导入本地 JVM 的私有 CA 不会自动传到 SSH 主机。

每次选定通道后、连接执行前打印一条 INFO，自动选择和手动指定都覆盖，例如：

```text
Pod exec 执行通道 transport=WEBSOCKET mode=AUTO version=1.31 server=https://127.0.0.1:6443 namespace=default pod=my-pod container=-
Pod exec 执行通道 transport=SSH mode=AUTO version=1.30 server=https://127.0.0.1:6443 namespace=default pod=my-pod container=-
```

`transport` 是实际选择的通道，`mode` 是传入的选项，未探测版本时 `version=-`。参数校验或版本探测失败、尚未选定通道时不打印此行。关闭全局 DEBUG 开关仍保留该 INFO（应用日志配置需允许 INFO）；它表示准备使用该通道，不表示命令已执行成功。

调用细节为 DEBUG，失败为 WARN，WebSocket 另有 execId；不记录命令、token、输出或远端错误正文。调试日志沿用 [全局开关](logging.md)。业务方如自行记录输出或异常正文，应自行控制敏感数据。

## 验证入口

- `mvn -Dtest=PodExecTest,PodExecSshFallbackTest,RedisCredentialsTest,LoggingTest test`：loopback WebSocket/HTTPS/SSH，覆盖版本边界、探测失败、参数编码与 shell 转义、缓存命中后 SSH 配置保留、v4/v5、退出码、EOF/截断、总超时/中断、部分输出、输出限制、TLS、不重放及日志脱敏。
- [真实 Kubernetes E2E](e2e.md)：在 kind 的双容器 BusyBox Pod 中验证命令、shell、容器选择、退出码、权限拒绝、超时和输出超限；另强制 SSH 验证参数与结果，并确认不会借用 master 管理员身份。具体运行结果以测试报告和 Actions 作业为准。

2026-09-21 在本地一次性 kind / Kubernetes 1.37.0 集群完成独立 Maven 使用方验证：仅引用本库，在 Java 8、21 上使用 API 地址、token 和 CA 直接执行双容器测试，覆盖上述成功与失败路径；运行 classpath 不含测试库。集群和临时凭据验证后清理。Actions 的对应测试用例已补充，远端结果以对应工作流运行记录为准；这不等于已验证所有 Kubernetes 版本或代理配置。

同日另在一次性 kind / Kubernetes 1.30.0 集群，通过实际 OpenSSH 与匹配版本 kubectl 验证 AUTO 选择 SSH：Java 8、21 均通过严格 TLS、参数边界、双容器选择、stdout/stderr、非零退出码、输出上限、超时、Pod 不存在及受限 token 拒绝访问的检查。运行仅使用生产类或主 jar 加运行依赖；测试集群、临时 SSH 服务与凭据已清理。此旧版本验证为本地测试，Actions 仍使用上述 1.37.0 集群。

# 按关键词查找 Pod，再执行命令

完整 main 示例：[K8sPodSearchExecExample.java](K8sPodSearchExecExample.java)，兼容 Java 8，使用 `io.github.iskycc:k8s-tools:1.6.0`。流程为 SSH/Redis 接入 → 跨全部 namespace 查询 Pod → 名称包含关键词匹配 → 选择容器 → `execShell` 执行整条命令并输出结果。

## 已有客户端时的核心调用

将示例类复制到自己的项目后，可直接复用其中的方法：

```java
JsonObject pod = K8sPodSearchExecExample.findPodByKeyword(client, "nginx");
String namespace = pod.getAsJsonObject("metadata").get("namespace").getAsString();
String podName = pod.getAsJsonObject("metadata").get("name").getAsString();
// 单容器传 null 自动选取；多容器传实际容器名，例如 "app"。
String container = K8sPodSearchExecExample.selectContainer(pod, null);
PodExecResult result = client.execShell(namespace, podName,
        PodExecOptions.builder().container(container).timeoutMs(30000).build(),
        "ls -al /tmp");
System.out.print(result.getStdout());
System.err.print(result.getStderr());
System.out.println("退出码：" + result.getExitCode());
```

需要导入 `com.google.gson.JsonObject` 以及 `com.iskycc.k8s.api` 下的 `PodExecOptions`、`PodExecResult`。这里的关键词是 **Pod 名称的包含匹配，区分大小写**，例如 `nginx` 匹配 `nginx-7df88c6b4c-abcde`。示例通过 `client.searchPodsDetailed(keyword, options)` 请求 `/api/v1/pods`，跨全部 namespace 用 `status.phase=Running` 查询并自动分页，再由示例跳过设置了 `deletionTimestamp` 的 Pod。SDK 返回全部名称匹配项；下述唯一目标选择只属于执行示例，公共接口本身不做此限制，详见[搜索 SDK](../resource-search.md)。

完整 Pod 名称优先匹配，但不同 namespace 中的同名 Pod 仍属于多个匹配。没有匹配时结束，匹配多个时列出 `namespace/pod` 并结束，不自动选择第一个或对所有副本执行。把关键词换为其中一个完整的 `namespace/pod`（例如 `production/nginx-abc`）后重新运行即可；即使这样指定，查询仍使用跨 namespace 接口。执行时的 namespace 和 podName 都来自同一个匹配对象的 metadata。这里的查询与执行不是原子操作，Pod 可能在两步之间重建或退出，执行失败不会重新搜索并重放命令。

## 从 SSH/Redis 开始运行

在仓库根目录编译：

```bash
mvn -B --no-transfer-progress compile dependency:copy-dependencies -DincludeScope=runtime
mkdir -p target/examples
javac -encoding UTF-8 -source 8 -target 8 \
  -cp 'target/classes:target/dependency/*' -d target/examples \
  docs/examples/K8sPodSearchExecExample.java

java -cp 'target/examples:target/classes:target/dependency/*' K8sPodSearchExecExample --help
```

设置连接信息（支持沿用[全查询示例](all-queries.md)的环境变量）：

```bash
export K8S_MASTER_IP='192.0.2.10'
export K8S_SSH_PORT='22'
export K8S_SSH_USER='root'
export K8S_SSH_PASSWORD='<SSH 密码>'
export K8S_TOOLS_REDIS_URL='redis://:example-password@127.0.0.1:6379/0'

# 跨全部 namespace 搜索单容器 Pod：名称包含 nginx，执行整条命令。
java -cp 'target/examples:target/classes:target/dependency/*' \
  K8sPodSearchExecExample nginx 'ls -al /tmp'

# 多容器 Pod：第三个参数指定实际的业务容器名 app。
java -cp 'target/examples:target/classes:target/dependency/*' \
  K8sPodSearchExecExample nginx 'ls -al /tmp' app

# 执行容器内已有的测试脚本；路径及工作目录按自己的镜像调整。
java -cp 'target/examples:target/classes:target/dependency/*' \
  K8sPodSearchExecExample nginx 'cd /app && sh ./tests/run.sh' app

# 多个匹配或跨 namespace 同名时，用结果中的 namespace/pod 精确选择。
java -cp 'target/examples:target/classes:target/dependency/*' \
  K8sPodSearchExecExample production/nginx-abc 'ls -al /tmp' app
```

以上是四种独立调用方式，选择所需的一种运行。外层单引号让整条命令作为一个参数传入 Java，变量、管道、引号等由容器内 `/bin/sh` 解释。例如 `'printf "%s\n" "$HOSTNAME"'` 使用容器内的变量。无需 shell 时改用 `exec(namespace, podName, options, "ls", "-al", "/tmp")`。

未配置 Redis URL 时不启用缓存；有缓存时由 `fromSsh` 读取，业务代码不引用 Jedis。Redis 密码含特殊字符时需要百分号编码，详见 [Redis 接入](../redis-cache.md#redis-url-带密码的格式)。无 SSH 密码时需设置 `K8S_SSH_KEY`，私钥口令用可选的 `K8S_SSH_KEY_PASSPHRASE`；密码存在时强制密码登录。无需设置 `K8S_NAMESPACE`；即使环境中已有该变量，本示例也不读取它，始终跨全部 namespace 搜索。

在其他 Maven 项目使用时，按 [Maven 配置指南](../maven-usage.md)引用 `1.6.0`，将示例复制到 `src/main/java`；运行 classpath 改为 `target/classes:target/dependency/*`。Windows 使用 `;` 分隔 classpath，并调整为对应 shell 的引号规则。

## 执行结果与边界

- 默认总超时 30 秒、stdout + stderr 上限 4 MiB；长时间执行的用例应调整示例中的 `timeoutMs`。超过限制会失败，断开连接不保证终止远端进程。
- stdout、stderr 分别写到本地标准输出与标准错误；目标信息和真实退出码也写 stderr。示例进程在命令成功时返回 0，命令或网络失败时返回 1，匹配或选择问题时返回 2。容器命令的真实退出码以 `result.getExitCode()` 为准。
- `Running` 不代表 Pod Ready，也不保证目标容器仍在运行或包含所需命令；多容器必须明确选择。本例只选择 `spec.containers` 中的普通容器，命令要求容器有 `/bin/sh`。
- 连接沿用库的行为：有 SSH 配置时低于 Kubernetes 1.31 自动使用远端 kubectl，其余用 WebSocket；每次选定通道打印 INFO，详见 [Pod Exec](../pod-exec.md)。使用方需要配置允许 INFO 的 SLF4J provider，参见[日志指南](../logging.md)。
- token 需要集群范围的 list pods 权限，以及目标 namespace 的 pods/exec 权限；SSH 路径还需 get pods，版本判断需访问 `/version`。跨 namespace 列表返回 403 时会直接失败，不静默缩小搜索范围。错误不会自动刷新 token、重复执行或切换到其他 Pod。
- SSH 初始化可能创建/复用 SA、Secret 和 cluster-admin 绑定；默认跳过 HTTPS 校验。严格 TLS 在 Builder 的 `fromSsh` 前增加 `.insecureSkipTlsVerify(false).tlsAutoFallback(false)`。凭据失效时先检查并按 [Redis 指南](../redis-cache.md)显式刷新，再决定是否重新运行。

示例会输出命令结果，结果可能包含业务敏感数据；库自己的诊断日志不会打印原始命令、token 或输出。示例放在 `docs/examples`，不会打包进库的 jar、sources 或 Javadoc。

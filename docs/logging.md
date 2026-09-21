# 日志与排障

从 `1.5.2` 起，本库通过 SLF4J 2.x 输出诊断日志，logger 名称为 `com.iskycc.k8s` 下的具体类名。业务应用使用已有的兼容 provider 和日志配置；本库只传递日志 API，不替换应用的日志实现。SLF4J 没有 provider 时会丢弃日志，详见 [SLF4J 官方说明](https://www.slf4j.org/manual.html)。

## 全局调试开关（1.5.5 起）

`1.5.5` 新增 `K8sLogging`，**默认关闭调试日志**。使用该开关需引用 `1.5.5` 或更新版本；`1.5.2` 不包含这个开关，仍通过日志框架级别控制输出。

生产环境保持默认即可，也可显式传入 JVM 参数：

```bash
java -Dk8s.tools.debug=false ...
```

或在 Java 程序中统一设置：

```java
import com.iskycc.k8s.K8sLogging;

K8sLogging.setDebugEnabled(false); // 生产：只保留 INFO、WARN、ERROR
K8sLogging.setDebugEnabled(true);  // 排查：允许输出 DEBUG
boolean enabled = K8sLogging.isDebugEnabled();
```

开关作用于同一 classloader 中所有新建和已有客户端，以及 SSH、Redis、凭据获取组件。启动参数 `-Dk8s.tools.debug=true` 在类首次初始化时读取，后续通过 `setDebugEnabled` 动态切换，无需重建客户端。关闭时，即使应用的 root logger 为 DEBUG，本库也不会输出 DEBUG；正常初始化结果、失败、TLS 降级等 INFO/WARN/ERROR 仍按日志框架的级别和 appender 配置输出。

开关不改变第三方组件的日志级别、异常与请求行为，也不关闭 CLI/Demo 的业务查询输出。没有日志 provider 或 provider 设置为 ERROR/OFF 时，不保证 INFO/WARN 可见。

## 配置日志实现和级别

已有日志框架的应用，生产环境可将 `com.iskycc.k8s` 配置为 `INFO`。需要查看单次请求时，**同时开启本库开关和 provider 的 DEBUG**；本库不会覆盖应用的日志框架配置。例如已有 Logback 配置可在 `<configuration>` 中增加：

```xml
<logger name="com.iskycc.k8s" level="DEBUG"/>
```

日志仍由应用的 root logger/appender 输出。本库不携带 `logback.xml` 或其他生产日志配置文件。

独立 Maven Demo 尚无日志 provider 时，在使用方 POM 增加以下依赖（支持 Java 8）：

```xml
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-simple</artifactId>
  <version>2.0.19</version>
  <scope>runtime</scope>
</dependency>
```

本仓库 POM 已将 Simple 声明为 `runtime` + `optional`，所以本仓库的 `dependency:copy-dependencies` 会准备它，使用方则需自行选择 provider。每个应用只保留一个 provider；升级后清理旧的 `target/dependency`，避免遗留 `slf4j-nop` 与 Simple 同时存在。

在本仓库运行完整查询 Demo，并将诊断日志单独保存：

```bash
mvn -B clean package dependency:copy-dependencies -DincludeScope=runtime
mkdir -p target/examples
javac -encoding UTF-8 -source 8 -target 8 \
  -cp 'target/classes:target/dependency/*' -d target/examples \
  docs/examples/K8sAllQueriesExample.java
java -Dk8s.tools.debug=true \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
  -Dorg.slf4j.simpleLogger.log.com.iskycc.k8s=debug \
  -Dorg.slf4j.simpleLogger.showDateTime=true \
  -Dorg.slf4j.simpleLogger.dateTimeFormat='yyyy-MM-dd HH:mm:ss.SSS' \
  -cp 'target/examples:target/classes:target/dependency/*' \
  K8sAllQueriesExample 2>target/k8s-tools.log
```

先按[全查询 Demo](examples/all-queries.md)配置 SSH/Redis 环境变量。CLI 使用相同 JVM 日志参数，把主类换为 `com.iskycc.k8s.Main` 并传入 CLI 参数即可。Simple 默认 INFO、写入 stderr，资源查询结果仍写 stdout；`k8s.tools.debug` 对所有 provider 生效，`org.slf4j.simpleLogger.*` 参数只适用于 Simple，其他 provider 通过自己的配置调整。配置字段见 [SimpleLogger 文档](https://www.slf4j.org/apidocs/org/slf4j/simple/SimpleLogger.html)。

## 如何定位

| 日志 | 级别与用途 |
| --- | --- |
| SSH 客户端初始化、连接开始/成功 | INFO：master、端口、用户、认证模式、超时配置、Redis endpoint |
| 集群凭据获取完成 | INFO：`source=redis` 表示缓存命中，`source=ssh` 表示远端获取，包含总耗时 |
| 集群凭据获取失败 | ERROR：`stage` 定位 `cache.read`、`ssh.connect`、`sa.ensure`、`token.read`、`rbac.ensure`、`api.discover`、`ca.read`、`cache.write`；`validate` 表示配置校验失败 |
| SSH 连接失败 | ERROR：`stage=connect/load-key/authenticate`，结合 `errorType` 区分网络、密钥读取或认证失败 |
| Redis 缓存未命中 | DEBUG：缺失/不完整、配置变化；元数据损坏为 WARN。Redis 操作失败为 ERROR，包含 read/write/delete |
| Redis 凭据保存/删除、显式刷新 | INFO：master、删除 key 数量、耗时；业务 401 不会触发自动重放或隐式刷新 |
| API 地址发现 | INFO：override、kubeconfig 或 admin-conf；使用 master 默认 6443 端口为 WARN |
| token controller 等待、SSH 命令完成 | DEBUG：读取次数、上限、间隔，命令退出码和耗时；非零退出码可能是资源尚不存在 |
| API 请求开始/完成 | DEBUG：进程内递增 `requestId`、method、server、path、status、`auditId` 和 `elapsedMs` |
| API 请求失败 | WARN：HTTP 错误或网络错误（`status=-1`）；404 常用于存在性检查，放在 DEBUG |
| TLS 降级、SA 删除重建、CA 缺失 | WARN：显示发生的兼容回退或有副作用的操作 |
| Pod Exec（1.5.5 起） | INFO：每次选定通道后打印 `transport=WEBSOCKET` 或 `transport=SSH`，含 mode、version、server、namespace、pod、container，DEBUG 关闭时仍保留；DEBUG：识别版本、开始与完成，WebSocket 按 execId 关联；WARN：非零退出码、版本探测/HTTP/协议失败、超时、中断。不记录命令和输出 |

API 请求开始和最终结果共享 `requestId`，可区分并发请求；它只用于当前进程的日志，没有发给服务端。服务端返回合法 UUID 格式的 `Audit-Id` 时输出 `auditId`，可据此关联 Kubernetes 审计记录，未返回或格式不合法时为 `-`。审计记录是否可用取决于集群配置。

出现 `401` 时核对凭据，按[Redis 指南](redis-cache.md)显式刷新；`403` 核对 RBAC；`409` 重新读取 `resourceVersion` 并由调用方合并；`status=-1` 结合 `errorType` 排查网络、超时或 TLS。请求耗时包含网络读取；自动 TLS 降级时包含两次尝试。

## 输出边界

本库新增的诊断日志不输出 token、SSH/Redis 密码、私钥、CA 正文、SSH 原始命令/stdout/stderr、HTTP 请求/响应正文、Authorization 或查询参数。Redis URL 只记录协议、主机、端口；异常只记录类型，原异常及响应体仍可由调用方捕获检查。不要直接把可能带正文的异常原因链或 `ExecResult` 输出到公共日志。

日志保留 master、SSH 用户、namespace、资源路径，外部字段限长并处理控制字符。排查时开启本库开关，并仅将 `com.iskycc.k8s` 的级别设为 DEBUG；第三方 HTTP wire/SSH 协议日志及应用自行打印的正文不受本库开关或过滤控制。

# Maven 坐标与接入配置

本文面向在自己项目中使用 k8s-tools 的开发者。先配置依赖，再按 [Java API 指南](library-api.md)调用接口；仓库维护者上传产物的步骤见[发布指南](publishing.md)。使用公开依赖无需配置 Central 发布账号、GitHub Secrets 或 GPG 私钥。

## 坐标与版本选择

| 项目 | 值 |
| --- | --- |
| groupId | `io.github.iskycc` |
| artifactId | `k8s-tools` |
| 正式版本 | `1.5.5`，新增 Pod Exec、旧集群 SSH 回退与全局调试开关；包含客户端托管 Redis、完整查询 Demo、通用 CRUD、Discovery、分页和子资源接口 |
| 历史快照 | `1.1.0-SNAPSHOT`，需要额外仓库，不含 1.2.0 的 SSH 改动 |
| 当前源码开发版本 | `1.5.5-SNAPSHOT`，本次不发布该快照，可从源码安装到本地 |
| Java 包名 | `com.iskycc.k8s`，与 Maven groupId 不同 |
| 使用环境 | JDK 8+、Maven 3.6.3+ |
| 依赖类型 | 普通 jar，默认 `compile` scope，无需 classifier |

本次正式版坐标为 `1.5.5`，发布完成后可下载；需要试用快照时再切换版本与仓库。旧版 `1.0.0` 只有查询接口，不能编译本指南中的通用 CRUD 调用。

## 现有项目添加正式版依赖

在使用方 POM 的 `<dependencies>` 内添加：

```xml
<dependency>
  <groupId>io.github.iskycc</groupId>
  <artifactId>k8s-tools</artifactId>
  <version>1.5.5</version>
</dependency>
```

正式版可从 Maven 默认中央仓库下载，无需 `<repositories>`，也不需要启用本仓库的 `release` 或 `snapshot` profile。仅在 `<dependencyManagement>` 中声明版本不会实际引入依赖，使用它的模块仍要在 `<dependencies>` 中声明。参见 [Maven 依赖管理说明](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html)。

## 从空项目运行一个查询示例

在新的使用方目录创建下面的 `pom.xml`。这里的编译插件固定目标为 Java 8；已有项目可沿用自己的构建配置。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>k8s-tools-demo</artifactId>
  <version>1.0.0</version>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>8</maven.compiler.release>
  </properties>

  <dependencies>
    <dependency>
      <groupId>io.github.iskycc</groupId>
      <artifactId>k8s-tools</artifactId>
      <version>1.5.5</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-dependency-plugin</artifactId>
        <version>3.8.1</version>
      </plugin>
    </plugins>
  </build>
</project>
```

将完整示例 [K8sReadExample.java](examples/K8sReadExample.java) 保存为使用方项目的 `src/main/java/K8sReadExample.java`。该示例直接使用已有 API 凭据，查询指定命名空间的 Pod 名称，不经过 SSH 初始化，也不创建集群资源。

需要从 SSH 登录、Redis 配置开始演示所有查询接口时，改用 [K8sAllQueriesExample.java](examples/K8sAllQueriesExample.java)，环境变量和命令见[完整查询 Demo 指南](examples/all-queries.md)。该 Demo 使用 `1.5.0` 新增的客户端托管 Redis 入口，引用上述坐标即可编译；`1.3.0` 不包含该入口。

在使用方项目根目录执行：

```bash
# 下载正式版依赖、编译示例并准备运行时 classpath
mvn --batch-mode --no-transfer-progress compile dependency:copy-dependencies -DincludeScope=runtime

# 仅打印帮助，不连接集群、不读取凭据
java -cp 'target/classes:target/dependency/*' K8sReadExample --help

# 将占位值替换为自己的集群配置；不要将真实 token 提交到 Git
export K8S_API_SERVER='https://192.0.2.10:6443'
export K8S_TOKEN='<ServiceAccount token>'
export K8S_CA_FILE='/absolute/path/to/ca.crt'
export K8S_NAMESPACE='default'

# 需要 API Server 可达，且 token 拥有该命名空间下 pods 的 list 权限
java -cp 'target/classes:target/dependency/*' K8sReadExample
```

Windows 的 classpath 使用 `;` 分隔并用双引号包裹。示例的环境变量读取由示例类完成，库本身不会自动读取这些变量、kubeconfig 或集群内挂载的 token。示例开启严格 TLS，并将所有分页结果收集到内存；资源较多时参考[逐页查询](library-api.md#选择器分页和作用域)。

## 使用快照版本

以下以历史快照 `1.1.0-SNAPSHOT` 为例，它不包含 `1.2.0` 的仅密码 SSH 模式。当前源码为 `1.5.5-SNAPSHOT`，本次只发布正式版，不上传新快照。使用历史快照时将依赖版本改为 `1.1.0-SNAPSHOT`，并在使用方 POM 的 `<project>` 下增加与 `<dependencies>` 同级的 `<repositories>`：

```xml
<repositories>
  <repository>
    <id>central-portal-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>
```

依赖声明为：

```xml
<dependency>
  <groupId>io.github.iskycc</groupId>
  <artifactId>k8s-tools</artifactId>
  <version>1.1.0-SNAPSHOT</version>
</dependency>
```

`repo1.maven.org` 不提供这个快照，快照通过 Sonatype 的独立仓库分发。相同快照坐标可能解析到更新的时间戳构建；用 `mvn -U compile` 刷新缓存。快照有清理策略，不应用它替代需要长期固定的正式版。参见 [Sonatype 快照说明](https://central.sonatype.org/publish/publish-portal-snapshots/)。

如果使用公司 Nexus/Artifactory 或 `mirrorOf=*`，请求可能全部转发到镜像，单独添加上面的仓库仍可能找不到快照。需要让镜像代理这个快照仓库，或由镜像管理员配置适当的路由。

[Pod Exec](pod-exec.md) 与全局调试开关从 `1.5.5` 起提供，引用上述正式版坐标即可使用。

## 依赖、日志与打包

`1.3.0` 新增 Jedis `5.2.0`、Commons Pool `2.13.1` 和 JSON-java `20260814`，用于 [Redis 凭据缓存](redis-cache.md)；已有正式版 `1.2.1` 的依赖不变。

- 完整组件版本、许可证及打包范围见 [README 开源组件清单](../README.md#开源组件与依赖范围)。从 `1.2.1` 起使用 SSHD `2.19.0` 并排除 `jcl-over-slf4j`；已有的 `1.2.0` 仍使用 SSHD `2.12.1`。
- Maven 会带入 Gson、Apache HttpClient 5、Apache MINA SSHD 等运行依赖；不要只复制 k8s-tools 的单个 jar 运行。
- 本文示例使用传递依赖中的 Gson JSON 类型。若业务代码直接大量使用 Gson，可以按项目的版本管理规则显式声明 Gson；不要随意覆盖为与本库不兼容的旧版本。
- `slf4j-simple` 是 optional，不会强制传递给使用方。项目已有日志实现时沿用现有配置，避免同时放入多个日志绑定；没有 provider 时日志不会输出；独立 Demo 可添加 Simple，配置见[日志与排障](logging.md)。
- 从 `1.2.1` 起采用 SLF4J `2.0.19`，需搭配兼容 2.x 的日志 provider；已发布的 `1.2.0` 仍为 1.7.x。升级本库时同时核对业务项目的日志实现，不能只替换 API jar 而保留旧的 1.7 binding。
- `sources` 和 `javadoc` 是 IDE 查看源码/文档的附件，不要将其作为业务依赖的 classifier。
- 自己的可执行 jar 是否包含依赖由使用方的打包方式决定；上面的示例通过 `target/dependency/*` 提供运行时依赖。
- `1.5.5` 新增 `nv-websocket-client:2.14` 作为 Pod Exec 的必要运行依赖。
- JUnit、Hamcrest、Bouncy Castle，以及 MockWebServer/OkHttp/Okio/Kotlin，仅为本库测试使用，不向下游传递；本库的主 jar、sources 和 Javadoc 不包含测试代码或模拟服务。POM 中的 `test` 声明不等于发布了这些测试依赖。

## 验证与常见问题

在使用方项目执行以下命令，不需要连接 Kubernetes：

```bash
# 查看实际选中的 k8s-tools 版本
mvn dependency:tree -Dincludes=io.github.iskycc:k8s-tools

# 检查 JSON、HTTP 和日志依赖是否被项目版本管理覆盖
mvn dependency:tree '-Dincludes=com.google.code.gson:*,org.apache.httpcomponents.client5:*,org.apache.httpcomponents.core5:*,org.slf4j:*'

# 下载源码与 Javadoc，便于 IDE 阅读
mvn dependency:resolve -Dclassifier=sources -DincludeGroupIds=io.github.iskycc
mvn dependency:resolve -Dclassifier=javadoc -DincludeGroupIds=io.github.iskycc
```

| 现象 | 检查与处理 |
| --- | --- |
| 找不到 `com.iskycc.k8s` | 确认依赖在实际模块的 `<dependencies>` 中，scope 不是 `test`，然后重新加载 Maven 项目 |
| 找不到 `resource`、`configMaps` 等方法 | 查看依赖树是否仍选中了 `1.0.0`；将坐标版本设为 `1.5.5` |
| 找不到 Builder 的 `redisUrl`、`refreshCache`、`fromSsh` 方法 | 从正式版 `1.5.0` 起提供；检查依赖树是否仍选中了 `1.3.0` 或旧快照 |
| 找不到静态 `fromSsh`、`Options.redisCache`、`ServiceTokenFetcher.refresh` 方法 | 这些方法从 `1.3.0` 起提供，检查依赖树是否仍使用 `1.2.1` 或旧快照 |
| 找不到 `passwordOnly` 方法 | 该方法从 `1.2.0` 起提供，检查依赖树是否仍使用 `1.1.0` 或旧快照 |
| 无法解析 `1.1.0-SNAPSHOT` | 确认快照仓库及 `<snapshots>` 已启用，检查镜像设置，再使用 `-U` |
| `NoClassDefFoundError` | 运行时 classpath 缺少依赖；重新执行 copy-dependencies 或检查应用打包配置 |
| `NoSuchMethodError` | 查看依赖树，核对 Gson/HttpClient 等是否被其他依赖或 BOM 覆盖 |
| TLS 错误、401、403 | 依赖已加载，问题位于集群连接或权限；按 [API 错误处理指南](library-api.md#错误与兼容性)排查 |

仓库当前 `pom.xml` 中的 `1.5.5-SNAPSHOT` 是源码开发构建版本，正式版坐标为 `1.5.5`。本指南不需要修改本仓库 POM 或运行任何发布命令。

# Maven 坐标与接入配置

本文面向在自己项目中使用 k8s-tools 的开发者。先配置依赖，再按 [Java API 指南](library-api.md)调用接口；仓库维护者上传产物的步骤见[发布指南](publishing.md)。使用公开依赖无需配置 Central 发布账号、GitHub Secrets 或 GPG 私钥。

## 坐标与版本选择

| 项目 | 值 |
| --- | --- |
| groupId | `io.github.iskycc` |
| artifactId | `k8s-tools` |
| 正式版本 | `1.1.0`，包含通用 CRUD、Discovery、分页和子资源接口 |
| 开发快照 | `1.1.0-SNAPSHOT`，需要额外配置快照仓库 |
| Java 包名 | `com.iskycc.k8s`，与 Maven groupId 不同 |
| 使用环境 | JDK 8+、Maven 3.6.3+ |
| 依赖类型 | 普通 jar，默认 `compile` scope，无需 classifier |

业务项目使用固定正式版本 `1.1.0`；需要试用快照时再切换版本与仓库。旧版 `1.0.0` 只有查询接口，不能编译本指南中的通用 CRUD 调用。

## 现有项目添加正式版依赖

在使用方 POM 的 `<dependencies>` 内添加：

```xml
<dependency>
  <groupId>io.github.iskycc</groupId>
  <artifactId>k8s-tools</artifactId>
  <version>1.1.0</version>
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
      <version>1.1.0</version>
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

将依赖版本改为 `1.1.0-SNAPSHOT`，并在使用方 POM 的 `<project>` 下增加与 `<dependencies>` 同级的 `<repositories>`：

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

## 依赖、日志与打包

- Maven 会带入 Gson、Apache HttpClient 5、Apache MINA SSHD 等运行依赖；不要只复制 k8s-tools 的单个 jar 运行。
- 本文示例使用传递依赖中的 Gson JSON 类型。若业务代码直接大量使用 Gson，可以按项目的版本管理规则显式声明 Gson；不要随意覆盖为与本库不兼容的旧版本。
- `slf4j-nop` 是 optional，不会强制传递给使用方。项目已有日志实现时沿用现有配置，避免同时放入多个日志绑定；没有日志实现时可能看到 SLF4J 提示。
- `sources` 和 `javadoc` 是 IDE 查看源码/文档的附件，不要将其作为业务依赖的 classifier。
- 自己的可执行 jar 是否包含依赖由使用方的打包方式决定；上面的示例通过 `target/dependency/*` 提供运行时依赖。

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
| 找不到 `resource`、`configMaps` 等方法 | 查看依赖树是否仍选中了 `1.0.0`；将坐标版本设为 `1.1.0` |
| 无法解析 `1.1.0-SNAPSHOT` | 确认快照仓库及 `<snapshots>` 已启用，检查镜像设置，再使用 `-U` |
| `NoClassDefFoundError` | 运行时 classpath 缺少依赖；重新执行 copy-dependencies 或检查应用打包配置 |
| `NoSuchMethodError` | 查看依赖树，核对 Gson/HttpClient 等是否被其他依赖或 BOM 覆盖 |
| TLS 错误、401、403 | 依赖已加载，问题位于集群连接或权限；按 [API 错误处理指南](library-api.md#错误与兼容性)排查 |

仓库当前 `pom.xml` 中的 `1.1.0-SNAPSHOT` 是源码开发构建版本，不影响使用方选择已发布的 `1.1.0`。本指南不需要修改本仓库 POM 或运行任何发布命令。

# Java 8 依赖版本核对

本页记录 `1.6.1` 的依赖；统一初始化入口及 `1.6.0` 的搜索 SDK 均未新增或升级依赖，沿用 `1.5.5` 的组件及 Pod Exec 依赖：原有依赖于 2026-09-20 核对；2026-09-21 将 optional 日志实现从 NOP 改为 Simple，并核对 [Simple 元数据](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-simple/maven-metadata.xml)，最新稳定版仍为 `2.0.19`，与 API 版本一致。已有正式版的 POM 不会被覆盖。

版本策略是选择**支持 Java 8 的最新稳定版本**；Jedis 按接入要求固定为 `5.2.0`，不随此策略升级。先读取 Maven Central 的完整版本列表，排除预发布版本，再核对上游最低运行 JDK、实际 jar 基础字节码和本项目在 JDK 8 下的测试。不能直接采用元数据的 `latest` / `release` 字段，因为这些字段可能指向 alpha 或 milestone。

## 运行与测试依赖

| 组件 | 本次选择 | 选择依据 |
| --- | --- | --- |
| Jedis | `5.2.0` | 按接入要求固定；[上游 POM](https://github.com/redis/jedis/blob/v5.2.0/pom.xml)使用 Java 8 编译 |
| Commons Pool | `2.13.1` | [Central 版本列表](https://repo.maven.apache.org/maven2/org/apache/commons/commons-pool2/maven-metadata.xml)的最新稳定版；[POM](https://repo.maven.apache.org/maven2/org/apache/commons/commons-pool2/2.13.1/commons-pool2-2.13.1.pom)要求 Java 8，直接声明，统一 Jedis 的下游版本 |
| JSON-java | `20260814` | [Central 版本列表](https://repo.maven.apache.org/maven2/org/json/json/maven-metadata.xml)的最新稳定版；[POM](https://repo.maven.apache.org/maven2/org/json/json/20260814/json-20260814.pom)指定 Java 8，直接声明 Jedis JSON API 所需类型，统一下游版本 |
| SSHD core / common | `2.19.0` | [Central 版本列表](https://repo.maven.apache.org/maven2/org/apache/sshd/sshd-core/maven-metadata.xml)中的最新 2.x 稳定版；3.0.0-M5 为里程碑版。[上游 2.19.0 说明](https://github.com/apache/mina-sshd/blob/sshd-2.19.0/README.md#core-requirements)要求运行时 Java 8+，构建 SSHD 本身需 Java 17+，两者不同 |
| Gson | `2.14.0` | [Central 版本列表](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/maven-metadata.xml)中的最新稳定版；[上游要求](https://google.github.io/gson/#minimum-java-version)从 2.12.0 起最低为 Java 8 |
| SLF4J API / Simple | `2.0.19` | [上游发布记录](https://www.slf4j.org/news.html)说明 2.0.x 要求 Java 8；[版本列表](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/maven-metadata.xml)中的 2.1.0-alpha1 不作为稳定版使用 |
| HttpClient | `5.6.4` | [Central 版本列表](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/client5/httpclient5/maven-metadata.xml)中的最新稳定版；5.7-alpha1 为预发布，当前版本无需升级 |
| HttpCore / HttpCore H2 | `5.4.3` | 由 HttpClient 5.6.4 引入，亦为[最新稳定版](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/core5/httpcore5/maven-metadata.xml)；5.5-beta2 为预发布 |
| JUnit 4 | `4.13.2` | 当前测试使用 JUnit 4 API；`junit:junit` 的[最新版本](https://repo.maven.apache.org/maven2/junit/junit/maven-metadata.xml)仍为 4.13.2。JUnit Jupiter 是不同坐标与 API，本次不迁移测试框架 |
| Hamcrest | `3.0` | [最新稳定版](https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest/maven-metadata.xml)，[上游说明](https://hamcrest.org/JavaHamcrest/distributables)要求 Java 8；使用 `org.hamcrest:hamcrest` 替换 JUnit 传递的旧 hamcrest-core 1.3 |
| Bouncy Castle PKIX / Provider / Util | `1.86` | 使用面向 Java 8+ 的 `*-jdk18on` 坐标；[Central 版本列表](https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-jdk18on/maven-metadata.xml)及[官方下载](https://www.bouncycastle.org/download/bouncy-castle-java/)可核对，三个组件版本一致，仅用于测试 |
| nv-websocket-client | `2.14` | 2026-09-21 核对 [Central 完整版本列表](https://repo.maven.apache.org/maven2/com/neovisionaries/nv-websocket-client/maven-metadata.xml)，仍是最新稳定版；[上游 POM](https://github.com/TakahikoKawasaki/nv-websocket-client/blob/nv-websocket-client-2.14/pom.xml)目标 Java 5，无运行传递依赖。用于一次 WebSocket 握手，不自动重定向或重试 |
| MockWebServer / OkHttp JVM | `5.5.0` | 仅 test；[Central 列表](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/mockwebserver3/maven-metadata.xml)最新稳定版；[上游 README](https://github.com/square/okhttp/blob/parent-5.5.0/README.md)要求 Java 8+，Maven 选实际 JVM artifact |
| Okio JVM | `3.18.2` | 仅 test；[Central 列表](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/maven-metadata.xml)最新稳定版；基础字节码为 Java 8 |
| Kotlin stdlib | `2.4.20` | 仅 test；[Central 列表](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml)最新稳定版；基础字节码为 Java 8。静态分析 `org.jetbrains:annotations` 排除 |

上表涵盖本库实际解析到的编译、运行和测试依赖。Maven 插件与发布工具在独立的构建 classloader 中运行，不向下游传递；其版本和执行条件继续由 POM 的 `build` / `profiles` 管理。

## 依赖隔离与兼容性

- SSHD 的 `jcl-over-slf4j` 未被当前实现使用，继续排除。
- Jedis 的 Gson 路径排除，由本项目直接提供同一 Gson；避免另一条路径把静态分析注解重新传递给使用方。
- Gson 的 `error_prone_annotations` 是静态分析注解，其 [JPMS 声明](https://github.com/google/gson/blob/gson-parent-2.14.0/gson/src/main/java/module-info.java)为 `requires static`；JSON 运行不需要它，继续保持使用方 classpath 精简。
- 直接声明 `slf4j-api:2.0.19`，避免上游依赖声明使解析结果退回 1.7.x；Simple 使用相同版本且保持 optional。使用方可通过自身 dependencyManagement 选择版本，但需自行负责兼容性。
- SLF4J 2.x 的日志 provider 采用 ServiceLoader；升级时需同步使用方日志实现，不能把旧 1.7 binding 当作 2.x provider。详情见 [SLF4J FAQ](https://www.slf4j.org/faq.html#compatibility)。
- JUnit、Hamcrest、Bouncy Castle、MockWebServer、OkHttp、Okio 和 Kotlin 都保持 test scope；主 jar、sources 和 Javadoc 不包含测试代码。

验证时同时运行 Java 8、21 的完整测试，检查实际依赖树，以及不含测试库、日志实现和静态分析注解的独立消费项目。对于 multi-release jar，Java 8 不加载 `META-INF/versions/*` 或 `module-info.class`，字节码检查应区分这些条目与基础类。

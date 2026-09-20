# 发布到 Maven Central

发布坐标为 `io.github.iskycc:k8s-tools`，许可证为 [Apache License 2.0](../LICENSE)。正式版通过 Sonatype Central Portal 的 [Maven 发布插件](https://central.sonatype.org/publish/publish-portal-maven/) 上传，配置位于 [pom.xml](../pom.xml) 的 `release` profile；快照通过 `snapshot` profile 和 Maven Deploy 插件上传到 Central Portal Snapshots。Maven `groupId` 与 Java 包名独立，Java API 的 import 仍使用 `com.iskycc.k8s`。

## 流水线行为

| 工作流 | 触发方式 | 执行内容 |
| --- | --- | --- |
| [Maven CI](../.github/workflows/ci.yml) | 推送 `main`、PR 或手动运行 | Java 8、21 上执行 `mvn clean verify`，无需发布密钥 |
| [Publish to Maven Central](../.github/workflows/publish.yml) | 发布正式 GitHub Release，或手动指定已有标签 | 检出标签、核对版本、测试、生成源码/Javadoc、GPG 签名并发布 |
| [Publish Snapshot](../.github/workflows/publish-snapshot.yml) | 手动从 `main` 运行并填写预期版本 | 核对 `X.Y.Z-SNAPSHOT` 与 POM 一致、测试、生成源码/Javadoc、GPG 签名并部署快照 |

正式版工作流只接受 `vX.Y.Z`，例如 `v1.1.0`。GitHub 预发布 Release 会被跳过；手动运行正式版工作流同样不接受 `SNAPSHOT`、`RC` 等后缀。单独推送标签不会触发自动发布，需发布 GitHub Release 或手动运行对应工作流。

每次部署包含主 jar、`-sources.jar`、`-javadoc.jar`、POM、各自产物的 `.asc` 签名和校验和。正式版工作流等待 Portal 确认发布完成；快照工作流使用 Maven 仓库协议上传并更新快照元数据。工作流不会创建 Git 提交、推送标签或回写分支上的 POM。

## 首次配置

### 1. 验证命名空间并创建发布 token

使用 GitHub 账号 `iskycc` 在 [Central Portal](https://central.sonatype.com/) 注册或登录，并在 Namespaces 页面确认 `io.github.iskycc` 状态为 **Verified**。按 [命名空间验证说明](https://central.sonatype.org/register/namespace/#github-namespaces)，使用 GitHub 账号注册会自动获得对应个人用户名的已验证命名空间；如果登录后没有自动创建的命名空间，按该说明联系 Central Support。

在 Portal 中[生成 User Token](https://central.sonatype.org/publish/generate-portal-token/)，保存返回的 username 和 password。这两个值是发布凭据，不是 GitHub token，也不是 Portal 登录密码。

### 2. 准备 GPG 签名密钥

使用已有签名密钥，或在本地创建带口令的密钥。将公钥发布到 Central 支持的公钥服务器，使 Central 能验证签名。具体要求见 [Sonatype PGP 签名说明](https://central.sonatype.org/publish/requirements/gpg/)。

```bash
# 如需新密钥，按交互提示生成，并设置口令
gpg --full-generate-key

# 查看完整指纹
gpg --list-secret-keys --keyid-format LONG

# 将占位指纹替换为实际密钥指纹，仅上传公钥
gpg --keyserver keyserver.ubuntu.com --send-keys 'YOUR_KEY_FINGERPRINT'

# 导出用于 GitHub Secret 的 ASCII-armored 私钥；导出文件不要放入仓库
gpg --armor --export-secret-keys 'YOUR_KEY_FINGERPRINT' > /tmp/k8s-tools-release-private.asc
```

将导出的私钥完整内容保存为下面的 GitHub Secret，完成配置后删除本地导出文件。私钥只导入 Actions 临时 keyring，口令通过环境变量传给 Maven GPG 插件。

### 3. 配置 GitHub Actions Secrets

在仓库 Settings → Environments 创建 `maven-central` 环境，进入该环境，在 Environment secrets 中逐项添加以下 Secrets。也可以添加到 Settings → Secrets and variables → Actions → New repository secret，名称相同即可；两处选择一处配置，无需重复添加。

| Secret | 内容 |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal User Token 的 username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal User Token 的 password |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored GPG 私钥，粘贴导出文件的完整多行文本，包含 BEGIN/END 行，无需额外 Base64 编码 |
| `MAVEN_GPG_PASSPHRASE` | 上述私钥的非空口令 |

`setup-java` 生成的 Maven server ID 为 `central`，与发布插件的 `publishingServerId` 一致。工作流在签名前检查 Secrets 是否为空，并只输出缺失项名称。普通 CI 不引用这些 Secrets。

如果环境配置了部署分支/标签限制，需要允许正式版标签 `v*` 和快照分支 `main`。工作流的 `GITHUB_TOKEN` 由 GitHub 自动提供，只需要 `contents: read`，无需额外创建 GitHub PAT 或同名 Secret；Central 上传使用上面的 User Token。

## 发布与使用快照

1. 在 Central Portal 的 Namespaces 页面确认 `io.github.iskycc` 已启用快照；如未启用，通过菜单 **Enable SNAPSHOTs** 开启。参见 [Sonatype 快照发布说明](https://central.sonatype.org/publish/publish-portal-snapshots/)。
2. 将开发版本（例如 `1.1.0-SNAPSHOT`）提交到 `main`，确认 CI 通过。
3. 在 Actions → **Publish Snapshot** → Run workflow 选择 `main`，填写 `version=1.1.0-SNAPSHOT`。工作流保留 POM 原版本，执行 `mvn -Psnapshot clean deploy`，复用上述四个 Secrets。
4. 检查工作流成功，并验证[快照元数据](https://central.sonatype.com/repository/maven-snapshots/io/github/iskycc/k8s-tools/1.1.0-SNAPSHOT/maven-metadata.xml)及对应时间戳产物可下载。

快照不需要 Git 标签或 GitHub Release，推送代码本身不会自动发布快照。相同快照版本可持续发布新的时间戳构建；它不出现在 Maven Central 正式版仓库，使用方需要额外声明快照仓库：

```xml
<repositories>
  <repository>
    <id>central-portal-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>io.github.iskycc</groupId>
    <artifactId>k8s-tools</artifactId>
    <version>1.1.0-SNAPSHOT</version>
  </dependency>
</dependencies>
```

需要刷新已有快照缓存时使用 `mvn -U ...`。Sonatype 会定期清理快照，快照不能替代固定正式版本；保留策略以其官方说明为准。

本地仅验证快照打包和签名可运行 `mvn -Psnapshot clean verify`；仅检查 sources/Javadoc 时增加 `-Dgpg.skip=true`。验证阶段不会上传；`release` 与 `snapshot` profile 应分别使用。

## 发布一个版本

`1.0.0` 已发布；当前开发分支为 `1.1.0-SNAPSHOT`，下面以准备下一版 `1.1.0` 为例。

1. 将流水线、发布 POM 和许可证提交到仓库，确保目标代码通过 CI。
2. 确定版本，例如 `1.1.0`。对应标签中的 `pom.xml` 版本必须是 `1.1.0` 或 `1.1.0-SNAPSHOT`。发布下一个版本前，先将开发版本更新为相应的 `X.Y.Z-SNAPSHOT`。
3. 在 GitHub 创建并发布正式 Release，标签为 `v1.1.0`。标签必须指向包含发布配置的提交。
4. 工作流检出 `refs/tags/v1.1.0`，核对 POM 后，只在临时检出目录将版本设为 `1.1.0`，执行 `mvn -Prelease clean deploy`。
5. 在 Actions 查看结果，必要时到 Central Portal 的 Deployments 查看校验详情。发布后等待 Maven Central 同步，再验证消费者能解析 `io.github.iskycc:k8s-tools:1.1.0`。

需要手动运行时，在 Actions → Publish to Maven Central → Run workflow 中输入已有标签，如 `v1.1.0`。手动运行也会自动发布，并非仅构建预览。

Maven Central 的正式版本不可覆盖。失败后重试前先确认 Portal 中的部署状态；如果已经发布成功，应使用新版本。相同标签的工作流会串行运行，但这不会让重复发布同一版本变得可行。

## 本地验证发布产物

发布相关检查建议在独立工作副本中执行，避免将开发分支的版本改成正式版本。只准备产物时无需 Central 凭据：

```bash
# 在独立工作副本中，将版本设为待验证的正式版本
mvn --batch-mode --no-transfer-progress \
  org.codehaus.mojo:versions-maven-plugin:2.22.0:set \
  -DnewVersion=1.1.0 -DgenerateBackupPoms=false

# 测试并生成主 jar、源码和 Javadoc，不签名、不上传
mvn --batch-mode --no-transfer-progress -Prelease -Dgpg.skip=true clean verify
```

需要验证签名时，先在本地配置 GPG 私钥，并通过 `MAVEN_GPG_PASSPHRASE` 环境变量提供口令，再执行到 `verify` 阶段：

```bash
mvn --batch-mode --no-transfer-progress -Prelease clean verify
```

该命令会测试、生成附件并签名，输出位于 `target/`；不会安装到本地 Maven 仓库或向 Portal 上传。`-Dgpg.skip=true` 生成的未签名产物不能直接用于 Central 发布。默认构建不激活 `release` profile，不要求密钥或发布账户。

实际 `deploy` 时插件才会生成 `target/central-publishing/central-bundle.zip` 并上传。POM 提供 `-Dcentral.skipPublishing=true` 作为跳过部署开关，但当前插件版本会同时跳过产物暂存，不能用它生成待上传的 bundle；插件在该模式下仍会读取 `settings.xml` 的 `central` server。仅检查本地产物时使用上面的 `verify` 命令即可。

## 排查

| 失败位置 | 检查项 |
| --- | --- |
| 标签或版本校验 | 标签是否为 `vX.Y.Z`，其提交中的 POM 是否为对应正式版本或 `-SNAPSHOT` |
| Checkout | 输入的标签是否已存在，是否指向包含发布配置的提交 |
| 缺少 Secret | 检查 `maven-central` 环境或 Repository Secrets 中的名称和非空值 |
| GPG 导入或签名 | 私钥是否完整、是否具有签名能力，口令是否正确，密钥是否过期 |
| Portal 认证或命名空间校验 | 使用的是 User Token，并且所属账号具有 `io.github.iskycc` 发布权限 |
| 快照上传返回 401/403 | 确认 token 有效、命名空间已启用 SNAPSHOTs，且环境允许 `main` 分支 |
| Portal 签名校验 | 公钥是否已上传、可获取，是否与签名私钥对应 |
| 版本已存在 | 查看 Portal 状态；已发布版本需递增版本号 |
| 等待发布超时 | 先查看 Portal 状态，再决定是否重试；不要直接重复上传 |

Central 所需元数据和产物要求以 [官方发布要求](https://central.sonatype.org/publish/requirements/) 为准。

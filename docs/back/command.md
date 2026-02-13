# remote-plugin 发布到阿里云仓库指南

[English Documentation](README.md)

本指南介绍如何把 `remote-plugin` Gradle 插件发布到阿里云私有 Maven 仓库，并在其他项目中通过 `plugins { id "io.github.iuin8.remote" version "x.y.z" }` 引入使用。

## 1. 准备工作
- 插件工程路径：`gradle/remote-plugin`
- 插件 ID：`io.github.iuin8.remote`
- 实现类：`io.github.iuin8.remote.RemotePlugin`
- 发布前请确认：
  - `group`（建议：`io.github.iuin8`）与 `version`（如：`0.1.0` 或 `0.1.1-SNAPSHOT`）已设置在 `gradle/remote-plugin/build.gradle`。
  - 你已在阿里云私有仓库（云效制品库/企业制品库/Nexus 等）创建了 Maven 仓库，并拿到以下信息：
    - Releases 仓库地址，例如：`https://packages.aliyun.com/maven/repository/<namespace>/releases/`
    - Snapshots 仓库地址，例如：`https://packages.aliyun.com/maven/repository/<namespace>/snapshots/`
    - 仓库用户名、密码（阿里云控制台可查看/生成）。

> 注意：不同阿里云产品/版本的仓库地址格式可能不同，请以控制台实际提供的 Maven 地址为准。

## 2. 增加发布能力（maven-publish）
在 `gradle/remote-plugin/build.gradle` 中确保包含：

```groovy
plugins {
    id 'groovy'
    id 'java-gradle-plugin'
    id 'maven-publish'
}

group = 'io.github.iuin8'
version = '0.1.0' // 发布版本；快照请使用 -SNAPSHOT 结尾

publishing {
    // java-gradle-plugin 会自动创建插件实现与 Marker 的 publication（无需手动声明 publications）
    repositories {
        // Releases
        maven {
            name = 'AliyunReleases'
            url = uri(findProperty('ALIYUN_RELEASE_URL') ?: System.getenv('ALIYUN_RELEASE_URL') ?: 'https://packages.aliyun.com/maven/repository/<namespace>/releases/')
            allowInsecureProtocol = false
            credentials {
                username = findProperty('ALIYUN_USERNAME') ?: System.getenv('ALIYUN_USERNAME')
                password = findProperty('ALIYUN_PASSWORD') ?: System.getenv('ALIYUN_PASSWORD')
            }
        }
        // Snapshots
        maven {
            name = 'AliyunSnapshots'
            url = uri(findProperty('ALIYUN_SNAPSHOT_URL') ?: System.getenv('ALIYUN_SNAPSHOT_URL') ?: 'https://packages.aliyun.com/maven/repository/<namespace>/snapshots/')
            allowInsecureProtocol = false
            credentials {
                username = findProperty('ALIYUN_USERNAME') ?: System.getenv('ALIYUN_USERNAME')
                password = findProperty('ALIYUN_PASSWORD') ?: System.getenv('ALIYUN_PASSWORD')
            }
        }
    }
}
```

要点：
- 不需要手动定义 `publications`，`java-gradle-plugin` 会自动生成包含插件 Marker 的 `pluginMaven` 等 publication。
- 同时配置 Releases 与 Snapshots 仓库，方便根据版本发布到不同仓库。

## 3. 安全配置凭证
将凭证放入 `~/.gradle/gradle.properties`（推荐），避免明文出现在工程。

也支持项目级本地管理：统一放置在 `support-dependencies/configs/secrets/gradle/<项目名>/secrets.properties`，并通过构建脚本自动加载。

示例模板（内容不要入库，放到你自己的私有 secrets 仓库中）：

```properties
ALIYUN_USERNAME=<your-aliyun-username>
ALIYUN_PASSWORD=<your-aliyun-password>
ALIYUN_RELEASE_URL=https://packages.aliyun.com/maven/repository/<namespace>/releases/
ALIYUN_SNAPSHOT_URL=https://packages.aliyun.com/maven/repository/<namespace>/snapshots/
NEXUS_USERNAME=<your-nexus-username>
NEXUS_PASSWORD=<your-nexus-password>
NEXUS_RELEASE_URL=http://<nexus-host>:8081/repository/maven-releases/
NEXUS_SNAPSHOT_URL=http://<nexus-host>:8081/repository/maven-snapshots/
```

目录规范：
- 插件工程：`support-dependencies/configs/secrets/gradle/remote-plugin/secrets.properties`
- 示例工程：`support-dependencies/configs/secrets/gradle/consumer-gradle6-sample/secrets.properties`

忽略规则：已在 `.gitignore` 中添加 `support-dependencies/configs/secrets/**`，确保本地秘密不会入库。建议后续将 `support-dependencies/configs/secrets` 作为私有 Git 仓库，并通过子模块关联进来。

也支持使用环境变量（`ALIYUN_USERNAME`、`ALIYUN_PASSWORD`、`ALIYUN_RELEASE_URL`、`ALIYUN_SNAPSHOT_URL`）。

## 4. 执行发布

小贴士：可先用 `./gradlew publishToMavenLocal` 验证消费端解析，再进行远端发布。

```bash
# 切到插件目录
cd gradle/remote-plugin

# 发布到 Releases 仓库（适用于非 SNAPSHOT 版本）
./gradlew publishAllPublicationsToAliyunReleasesRepository

# 发布到 Snapshots 仓库（适用于 -SNAPSHOT 结尾的版本）
./gradlew publishAllPublicationsToAliyunSnapshotsRepository

./gradlew publishAllPublicationsToAliyunSnapshotsRepository -PALIYUN_USERNAME=xxx -PALIYUN_PASSWORD=xxx --stacktrace 
```

```bash
# 在插件项目根目录执行, 发布到Nexus仓库
./gradlew publishToNexus
# 发布正式版
./gradlew publishToNexusReleases --info 
```

```bash
# 发布到gradle插件仓库
./gradlew publishPlugins
# 带参数发布（自动启用website/vcsUrl/tags）
./gradlew publishPlugins -PpublishToPortal
```

说明：
- 使用 Releases 发布时，请确保 `version` 不以 `-SNAPSHOT` 结尾；Snapshots 发布请使用 `-SNAPSHOT` 结尾。
- 未配置全局凭证时，可通过命令行参数临时传入：`-PALIYUN_USERNAME=xxx -PALIYUN_PASSWORD=xxx`。
- 可先本地验证：`./gradlew publishToMavenLocal`，消费方项目在 `settings.gradle` 中加 `pluginManagement { repositories { mavenLocal() } }` 测试解析。
- 发布产物包含插件实现 Jar 和插件 Marker（坐标类似 `io.github.iuin8.remote:io.github.iuin8.remote.gradle.plugin:<version>`）。
- Releases 仓库不允许覆盖已存在版本；若发布出现 HTTP 409（Conflict），请递增 `version`（例如 `0.1.19` → `0.1.20`）后重试。
- 快速迭代可使用 `-SNAPSHOT` 版本发布到 Snapshots 仓库（支持覆盖），命令：`./gradlew publishAllPublicationsToAliyunSnapshotsRepository`。
- 版本号位置：`gradle/remote-plugin/build.gradle` 的 `version` 字段，例如：`version = '0.1.20'`。

## 5. 在其他项目中使用

1) 通过私有仓库远程使用（推荐）：

`settings.gradle`：
```groovy
pluginManagement {
    repositories {
        maven { url 'https://packages.aliyun.com/maven/repository/<namespace>/releases/' }
        maven { url 'https://packages.aliyun.com/maven/repository/<namespace>/snapshots/' }
        gradlePluginPortal() // 可选，保留以解析其他公共插件
    }
}
```

`build.gradle`：
```groovy
plugins {
    id 'io.github.iuin8.remote' version '0.1.0' // 或已发布的其他版本
}
```

2) 不发布，当前仓库内联调：

`settings.gradle`：
```groovy
pluginManagement {
    includeBuild('gradle/remote-plugin')
}
```
然后在目标子项目 `build.gradle` 中：
```groovy
plugins { id 'io.github.iuin8.remote' }
```

## 6. 常见问题
- 401 Unauthorized：检查用户名/密码，或账户是否有 deploy 权限；确认仓库 URL 与发布类型（Releases/Snapshots）匹配。
- 404/405：通常是仓库地址不正确，务必使用阿里云控制台提供的 Maven 地址（注意末尾路径）。
- 消费端无法解析插件 ID：确认消费端 settings.gradle 的 `pluginManagement.repositories` 已包含你的私有仓库，并且已发布了插件 Marker 包。
- 公司网络代理/证书：如需代理，在 `~/.gradle/gradle.properties` 配置 `systemProp.http.proxyHost` 等；如需自签证书信任，按公司规范配置 JVM 信任库。

## 配置文件与脚本位置
- 统一配置文件：`gradle/remote-plugin/remote.yml`
- 端口配置示例：
```yaml
service:
  ports:
    app: 8080
```
- 日志配置示例：
```yaml
log:
  filePattern: ${REMOTE_BASE_DIR}/../logs/${service}.log
```
- 说明：服务端口在service_ports下配置，key为子模块名称；Arthas端口为`1`+服务端口（例如`8080 -> 18080`）。
- 发布脚本路径：`gradle/remote-plugin/publish-service.sh`，插件在执行发布任务时会将此目录作为工作目录。
- 缺失提示：当配置文件不存在或缺少端口配置时，插件会在控制台打印示例内容与路径提示，便于补全配置。

## SSH 配置管理

插件支持自动管理 SSH 配置，实现项目级 SSH 配置的开箱即用。

### 配置文件位置

在项目的插件配置目录下创建 SSH 配置文件：
```
<项目根目录>/gradle/remote-plugin/.ssh/config
```

### 配置示例

```ssh
Host cloudflared.fa.internet.company
  HostName ssh-fa-ssy-edgeone-cn.iuin888vip.icu
  User root
  IdentityFile ~/.ssh/id_ed25519_iu
  ProxyCommand cloudflared access ssh --hostname %h --protocol quic
```

### 自动注入机制

当项目中存在 `gradle/remote-plugin/.ssh/config` 文件时，插件初始化时会自动：

1. **第一层：系统配置注入**
   - 在 `~/.ssh/config` 文件开头添加插件配置引用（仅首次）：
   ```
   # Auto-generated by Gradle Remote Plugin
   # DO NOT EDIT THIS BLOCK MANUALLY
   Include ~/.ssh/gradle/remote-plugin/config
   ```

2. **第二层：项目配置注入**
   - 在 `~/.ssh/gradle/remote-plugin/config` 中添加当前项目的配置引用：
   ```
   # Project: <项目名>
   # Path: <项目路径>
   Include <项目根目录>/gradle/remote-plugin/.ssh/config
   ```

### 配置结构

```
~/.ssh/config (系统配置)
  ↓ Include
~/.ssh/gradle/remote-plugin/config (插件管理配置)
  ↓ Include
  ├─ 项目1/gradle/remote-plugin/.ssh/config
  ├─ 项目2/gradle/remote-plugin/.ssh/config
  └─ 项目N/gradle/remote-plugin/.ssh/config
```

### 重要提醒

⚠️ **避免 Host 名称冲突**

- SSH 配置使用“首次匹配”原则，遇到第一个匹配的 Host 就停止查找
- 不同项目的 `.ssh/config` 中不要配置相同的 Host 名称
- 发生 Host 名称冲突时，只有最先匹配到的配置会生效
- 项目配置通过 Include 引入，优先级高于系统配置中的后续定义

### 版本控制建议

- `remote.yml`：建议纳入 Git 管理，便于团队共享环境配置
- `.ssh/config`：根据实际情况选择
  - 如果包含敏感信息（私有主机地址、用户名等），建议添加到 `.gitignore`
  - 如果是通用配置（如跳板机配置），可以纳入版本管理

### 配置管理优势

- ✅ 项目级配置，不同项目互不干扰
- ✅ 自动注入，无需手动修改系统配置
- ✅ 集中管理，所有项目配置统一维护
- ✅ 开箱即用，克隆项目后自动生效

## 版本更新（最新版）
- 统一配置：端口配置从 `service-ports.json` 迁移到 `remote.yml`，支持更丰富的配置选项。
- 版本更新（0.1.9）
  - 目录迁移：插件默认脚本目录从 `script/` 迁移为 `gradle/remote-plugin/`。
  - 文件更名：端口配置文件统一为 `service-ports.json`（替代早期示例中的 `arthas_ports.json`）。
  - 迁移指南：
    - 将旧项目中的 `script/publish-service.sh` 移动到 `gradle/remote-plugin/publish-service.sh`。
    - 将 `script/arthas_ports.json` 或旧的 `script/service-ports.json` 统一为 `gradle/remote-plugin/service-ports.json`。
- 使用版本：在消费项目中升级插件为 `io.github.iuin8.remote:0.1.9`。

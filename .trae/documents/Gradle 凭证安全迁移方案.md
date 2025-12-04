## 问题分析
- Gradle 默认读取 `gradle.properties` 的位置仅限项目根目录与用户目录 `~/.gradle/gradle.properties`，不会读取 `gradle/wrapper` 目录中的任意属性文件；`gradle/wrapper/gradle-wrapper.properties` 只用于 Wrapper 的分发配置。
- 将凭证迁移到 `gradle/wrapper/` 并不能被当前构建脚本消费，且该目录通常被版本控制，存在泄露风险。
- 代码中仓库凭证读取位置：
  - 发布仓库：`remote-plugin/build.gradle:45-46, 55-56, 65-66, 75-76` 使用 `findProperty('ALIYUN_USERNAME') ?: System.getenv('ALIYUN_USERNAME')` 等逻辑。
  - 示例工程拉取仓库：`consumer-gradle6-sample/settings.gradle:6-7` 使用 `settings.hasProperty('ALIYUN_*') ?: System.getenv('ALIYUN_*')`。
- 插件自身环境加载（与仓库凭证无直接关系）：`RemotePluginUtils.envLoad` 会优先读取 `gradle/remote-plugin/remote.yml`，回退到 `gradle-<profile>.properties`（`RemotePluginUtils.kt:209-221`）。

## 推荐做法
- 安全优先：把账号与密码迁移到用户目录 `~/.gradle/gradle.properties`（不在仓库内），或者用环境变量；项目内仅保留非敏感默认配置。
- 项目级差异化：为每个项目提供一个不提交到仓库的本地秘密文件，如 `consumer-gradle6-sample/gradle/secrets.properties`，构建脚本显式加载它；这样不同项目不同配置、且不入库。
- 兼容现有逻辑：继续使用 `findProperty('...') ?: System.getenv('...')`，只是在构建早期把 `secrets.properties` 中的键加载到扩展属性中。

## 实施步骤
1) 创建本地秘密文件（不提交）
- 在每个需要不同凭证的项目下创建 `gradle/secrets.properties`，内容示例：
  - `ALIYUN_USERNAME=...`
  - `ALIYUN_PASSWORD=...`
  - 如需：`ALIYUN_RELEASE_URL=...`、`ALIYUN_SNAPSHOT_URL=...`、`NEXUS_*`。
- 将 `gradle/secrets.properties` 加入 `.gitignore`（保持不入库）。

2) 在示例工程加载秘密文件
- 在 `consumer-gradle6-sample/settings.gradle` 顶部添加加载逻辑：
  - 若存在 `gradle/secrets.properties`，读取其中键值，设置到 `settings` 的扩展属性；现有的 `settings.gradle:6-7` 读取逻辑即可生效。

3) 在插件工程（发布脚本）同样加载秘密文件
- 在 `remote-plugin/build.gradle` 中，在 `publishing { ... }` 之前加载 `gradle/secrets.properties`（若存在）并将键注入到项目扩展属性；发布凭证读取点位：`remote-plugin/build.gradle:45-46, 55-56, 65-66, 75-76` 将自动获得值。

4) 清理与迁移
- 从项目根 `gradle.properties` 中移除明文凭证；将它们迁移到各项目 `gradle/secrets.properties` 或 `~/.gradle/gradle.properties` 或环境变量。
- 可选：在仓库中添加一个 `gradle/secrets.properties.example` 示例文件（不含真实值），帮助同事本地配置。

5) CI/CD 兼容
- 在 CI 中通过环境变量注入，或在构建命令中使用 `-PALIYUN_USERNAME=... -PALIYUN_PASSWORD=...`。

## 验证方式
- 本地：在示例项目下放置 `gradle/secrets.properties`，运行依赖仓库的构建（如 `./gradlew build`），确认 `consumer-gradle6-sample/settings.gradle:6-7` 能获取到凭证。
- 发布：在插件工程执行 `./gradlew publish`（或相应任务），确认 `remote-plugin/build.gradle:45-46,55-56,65-66,75-76` 的凭证解析成功。
- 若未放置秘密文件，确保环境变量或 `~/.gradle/gradle.properties` 能作为后备。
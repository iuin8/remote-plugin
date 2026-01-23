package io.github.iuin8.remote

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.tasks.Exec
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Properties
import org.gradle.api.logging.Logging

// 导入工具类
import io.github.iuin8.remote.RemotePluginUtils

class RemotePlugin : Plugin<Project> {
    private val logger = Logging.getLogger(RemotePlugin::class.java)

    override fun apply(project: Project) {
        // 默认脚本目录：优先使用项目根目录下的 gradle/remote-plugin
        val scriptDirFile = File(project.rootDir, "gradle/remote-plugin")
        val scriptDir = scriptDirFile.absolutePath
        val remoteYmlFile = File(scriptDirFile, "remote.yml")

        // 使用配置合并工具扫描所有环境和服务
        val config = ConfigMerger.scanConfig(project)
        val environments = config.environments
        val configuredServices = config.configuredServices

        // 设置 SSH 配置和密钥自动管理
        SshSetupManager.setupProjectSsh(project.rootDir)
        
        // 设置 SSH 配置自动注入
        SshConfigManager.setupSshConfig(project.rootDir, project.name)

        // 核心优化：确保确认逻辑在任务执行图准备好后、任何任务执行前运行
        // 这比 Task 依赖更可靠，因为它能阻塞整个执行过程，包括 bootJar 的依赖（如 compileJava）
        val rootExtra = project.rootProject.extensions.extraProperties
        if (!rootExtra.has("remote_confirmation_listener_added")) {
            project.gradle.taskGraph.whenReady { graph ->
                if (project.gradle.startParameter.isDryRun) return@whenReady
                
                // 找到所有远程插件的任务
                val remoteTasks = graph.allTasks.filter { 
                    it.extensions.extraProperties.has("remote_profile")
                }
                
                if (remoteTasks.isNotEmpty()) {
                    val confirmedProfiles = mutableSetOf<String>()
                    val loadedProfiles = mutableSetOf<String>()

                    remoteTasks.forEach { t ->
                        val profile = t.extensions.extraProperties.get("remote_profile").toString()
                        
                        // 1. 确保环境配置已加载 (Project 级别)
                        if (!loadedProfiles.contains("${t.project.path}:$profile")) {
                            ConfigMerger.envLoad(t.project, profile)
                            loadedProfiles.add("${t.project.path}:$profile")
                        }

                        // 2. 如果是敏感任务且未确认，执行确认
                        if (t.extensions.extraProperties.has("remote_sensitive") &&
                            t.extensions.extraProperties.get("remote_sensitive") == true
                        ) {
                            if (!confirmedProfiles.contains(profile)) {
                                RemotePluginUtils.checkConfirmation(t, profile)
                                confirmedProfiles.add(profile)
                            }
                        }
                    }
                }
            }
            // 标记已添加监听器，避免在多项目构建中针对每个子项目重复添加
            rootExtra.set("remote_confirmation_listener_added", true)
        }

        // 在所有子模块注册任务
        project.subprojects { sub ->
            // 核心优化：只对在 remote.yml 的 service_ports 中定义过的子项目注册任务
            if (!configuredServices.contains(sub.name)) {
                return@subprojects
            }

            environments.forEach { profile ->
                val groupName = "remote-$profile"

                // 预检查任务 (作为占位符，逻辑已移至 whenReady)
                val preCheckTask = sub.tasks.register("${profile}_pre_check") { t ->
                    t.group = groupName
                    t.extensions.extraProperties.set("remote_sensitive", true)
                    t.extensions.extraProperties.set("remote_profile", profile)
                }

                // 强制 bootJar 在预检查之后运行 (如果存在)
                sub.tasks.matching { it.name == "bootJar" }.all { bootJar ->
                    bootJar.mustRunAfter(preCheckTask)
                }

                // 发布任务
                sub.tasks.register("${profile}_publish", Exec::class.java) { t ->
                    t.group = groupName
                    t.extensions.extraProperties.set("remote_sensitive", true)
                    t.extensions.extraProperties.set("remote_profile", profile)
                    t.dependsOn(preCheckTask)
                    publishTask(t, profile, scriptDir)
                }

                // Debug任务
                sub.tasks.register("${profile}_debug", Exec::class.java) { t ->
                    t.group = groupName
                    t.extensions.extraProperties.set("remote_sensitive", true)
                    t.extensions.extraProperties.set("remote_profile", profile)
                    t.dependsOn(preCheckTask)
                    debugTask(t, profile, scriptDir)
                }

                // Arthas任务
                sub.tasks.register("${profile}_arthas", Exec::class.java) { t ->
                    t.group = groupName
                    t.extensions.extraProperties.set("remote_profile", profile)
                    // Arthas 任务目前用户决定不需要强制确认，保持现状
                    arthasTask(t, profile, scriptDir)
                }

                // 日志任务
                sub.tasks.register("${profile}_log") { t ->
                    t.group = groupName
                    t.extensions.extraProperties.set("remote_profile", profile)
                    logTask(t, profile)
                }

                // 重启任务
                sub.tasks.register("${profile}_restart", Exec::class.java) { t ->
                    t.group = groupName
                    t.extensions.extraProperties.set("remote_sensitive", true)
                    t.extensions.extraProperties.set("remote_profile", profile)
                    t.dependsOn(preCheckTask)
                    restartTask(t, profile)
                }

                // Jenkins构建任务
                sub.tasks.register("${profile}_jenkins_build") { t ->
                    t.group = groupName
                    t.extensions.extraProperties.set("remote_sensitive", true)
                    t.extensions.extraProperties.set("remote_profile", profile)
                    t.dependsOn(preCheckTask)
                    jenkinsBuildTask(t, profile)
                }

                // Jenkins构建信息查看
                sub.tasks.register("${profile}_jenkins_last_build_info") { t ->
                    t.group = groupName
                    t.extensions.extraProperties.set("remote_profile", profile)
                    jenkinsLastBuildInfoTask(t, profile)
                }
            }
        }
    }

    companion object {

        @JvmStatic
        fun publishTask(task: Exec, profile: String, scriptDir: String) {
            RemotePluginUtils.configureTaskToDependOnBootJar(task)

            task.workingDir = File(scriptDir)
            task.doFirst {
                val extra = task.project.extensions.extraProperties
                println("[publish] 项目: ${task.project.name} 环境: $profile 服务器: ${if (extra.has("ssh.server")) extra.get("ssh.server") else "未设置"} 基础目录: ${if (extra.has("remote.base_dir")) extra.get("remote.base_dir") else "未设置"}")
                if (!extra.has("ssh.server")) {
                    val allProperties = mutableMapOf<String, Any?>()
                    extra.properties.forEach { (key, value) ->
                        allProperties[key] = value
                    }
                    throw GradleException("环境变量 ssh.server 不存在，当前所有属性: $allProperties")
                }

                // 读取服务端口并注入环境变量
                val servicePort = RemotePluginUtils.getServicePort(task, scriptDir)

                applyCommonEnvironment(task, servicePort)

                val remoteServer = extra.get("ssh.server").toString()
                val remoteBaseDir = if (extra.has("remote.base_dir")) extra.get("remote.base_dir").toString() else ""
                if (remoteBaseDir.isBlank()) throw GradleException("remote.base_dir 未设置")
                val serviceName = task.project.name
                val serviceDir = File(task.project.rootDir, serviceName)
                val libsDir = File(serviceDir, "build/libs")
                if (!libsDir.exists()) throw GradleException("未找到目录: ${libsDir.absolutePath}")
                val jar = libsDir.listFiles { f -> f.isFile && f.name.startsWith("$serviceName-") && f.name.endsWith(".jar") }?.maxByOrNull { it.lastModified() }
                if (jar == null) throw GradleException("未找到可上传的JAR: ${libsDir.absolutePath}")
                val scpTarget = "$remoteServer:$remoteBaseDir/$serviceName/"
                val scpCmdStr = "scp ${jar.absolutePath} $scpTarget"
                println("[cmd] $scpCmdStr")
                task.project.exec { 
                    applyCommonEnvironment(it, task.project.rootDir)
                    it.commandLine(RemotePluginUtils.wrapWithPty(listOf("scp", jar.absolutePath, "$remoteServer:$remoteBaseDir/$serviceName/"))) 
                    it.standardOutput = System.out
                    it.errorOutput = System.err
                }
                val sshUser = if (extra.has("ssh.user")) extra.get("ssh.user").toString() else ""
                
                val chownTarget = "$remoteBaseDir/$serviceName/$serviceName-*.jar"
                if (sshUser.isNotBlank()) {
                    val chownCmdStr = "ssh $remoteServer bash -lc 'chown $sshUser:$sshUser $chownTarget'"
                    println("[cmd] $chownCmdStr")
                    task.project.exec { 
                        applyCommonEnvironment(it, task.project.rootDir)
                        it.commandLine("ssh", remoteServer, "bash -lc 'chown $sshUser:$sshUser $chownTarget'") 
                    }
                }

                val startCmd = io.github.iuin8.remote.RemotePluginUtils.resolveStartCommand(task, remoteBaseDir, serviceName, servicePort)
                val remoteCmd = RemotePluginUtils.wrapRemoteCommand(startCmd, sshUser)
                
                println("[cmd] ssh $remoteServer $remoteCmd")
                task.commandLine("ssh", remoteServer, remoteCmd)
            }

            task.doLast {
                // 为兼容 Gradle 6，避免使用 executionResult（Gradle 7+ 才稳定提供）
                // Exec 任务在 ignoreExitValue=true 时不会使构建失败，这里不强行读取退出码
                println("[publish] ${task.project.name} 发布脚本执行完成")
            }
        }

        @JvmStatic
        fun debugTask(task: Exec, @Suppress("UNUSED_PARAMETER") profile: String, scriptDir: String) {
            task.onlyIf { task.project.tasks.findByName("bootJar") != null }
            task.isIgnoreExitValue = true
            task.doFirst {
                val extra = task.project.extensions.extraProperties
                if (!extra.has("ssh.server")) {
                    val allProperties = mutableMapOf<String, Any?>()
                    extra.properties.forEach { (key, value) -> allProperties[key] = value }
                    throw GradleException("环境变量 ssh.server 不存在，当前所有属性: $allProperties")
                }
                val remoteServer = extra.get("ssh.server").toString()
                val remoteBaseDir = if (extra.has("remote.base_dir")) extra.get("remote.base_dir").toString() else ""
                if (remoteBaseDir.isBlank()) throw GradleException("remote.base_dir 未设置")
                val serviceName = task.project.name
                val servicePort = RemotePluginUtils.getServicePort(task, scriptDir)
                val startCmd = RemotePluginUtils.resolveStartCommand(task, remoteBaseDir, serviceName, servicePort)
                val envMap = RemotePluginUtils.resolveStartEnv(task, remoteBaseDir, serviceName, servicePort)
                val export = RemotePluginUtils.buildExportEnv(envMap)
                val full = if (export.isBlank()) startCmd else "$export && $startCmd"
                
                val sshUser = if (extra.has("ssh.user")) extra.get("ssh.user").toString() else ""
                val remoteCmd = RemotePluginUtils.wrapRemoteCommand(full, sshUser)
                
                applyCommonEnvironment(task)
                println("[cmd] ssh -tt -o SendEnv=TERM -o RequestTTY=force $remoteServer $remoteCmd")
                task.setCommandLine(listOf("ssh", "-tt", "-o", "SendEnv=TERM", "-o", "RequestTTY=force", remoteServer, remoteCmd))
                task.standardInput = System.`in`
                task.standardOutput = System.out
                task.errorOutput = System.err
            }

            task.doLast {
                // 为兼容 Gradle 6，避免使用 executionResult（Gradle 7+ 才稳定提供）
                println("[debug] ${task.project.name} 调试脚本执行完成")
            }
        }

        @JvmStatic
        fun arthasTask(task: Exec, profile: String, scriptDir: String) {
            // 只需要bootjar任务存在即可，不需要依赖它
            task.onlyIf { task.project.tasks.findByName("bootJar") != null }

            task.doFirst {
                // 获取服务端口并转换为Arthas端口（1开头）
                val servicePort = RemotePluginUtils.getServicePort(task, scriptDir)
                val arthasPort = "1$servicePort"

                val extra = task.project.extensions.extraProperties
                println("[arthas] 项目: ${task.project.name} 环境: $profile 服务器: ${if (extra.has("ssh.server")) extra.get("ssh.server") else "未设置"} 基础目录: ${if (extra.has("remote.base_dir")) extra.get("remote.base_dir") else "未设置"}")
                if (!extra.has("ssh.server")) {
                    throw GradleException("环境变量 ssh.server 不存在")
                }
                applyCommonEnvironment(task, servicePort)

                val remoteServer = extra.get("ssh.server").toString()
                println("正在通过SSH连接到 $remoteServer 并启动Arthas(${task.project.name}:$arthasPort)...")
                val arthasCmdStr = "ssh -tt -o SendEnv=TERM -o RequestTTY=force $remoteServer bash -c 'stty intr ^c; export TERM=xterm; telnet localhost $arthasPort'"
                println("[cmd] $arthasCmdStr")
                task.setCommandLine(
                    listOf(
                        "ssh", "-tt",
                        "-o", "SendEnv=TERM",
                        "-o", "RequestTTY=force",
                        remoteServer,
                        "bash -c 'stty intr ^c; export TERM=xterm; telnet localhost $arthasPort'"
                    )
                )

                task.standardInput = System.`in`
                task.standardOutput = System.out
                task.errorOutput = System.err
            }
        }

        @JvmStatic
        fun restartTask(task: Exec, @Suppress("UNUSED_PARAMETER") profile: String) {
            task.onlyIf { task.project.tasks.findByName("bootJar") != null }
            task.doFirst {
                val extra = task.project.extensions.extraProperties
                println("[restart] 项目: ${task.project.name} 环境: $profile 服务器: ${if (extra.has("ssh.server")) extra.get("ssh.server") else "未设置"} 基础目录: ${if (extra.has("remote.base_dir")) extra.get("remote.base_dir") else "未设置"}")
                if (!extra.has("ssh.server")) {
                    val all = mutableMapOf<String, Any?>()
                    extra.properties.forEach { (k, v) -> all[k] = v }
                    throw GradleException("环境变量 ssh.server 不存在，当前所有属性: $all")
                }
                val remoteServer = extra.get("ssh.server").toString()
                val remoteBaseDir = if (extra.has("remote.base_dir")) extra.get("remote.base_dir").toString() else ""
                if (remoteBaseDir.isBlank()) throw GradleException("remote.base_dir 未设置")
                val serviceName = task.project.name
                val servicePort = RemotePluginUtils.getServicePort(task, "") // scriptDir is unused
                val startCmd = RemotePluginUtils.resolveStartCommand(task, remoteBaseDir, serviceName, servicePort)
                
                val sshUser = if (extra.has("ssh.user")) extra.get("ssh.user").toString() else ""
                val remoteCmd = RemotePluginUtils.wrapRemoteCommand(startCmd, sshUser)
                
                applyCommonEnvironment(task)
                println("[cmd] ssh -tt -o SendEnv=TERM -o RequestTTY=force $remoteServer $remoteCmd")
                task.setCommandLine(listOf("ssh", "-tt", "-o", "SendEnv=TERM", "-o", "RequestTTY=force", remoteServer, remoteCmd))
                task.standardInput = System.`in`
                task.standardOutput = System.out
                task.errorOutput = System.err
            }
        }

        @JvmStatic
        fun logTask(task: Task, profile: String) {
            // 只需要bootjar任务存在即可，不需要依赖它
            task.onlyIf { task.project.tasks.findByName("bootJar") != null }

            task.doFirst {
                val extra = task.project.extensions.extraProperties
                println("[log] 项目: ${task.project.name} 环境: $profile 服务器: ${if (extra.has("ssh.server")) extra.get("ssh.server") else "未设置"} 基础目录: ${if (extra.has("remote.base_dir")) extra.get("remote.base_dir") else "未设置"}")
                if (!extra.has("ssh.server")) {
                    val allProperties = mutableMapOf<String, Any?>()
                    extra.properties.forEach { (key, value) ->
                        allProperties[key] = value
                    }
                    throw GradleException("环境变量不存在，当前所有属性: $allProperties")
                }
            }

            task.doLast {
                val serviceName = task.project.name
                val extra = task.project.extensions.extraProperties
                val remoteServer = extra.get("ssh.server").toString()
                val remoteBaseDir = if (extra.has("remote.base_dir")) extra.get("remote.base_dir").toString() else ""
                val servicePort = RemotePluginUtils.getServicePort(task, "") // scriptDir is unused
                val logFilePath = RemotePluginUtils.resolveLogFilePath(task, serviceName, remoteBaseDir, servicePort)

                println("找到服务 $serviceName")
                println("正在通过SSH连接到 $remoteServer 并开始打印服务 $serviceName 的日志 $logFilePath")
                val cmdStr = "ssh $remoteServer ${RemotePluginUtils.buildRemoteTailCmd(logFilePath)}"
                println("[cmd] $cmdStr")

                task.project.exec { execSpec ->
                    applyCommonEnvironment(execSpec, task.project.rootDir)
                    execSpec.environment("TERM", "xterm")
                    execSpec.isIgnoreExitValue = true
                    execSpec.commandLine(
                        "ssh",
                        remoteServer,
                        RemotePluginUtils.buildRemoteTailCmd(logFilePath)
                    )
                    execSpec.standardOutput = System.out
                    execSpec.errorOutput = System.err
                }
                println("日志流结束")
            }
        }

        /**
         * 应用公共环境变量
         */
        private fun applyCommonEnvironment(task: Exec, servicePort: String? = null) {
            val extra = task.project.extensions.extraProperties
            val envMap = mutableMapOf(
                "TERM" to "xterm",
                "LOCAL_BASE_DIR" to task.project.rootDir.absolutePath,
                "RP_PROJECT_ROOT_PATH" to task.project.rootDir.absolutePath,
                "REMOTE_SERVER" to if (extra.has("ssh.server")) extra.get("ssh.server").toString() else "",
                "REMOTE_BASE_DIR" to if (extra.has("remote.base_dir")) extra.get("remote.base_dir").toString() else "",
                "SERVICE_NAME" to task.project.name,
                "SERVICE_DIR" to File(task.project.rootDir, task.project.name).absolutePath
            )
            if (servicePort != null) {
                envMap["SERVICE_PORT"] = servicePort
            }
            task.environment(envMap)
        }

        private fun applyCommonEnvironment(execSpec: org.gradle.process.ExecSpec, rootDir: File) {
            execSpec.environment("RP_PROJECT_ROOT_PATH", rootDir.absolutePath)
        }

        /**
         * Jenkins构建任务
         * 使用 jenkins-client 库实现
         */
        @JvmStatic
        fun jenkinsBuildTask(task: Task, platform: String) {
            JenkinsTask.jenkinsBuildTask(task, platform)
        }

        /**
         * Jenkins构建任务
         * 使用 jenkins-client 库实现
         */
        @JvmStatic
        fun jenkinsLastBuildInfoTask(task: Task, platform: String) {
            JenkinsTask.jenkinsLastBuildInfoTask(task, platform)
        }
    }
}
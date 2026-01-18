package io.github.iuin8.remote

import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecResult
import org.gradle.api.plugins.ExtraPropertiesExtension
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Scanner

/**
 * RemotePlugin 工具类，包含非任务相关的辅助方法
 */
object RemotePluginUtils {
    /**
     * 判断是否为 Windows 系统
     */
    fun isWindows(): Boolean {
        return System.getProperty("os.name").toLowerCase().contains("windows")
    }

    /**
     * 判断是否为 Mac 系统
     */
    fun isMac(): Boolean {
        return System.getProperty("os.name").toLowerCase().contains("mac")
    }

    /**
     * 使用 script 命令包装以模拟 TTY (仅支持 Unix-like)
     * 在 Gradle exec 中强制 scp/ssh 显示进度条时非常有用
     */
    fun wrapWithPty(args: List<String>): List<String> {
        if (isWindows()) return args
        
        // macOS 和 Linux 的 script 命令用法略有不同
        // macOS: script -q /dev/null command args...
        // Linux: script -q -c "command args..." /dev/null
        
        return if (isMac()) {
            listOf("script", "-q", "/dev/null") + args
        } else {
            // 兜底 Linux 或其他 Unix
            val fullCmd = args.joinToString(" ") { 
                if (it.contains(" ") || it.contains("\"") || it.contains("$")) "\"${it.replace("\"", "\\\"")}\"" else it 
            }
            listOf("script", "-q", "-c", fullCmd, "/dev/null")
        }
    }

    /**
     * 替换配置值中的占位符
     */
    fun replacePlaceholders(value: String, serviceName: String, remoteBaseDir: String, servicePort: String): String {
        return value
            .replace("${'$'}{service}", serviceName)
            .replace("${'$'}{SERVICE_NAME}", serviceName)
            .replace("${'$'}{remote.base.dir}", remoteBaseDir)
            .replace("${'$'}{REMOTE_BASE_DIR}", remoteBaseDir)
            .replace("${'$'}{SERVICE_PORT}", servicePort)
            .replace("${'$'}service", serviceName)
            .replace("${'$'}SERVICE_NAME", serviceName)
            .replace("${'$'}REMOTE_BASE_DIR", remoteBaseDir)
            .replace("${'$'}SERVICE_PORT", servicePort)
    }
    
    /**
     * 替换配置值中的占位符（仅使用服务名）
     */
    fun replacePlaceholders(value: String, serviceName: String): String {
        return value
            .replace("${'$'}{service}", serviceName)
            .replace("${'$'}{SERVICE_NAME}", serviceName)
            .replace("${'$'}service", serviceName)
            .replace("${'$'}SERVICE_NAME", serviceName)
    }
    
    // parseSimpleYaml 已移除，请使用 ConfigMerger.parseSimpleYamlWithBase

    fun getJenkinsConfig(task: Task, @Suppress("UNUSED_PARAMETER") profile: String): Map<String, String?> {
        val extra = task.extensions.extraProperties
        val serviceName = task.project.name
        
        val url = if (extra.has("jenkins.url")) extra.get("jenkins.url").toString() else null
        val user = if (extra.has("jenkins.user")) extra.get("jenkins.user").toString() else null
        val token = if (extra.has("jenkins.token")) extra.get("jenkins.token").toString() else null
        var jobPath = if (extra.has("jenkins.job")) extra.get("jenkins.job").toString() else null
        
        if (jobPath != null) {
            jobPath = replacePlaceholders(jobPath, serviceName)
            if (!jobPath.contains(serviceName)) {
                jobPath = "$jobPath/$serviceName"
            }
        }
        
        return mapOf(
            "url" to url,
            "user" to user,
            "token" to token,
            "job" to jobPath
        )
    }
    
    fun resolveLogFilePath(task: Task, serviceName: String, remoteBaseDir: String, servicePort: String): String {
        val extra = task.extensions.extraProperties
        val pattern = if (extra.has("log.filePattern")) extra.get("log.filePattern").toString() else null
        if (pattern != null) {
            return replacePlaceholders(pattern, serviceName, remoteBaseDir, servicePort)
        }
        return "$remoteBaseDir/../logs/$serviceName.log"
    }
    
    fun resolveStartCommand(task: Task, remoteBaseDir: String, serviceName: String, servicePort: String): String {
        val extra = task.extensions.extraProperties
        var cmd = if (extra.has("start.command")) extra.get("start.command").toString() else "$remoteBaseDir/$serviceName/$serviceName-start.sh"
        return replacePlaceholders(cmd, serviceName, remoteBaseDir, servicePort)
    }

    fun resolveStartEnv(task: Task, remoteBaseDir: String, serviceName: String, servicePort: String): Map<String, String> {
        val extra = task.extensions.extraProperties
        val result = mutableMapOf<String, String>()
        extra.properties.forEach { (k, v) ->
            if (k.startsWith("env.")) {
                val key = k.substring("env.".length)
                val value = replacePlaceholders(v.toString(), serviceName, remoteBaseDir, servicePort)
                result[key] = value
            }
        }
        return result
    }

    fun buildExportEnv(env: Map<String, String>): String {
        if (env.isEmpty()) return ""
        return env.entries.joinToString(separator = " ", prefix = "export ") {
            (k, v) ->
            val valEsc = v.replace("'", "'\\''")
            "$k='$valEsc'"
        }
    }

    fun buildRemoteTailCmd(logFilePath: String): String {
        return """bash -lc 'tail -fn10000 $logFilePath & pid=${'$'}!; trap "kill -TERM ${'$'}pid" EXIT; wait ${'$'}pid'"""
    }

    /**
     * 智能包装远程命令，同时兼顾安全性和可读性
     * 1. 如果指定了 user，使用 su - user -c '...' 包装
     * 2. 外部使用 bash -lc '...' 包装以确保加载环境变量
     * 3. 内部优先使用双引号包装以减少单引号转义带来的 '\''' 丑陋输出
     */
    fun wrapRemoteCommand(command: String, user: String): String {
        val wrappedUser = if (user.isBlank()) {
            command
        } else {
            // 如果命令中包含双引号或 $，则使用单引号包装以保安全
            // 否则使用双引号以提供更好的可读性（避免 '\'''）
            if (command.contains("\"") || command.contains("$")) {
                val escaped = command.replace("'", "'\\''")
                "su - $user -c '$escaped'"
            } else {
                "su - $user -c \"$command\""
            }
        }
        
        // 外部始终使用单引号，因为这是作为 SSH 的最后一个参数传递的
        // 即使内部用了双引号，外部的单引号也能防止本地 Shell 解析
        val escapedBash = wrappedUser.replace("'", "'\\''")
        return "bash -lc '$escapedBash'"
    }

    /**
     * 加载环境配置
     * 支持配置继承机制
     */
    fun envLoad(task: Task, profile: String): Boolean {
        // 尝试从remote.yml读取环境配置
        val scriptDirFile = File(task.project.rootDir, "gradle/remote-plugin")
        val remoteYmlFile = File(scriptDirFile, "remote.yml")
        val extra: ExtraPropertiesExtension = task.extensions.extraProperties
        
        if (remoteYmlFile.exists()) {
            try {
                // 使用新的配置合并机制
                val mergedConfig = ConfigMerger.getMergedConfigForEnvironment(remoteYmlFile, profile)
                
                // 应用配置到任务属性
                val loadedProperties = mutableMapOf<String, String>()
                mergedConfig.entries.forEach { (key, value) ->
                    extra.set(key, value)
                    loadedProperties[key] = value
                }
                
                if (loadedProperties.isNotEmpty()) {
                    println("[remote-plugin] 成功从remote.yml加载 ${loadedProperties.size} 个环境 $profile 的配置项")
                    return true
                } else {
                    println("[DEBUG-envLoad] No config found for environment $profile in remote.yml")
                }
            } catch (e: ConfigMerger.ConfigException) {
                println("[DEBUG-envLoad] 配置错误: ${e.message}")
                e.printStackTrace()
            } catch (e: Exception) {
                println("[DEBUG-envLoad] Error parsing remote.yml: ${e.message}")
                e.printStackTrace()
            }
            
            // SshSetupManager 相关的逻辑，用于在初始化阶段获取 autoKeygen 配置
            try {
                // SshSetupManager 属于初始化阶段，此时还没有 profile，直接解析
                val parsedConfig = ConfigMerger.parseSimpleYamlWithBase(remoteYmlFile)
                // 查找所有 environments 中的 ssh.setup.auto.keygen，或者 common 中的
                // 这里为了精简，我们先取 base 配置（通常是 common.base）
                val baseConfig = parsedConfig.commonConfigs["base"] ?: emptyMap()
                val autoKeygen = (baseConfig["ssh.setup.auto.keygen"]?.toBoolean() ?: false)
                // 将 autoKeygen 存储到 extra properties，以便 SshSetupManager 访问
                extra.set("ssh.setup.auto.keygen", autoKeygen)
                println("[DEBUG-envLoad] 从remote.yml加载 ssh.setup.auto.keygen: $autoKeygen")
            } catch (e: Exception) {
                println("[DEBUG-envLoad] Error parsing remote.yml for ssh.setup.auto.keygen: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("[DEBUG-envLoad] remote.yml file not found: ${remoteYmlFile.absolutePath}")
        }
        
        return false
    }

    /**
     * 配置任务依赖于bootJar任务（如果存在）
     * 1. 设置任务只在bootJar任务存在时执行
     * 2. 对于Exec类型任务，添加对bootJar任务的依赖关系
     */
    fun configureTaskToDependOnBootJar(task: Task) {
        // 使用字符串名称依赖，即使 bootJar 任务是延迟注册的也能正确建立依赖关系
        // 这样可以避免在任务配置闭包中执行 Matching/All 导致的 Context 错误
        task.dependsOn("bootJar")
        // 只有存在 bootJar 任务时才执行
        task.onlyIf { task.project.tasks.findByName("bootJar") != null }
    }

    fun getServicePort(task: Task, @Suppress("UNUSED_PARAMETER") scriptDir: String = ""): String {
        val extra = task.extensions.extraProperties
        val serviceName = task.project.name
        val port = if (extra.has("service_ports.$serviceName")) extra.get("service_ports.$serviceName").toString() else null
        
        if (port == null) {
            val msg = """
[remote-plugin] 未找到服务 $serviceName 的端口映射
[remote-plugin] 请在 remote.yml 的 service_ports 下添加条目:
service_ports:
    $serviceName: 8080
"""
            throw GradleException(msg)
        }
        return port
    }

    /**
     * 检查用户确认，用于生产环境任务安全防护
     */
    fun checkConfirmation(task: Task, profile: String) {
        val extra = task.extensions.extraProperties
        
        // 1. 检查命令行属性绕过 -Pstart.need_confirm=false
        if (task.project.hasProperty("start.need_confirm")) {
            val prop = task.project.property("start.need_confirm").toString()
            if (prop == "false") return
        }
        
        // 2. 获取配置项 need_confirm (从 remote.yml 加载)
        var needConfirm: Boolean? = if (extra.has("start.need_confirm")) {
            extra.get("start.need_confirm").toString().toBoolean()
        } else {
            null
        }
        
        // 3. 智能默认值：如果未显式配置，且环境名包含 prod，则默认为 true
        if (needConfirm == null) {
            needConfirm = profile.toLowerCase().contains("prod")
        }
        
        if (!needConfirm) return

        // 4. 执行确认逻辑
        println("\n" + "=".repeat(60))
        println("⚠️  警告: 检测到当前环境为 '$profile'")
        println("   根据配置或环境识别，此任务需要用户确认。")
        println("=".repeat(60))
        
        var input: String? = null
        val console = System.console()
        if (console != null) {
            input = console.readLine("🔔 确定要继续执行吗？ [y/N]: ")
        } else {
            // 尝试使用 Scanner (兼容 IDE 运行窗口)
            print("🔔 确定要继续执行吗？ [y/N]: ")
            System.out.flush()
            try {
                val scanner = Scanner(System.`in`)
                if (scanner.hasNextLine()) {
                    input = scanner.nextLine()
                }
            } catch (e: Exception) {
                // 读取失败通常意味着非交互式环境
            }
        }
        
        if (input == null || !input.trim().equals("y", ignoreCase = true)) {
            if (console == null && input == null) {
                throw GradleException(
                    "检测到敏感操作确认，但当前为非交互式环境 (无 Console 且 Stdin 不可读)。\n" +
                    "如果是自动化脚本，请加上 -Pstart.need_confirm=false 以跳过确认。"
                )
            }
            throw GradleException("❌ 任务已由用户取消。")
        }
        println("✅ 确认成功，继续执行任务...\n")
    }
}
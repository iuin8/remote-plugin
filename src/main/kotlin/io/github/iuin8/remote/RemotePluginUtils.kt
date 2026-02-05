package io.github.iuin8.remote

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.Logging
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
    private val logger = Logging.getLogger(RemotePluginUtils::class.java)

    /**
     * 判断是否为 Windows 系统
     */
    fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase().contains("windows")
    }

    /**
     * 判断是否为 Mac 系统
     */
    fun isMac(): Boolean {
        return System.getProperty("os.name").lowercase().contains("mac")
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
     * 解析字符串中的占位符 ${VAR}，依次从以下来源获取值：
     * 1. Project 属性 (gradle.properties 或 -P 参数)
     * 2. 环境变量 (System.getenv)
     */
    fun resolvePlaceholders(value: String, project: Project): String {
        val regex = Regex("\\$\\{([^}]+)}")
        return regex.replace(value) { matchResult ->
            val key = matchResult.groupValues[1]
            val resolvedValue = if (project.hasProperty(key)) {
                project.property(key)?.toString()
            } else {
                System.getenv(key)
            }
            resolvedValue ?: matchResult.value
        }
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
    


    fun getJenkinsConfig(extra: Map<String, Any?>, serviceName: String): Map<String, String?> {
        val url = extra["jenkins.url"]?.toString()
        val user = extra["jenkins.user"]?.toString()
        val token = extra["jenkins.token"]?.toString()
        var jobPath = extra["jenkins.job"]?.toString()
        
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
    
    fun resolveLogFilePath(extra: Map<String, Any?>, serviceName: String, remoteBaseDir: String, servicePort: String): String {
        val pattern = extra["log.filePattern"]?.toString()
        if (pattern != null) {
            return replacePlaceholders(pattern, serviceName, remoteBaseDir, servicePort)
        }
        return "$remoteBaseDir/../logs/$serviceName.log"
    }
    
    fun resolveLogCommand(extra: Map<String, Any?>, serviceName: String, remoteBaseDir: String, servicePort: String, logFilePath: String): String {
        val command = extra["log.command"]?.toString() ?: "tail -fn10000 ${'$'}{log.file}"
        return replacePlaceholders(command, serviceName, remoteBaseDir, servicePort)
            .replace("${'$'}{log.file}", logFilePath)
            .replace("${'$'}log.file", logFilePath)
    }
    
    fun resolveStartCommand(extra: Map<String, Any?>, remoteBaseDir: String, serviceName: String, servicePort: String): String {
        var cmd = extra["start.command"]?.toString() ?: "$remoteBaseDir/$serviceName/$serviceName-start.sh"
        return replacePlaceholders(cmd, serviceName, remoteBaseDir, servicePort)
    }

    fun resolveStartEnv(extra: Map<String, Any?>, remoteBaseDir: String, serviceName: String, servicePort: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        extra.forEach { (k, v) ->
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
     * 配置任务依赖于 build 任务
     */
    fun configureTaskToDependOnBuild(sub: Project, task: Task) {
        // 直接将 TaskCollection 传入 dependsOn，Gradle 会自动处理延迟注册的任务
        task.dependsOn(sub.tasks.matching { it.name == "build" })
        
        // 只有存在 build 任务时才执行
        if (task is BaseRemoteTask) {
            task.onlyIf {
                val dir = task.projectDir.get()
                // 检查 build/libs 目录是否存在 (如果是 java/boot 项目通常会有这个目录)
                File(dir, "build/libs").exists()
            }
        }
    }

    fun getServicePort(extra: Map<String, Any?>, serviceName: String): String {
        val port = extra["service_ports.$serviceName"]?.toString()
        
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
    fun checkConfirmation(
        profile: String,
        needConfirm: Boolean,
        hasConfirmProperty: Boolean,
        confirmPropertyValue: String?
    ) {
        // 1. 检查命令行属性绕过 -Pstart.need_confirm=false
        if (hasConfirmProperty && confirmPropertyValue == "false") {
            return
        }
        
        if (!needConfirm) return

        // 2. 执行确认逻辑
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
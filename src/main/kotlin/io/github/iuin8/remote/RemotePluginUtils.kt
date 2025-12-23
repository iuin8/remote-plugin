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

/**
 * RemotePlugin 工具类，包含非任务相关的辅助方法
 */
object RemotePluginUtils {
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

    fun getJenkinsConfig(task: Task, profile: String): Map<String, String?> {
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
        task.onlyIf { task.project.tasks.findByName("bootJar") != null }
        if (task is Exec) {
            if (task.project.tasks.findByName("bootJar") != null) {
                task.dependsOn("bootJar")
            }
        }
    }

    fun getServicePort(task: Task, scriptDir: String): String {
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
                .trimIndent()
            throw GradleException(msg)
        }
        return port
    }
}
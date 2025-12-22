package io.github.iuin8.remote

import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import java.io.File
import java.util.Properties
import org.gradle.api.plugins.ExtraPropertiesExtension

/**
 * RemotePlugin 工具类，包含非任务相关的辅助方法
 */
object RemotePluginUtils {
    /**
     * 解析YAML配置文件，支持嵌套结构
     */
    fun parseSimpleYaml(file: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val prefixStack = mutableListOf<String>()
        
        file.forEachLine { raw ->
            // 计算缩进级别
            val indentCount = raw.takeWhile { it == ' ' }.length
            val line = raw.trim()
            
            // 跳过空行和注释
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            
            // 处理键值对或嵌套键
            if (line.endsWith(":")) {
                // 嵌套键（只有冒号，没有值）
                val key = line.substringBeforeLast(":").trim()
                
                // 根据缩进级别调整前缀栈
                while (prefixStack.size > indentCount / 2) {
                    prefixStack.removeLast()
                }
                
                // 添加当前键到前缀栈
                prefixStack.add(key)
            } else if (line.contains(":")) {
                // 键值对（包含冒号和值）
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    var value = parts[1].trim()
                    
                    // 构建完整的嵌套键名（如 log.filePattern）
                    val fullKey = if (prefixStack.isNotEmpty()) {
                        prefixStack.joinToString(".") + "." + key
                    } else {
                        key
                    }
                    
                    // 移除引号
                    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length - 1)
                    }
                    
                    map[fullKey] = value
                }
            }
        }
        return map
    }

    /**
     * 读取Jenkins配置
     * 支持在job路径中使用 ${service} 占位符，或自动添加服务名
     */
    fun getJenkinsConfig(task: Task, profile: String): Map<String, String?> {
        val scriptDirFile = File(task.project.rootDir, "gradle/remote-plugin")
        val cfgFile = File(scriptDirFile, "remote.yml")
        if (!cfgFile.exists()) {
            return emptyMap()
        }

        val config = parseSimpleYaml(cfgFile)
        val serviceName = task.project.name
        
        // 读取job配置，优先使用 jobs.$profile，否则使用 job 或 jobPath
        var jobPath = config["jenkins.jobs.$profile"] 
            ?: config["jenkins.job"]
            ?: config["jenkins.jobPath"]
        
        if (jobPath != null) {
            // 如果路径中包含占位符，则替换
            if (jobPath.contains("\${service}") || jobPath.contains("\$service") || 
                jobPath.contains("\${SERVICE_NAME}") || jobPath.contains("\$SERVICE_NAME")) {
                jobPath = jobPath
                    .replace("\${service}", serviceName)
                    .replace("\$service", serviceName)
                    .replace("\${SERVICE_NAME}", serviceName)
                    .replace("\$SERVICE_NAME", serviceName)
            } else {
                // 如果没有占位符，自动在末尾添加 /${service}
                jobPath = "$jobPath/$serviceName"
            }
        }
        
        return mapOf(
            "url" to config["jenkins.url"],
            "user" to config["jenkins.user"],
            "token" to config["jenkins.token"],
            "job" to jobPath
        )
    }

    /**
     * 解析日志文件路径
     */
    fun resolveLogFilePath(task: Task, serviceName: String, remoteBaseDir: String): String {
        val scriptDirFile = File(task.project.rootDir, "gradle/remote-plugin")
        val cfgFile = File(scriptDirFile, "remote.yml")
        if (!cfgFile.exists()) return "$remoteBaseDir/../logs/$serviceName.log"
        val config = parseSimpleYaml(cfgFile)
        val pattern = config["log.filePattern"]
            ?: config["log.path.pattern"]
            ?: config["log.file.pattern"]
            ?: config["log.path"]
            ?: config["log.file"]
        if (pattern != null) {
            return pattern
                .replace("\${service}", serviceName)
                .replace("\${SERVICE_NAME}", serviceName)
                .replace("\${remote.base.dir}", remoteBaseDir)
                .replace("\${REMOTE_BASE_DIR}", remoteBaseDir)
                .replace("\$service", serviceName)
                .replace("\$SERVICE_NAME", serviceName)
                .replace("\$remote.base.dir", remoteBaseDir)
                .replace("\$REMOTE_BASE_DIR", remoteBaseDir)
        }
        return "$remoteBaseDir/../logs/$serviceName.log"
    }

    fun resolveStartCommand(task: Task, remoteBaseDir: String, serviceName: String): String {
        val scriptDirFile = File(task.project.rootDir, "gradle/remote-plugin")
        val cfgFile = File(scriptDirFile, "remote.yml")
        var cmd = "$remoteBaseDir/$serviceName/$serviceName-start.sh"
        if (cfgFile.exists()) {
            val config = parseSimpleYaml(cfgFile)
            val v = config["start.command"]
                ?: config["service.start.command"]
                ?: config["start.path"]
            if (v != null) cmd = v
        }
        return cmd
            .replace("\${service}", serviceName)
            .replace("\${SERVICE_NAME}", serviceName)
            .replace("\${remote.base.dir}", remoteBaseDir)
            .replace("\${REMOTE_BASE_DIR}", remoteBaseDir)
            .replace("\$service", serviceName)
            .replace("\$SERVICE_NAME", serviceName)
            .replace("\$remote.base.dir", remoteBaseDir)
            .replace("\$REMOTE_BASE_DIR", remoteBaseDir)
    }

    fun resolveStartEnv(task: Task, remoteBaseDir: String, serviceName: String, servicePort: String): Map<String, String> {
        val scriptDirFile = File(task.project.rootDir, "gradle/remote-plugin")
        val cfgFile = File(scriptDirFile, "remote.yml")
        if (!cfgFile.exists()) return emptyMap()
        val config = parseSimpleYaml(cfgFile)
        val result = mutableMapOf<String, String>()
        config.entries.forEach { (k, v) ->
            val isEnv = k.startsWith("env.") || k.startsWith("service.env.")
            if (isEnv) {
                val key = if (k.startsWith("env.")) {
                    k.substring("env.".length)
                } else {
                    k.substring("service.env.".length)
                }
                val value = v
                    .replace("\${service}", serviceName)
                    .replace("\${SERVICE_NAME}", serviceName)
                    .replace("\${remote.base.dir}", remoteBaseDir)
                    .replace("\${REMOTE_BASE_DIR}", remoteBaseDir)
                    .replace("\${SERVICE_PORT}", servicePort)
                    .replace("\$service", serviceName)
                    .replace("\$SERVICE_NAME", serviceName)
                    .replace("\$remote.base.dir", remoteBaseDir)
                    .replace("\$REMOTE_BASE_DIR", remoteBaseDir)
                    .replace("\$SERVICE_PORT", servicePort)
                result[key] = value
            }
        }
        return result
    }

    fun buildExportEnv(env: Map<String, String>): String {
        if (env.isEmpty()) return ""
        return env.entries.joinToString(separator = " ", prefix = "export ") { (k, v) ->
            val valEsc = v.replace("'", "'\\''")
            "$k='$valEsc'"
        }
    }

    /**
     * 构建远程tail命令
     */
    fun buildRemoteTailCmd(logFilePath: String): String {
        return """bash -lc 'tail -fn10000 $logFilePath & pid=${'$'}!; trap "kill -TERM ${'$'}pid" EXIT; wait ${'$'}pid'"""
    }

    /**
     * 加载环境配置
     * 支持配置继承机制
     */
    fun envLoad(task: Task, profile: String): Boolean {
        println("[DEBUG-envLoad] - 开始加载环境配置 - 项目: ${task.project.name} 环境: $profile")
        
        // 尝试从remote.yml读取环境配置
        val scriptDirFile = File(task.project.rootDir, "gradle/remote-plugin")
        val remoteYmlFile = File(scriptDirFile, "remote.yml")
        val extra: ExtraPropertiesExtension = task.extensions.extraProperties
        
        if (remoteYmlFile.exists()) {
            println("[DEBUG-envLoad] 发现remote.yml文件: ${remoteYmlFile.absolutePath}")
            try {
                // 使用新的配置合并机制
                val mergedConfig = ConfigMerger.getMergedConfigForEnvironment(remoteYmlFile, profile)
                println("[DEBUG-envLoad] 解析remote.yml成功，共加载 ${mergedConfig.size} 个配置项")
                
                // 打印所有配置项（用于调试）
                println("[DEBUG-envLoad] 合并后的配置项: $mergedConfig")
                
                // 应用配置到任务属性
                val loadedProperties = mutableMapOf<String, String>()
                mergedConfig.entries.forEach { (key, value) ->
                    extra.set(key, value)
                    loadedProperties[key] = value
                    println("[DEBUG-envLoad] 从remote.yml加载配置: $key=$value")
                }
                
                if (loadedProperties.isNotEmpty()) {
                    println("[DEBUG-envLoad] 成功从remote.yml加载 ${loadedProperties.size} 个环境配置项")
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

    /**
     * 获取服务端口
     */
    fun getServicePort(task: Task, scriptDir: String): String {
        // 读取YAML配置文件
        val configFile = File(scriptDir, "remote.yml")
        if (!configFile.exists()) {
            val sample = """
# Service ports configuration
service:
  ports:
    ${task.project.name}: 8080"""
            val msg = """
[remote-plugin] 配置文件缺失
[remote-plugin] 缺失文件, 请创建文件: ${configFile.absolutePath}
[remote-plugin] 示例: $sample
[remote-plugin] 说明: 在service.ports下配置服务端口；Arthas 端口 = 1 + 服务端口
""".trimIndent()
            throw GradleException(msg)
        }
        
        // 使用现有的YAML解析方法
        val config = parseSimpleYaml(configFile)
        val portKey = "service.ports.${task.project.name}"
        val port = config[portKey]
        
        if (port == null) {
            val sample = """
service:
  ports:
    ${task.project.name}: 8080"""
            val msg = """
[remote-plugin] 未找到服务 ${task.project.name} 的端口映射
[remote-plugin] 请在 ${configFile.absolutePath} 的service.ports下添加条目, 示例: $sample
[remote-plugin] 说明: 配置格式为 service.ports.<服务名>: <端口号>；Arthas 端口 = 1 + 服务端口
""".trimIndent()
            throw GradleException(msg)
        }
        return port
    }
}

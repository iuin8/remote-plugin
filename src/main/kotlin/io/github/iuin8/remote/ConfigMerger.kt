package io.github.iuin8.remote

import java.io.File

/**
 * 表示解析后的配置结构，包含通用配置和环境配置
 */
data class ParsedConfig(
    val commonConfigs: Map<String, Map<String, String>> = emptyMap(),
    val envConfigs: Map<String, Map<String, String>> = emptyMap(),
    val servicePorts: Map<String, String> = emptyMap()
)

/**
 * 表示扫描后的概要配置，用于任务注册
 */
data class ScannedConfig(
    val environments: Set<String> = emptySet(),
    val configuredServices: Set<String> = emptySet()
)

/**
 * 配置合并工具类
 */
object ConfigMerger {
    
    class ConfigException(message: String) : Exception(message)
    
    /**
     * 解析YAML配置文件，支持common和environments结构
     */
    fun parseSimpleYamlWithBase(file: File): ParsedConfig {
        val commonConfigs = mutableMapOf<String, MutableMap<String, String>>()
        val envConfigs = mutableMapOf<String, MutableMap<String, String>>()
        val servicePortsMap = mutableMapOf<String, String>()
        
        var currentSection: String? = null // "common", "environments" or "service_ports"
        var currentBlock: String? = null   // e.g., "base" or "dev"
        var blockIndent: Int = -1          // Indentation of the current top-level block
        
        // 用于跟踪每行的缩进和对应的键路径
        val pathStack = mutableListOf<Pair<Int, String>>()
        
        file.forEachLine { raw ->
            val indent = raw.takeWhile { it == ' ' }.length
            val line = raw.trim()
            
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            
            if (indent == 0 && line == "common:") {
                currentSection = "common"
                currentBlock = null
                blockIndent = -1
                pathStack.clear()
                return@forEachLine
            }
            if (indent == 0 && line == "environments:") {
                currentSection = "environments"
                currentBlock = null
                blockIndent = -1
                pathStack.clear()
                return@forEachLine
            }

            
            // 调整 pathStack 到当前缩进级别
            while (pathStack.isNotEmpty() && pathStack.last().first >= indent) {
                pathStack.removeAt(pathStack.size - 1)
            }
            
            if (line.endsWith(":")) {
                val key = line.substringBeforeLast(":").trim()
                if (currentSection != null && (currentBlock == null || indent <= blockIndent) && pathStack.isEmpty()) {
                    currentBlock = key
                    blockIndent = indent
                } else {
                    pathStack.add(indent to key)
                }
            } else if (line.contains(":")) {
                val parts = line.split(":", limit = 2)
                val key = parts[0].trim()
                var value = parts[1].trim()
                
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length - 1)
                }
                
                if (currentSection != null && currentBlock != null) {
                    val fullKey = (pathStack.map { it.second } + key).joinToString(".")
                    val targetMap = if (currentSection == "common") {
                        commonConfigs.getOrPut(currentBlock!!) { mutableMapOf() }
                    } else {
                        envConfigs.getOrPut(currentBlock!!) { mutableMapOf() }
                    }
                    targetMap[fullKey] = value
                }
            }
        }
        
        return ParsedConfig(commonConfigs, envConfigs, servicePortsMap)
    }



    /**
     * 合并多个配置文件并获取环境配置
     * 后面的文件优先级更高
     */
    fun getMergedConfigForEnvironment(files: List<File>, environment: String, project: org.gradle.api.Project? = null): Map<String, String> {
        val existingFiles = files.filter { it.exists() }
        if (existingFiles.isEmpty()) throw ConfigException("配置文件不存在")

        // 逐个解析并简单合并 ParsedConfig
        var finalParsedConfig = ParsedConfig()
        existingFiles.forEach { file ->
            val p = parseSimpleYamlWithBase(file)
            finalParsedConfig = ParsedConfig(
                commonConfigs = deepMerge(finalParsedConfig.commonConfigs, p.commonConfigs),
                envConfigs = deepMerge(finalParsedConfig.envConfigs, p.envConfigs),
                servicePorts = finalParsedConfig.servicePorts + p.servicePorts
            )
        }
        
        val envConfig = finalParsedConfig.envConfigs[environment] ?: emptyMap()
        
        val extends = envConfig["extends"]
        val baseConfig = if (extends != null) {
            finalParsedConfig.commonConfigs[extends] ?: throw ConfigException("环境${environment}试图继承不存在的配置块${extends}")
        } else {
            emptyMap()
        }
        
        val mergedConfig = baseConfig.toMutableMap()
        
        // 合并环境特有配置
        mergedConfig.putAll(envConfig)

        // 如果提供了 project，则解析所有值中的占位符
        if (project != null) {
            mergedConfig.keys.forEach { key ->
                mergedConfig[key] = RemotePluginUtils.resolvePlaceholders(mergedConfig[key]!!, project)
            }
        }
        
        return mergedConfig
    }

    /**
     * 深度合并 Map<String, Map<String, String>>
     */
    private fun deepMerge(base: Map<String, Map<String, String>>, override: Map<String, Map<String, String>>): Map<String, Map<String, String>> {
        val result = base.toMutableMap()
        override.forEach { (key, map) ->
            val existing = result[key]
            if (existing == null) {
                result[key] = map
            } else {
                result[key] = existing + map
            }
        }
        return result
    }

    /**
     * 扫描项目中的所有配置文件并提取概要信息
     */
    fun scanConfig(project: org.gradle.api.Project): ScannedConfig {
        val environments = mutableSetOf<String>()
        val configuredServices = mutableSetOf<String>()
        
        val scriptDirFile = File(project.rootDir, "gradle/remote-plugin")
        val remoteYmlFile = File(scriptDirFile, "remote.yml")
        val remoteLocalYmlFile = File(scriptDirFile, "remote-local.yml")
        val ymlFiles = listOf(remoteYmlFile, remoteLocalYmlFile).filter { it.exists() }

        ymlFiles.forEach { file ->
            try {
                val parsedConfig = parseSimpleYamlWithBase(file)
                environments.addAll(parsedConfig.envConfigs.keys)
                
                // 从所有配置块中提取以 service_ports. 开头的服务定义
                parsedConfig.commonConfigs.values.forEach { map ->
                    map.keys.filter { it.startsWith("service_ports.") }.forEach {
                        configuredServices.add(it.substringAfter("service_ports."))
                    }
                }
                parsedConfig.envConfigs.values.forEach { map ->
                    map.keys.filter { it.startsWith("service_ports.") }.forEach {
                        configuredServices.add(it.substringAfter("service_ports."))
                    }
                }
            } catch (e: Exception) {
                // 静默失败
            }
        }
        return ScannedConfig(environments, configuredServices)
    }

    /**
     * 加载环境配置并应用到项目属性
     */
    fun envLoad(project: org.gradle.api.Project, profile: String): Boolean {
        val extra = project.extensions.extraProperties
        
        // 如果已经加载过当前环境，则跳过
        if (extra.has("remote_loaded_profile") && extra.get("remote_loaded_profile") == profile) {
            return true
        }

        val scriptDirFile = File(project.rootDir, "gradle/remote-plugin")
        val remoteYmlFile = File(scriptDirFile, "remote.yml")
        val remoteLocalYmlFile = File(scriptDirFile, "remote-local.yml")
        
        if (remoteYmlFile.exists()) {
            try {
                val files = listOf(remoteYmlFile, remoteLocalYmlFile)
                val mergedConfig = getMergedConfigForEnvironment(files, profile, project)
                
                mergedConfig.entries.forEach { (key, value) ->
                    extra.set(key, value)
                }
                
                extra.set("remote_loaded_profile", profile)
                
                // SshSetupManager 相关的逻辑 (基于合并后的配置)
                val autoKeygen = if (extra.has("ssh.setup.auto.keygen")) {
                    extra.get("ssh.setup.auto.keygen").toString().toBoolean()
                } else false
                extra.set("ssh.setup.auto.keygen", autoKeygen)
                
                return true
            } catch (e: Exception) {
                // 错误显示可以留给 debug
            }
        }
        return false
    }
}
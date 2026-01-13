package io.github.iuin8.remote

import java.io.File

/**
 * 表示解析后的配置结构，包含通用配置和环境配置
 */
data class ParsedConfig(
    val commonConfigs: Map<String, Map<String, String>> = emptyMap(),
    val envConfigs: Map<String, Map<String, String>> = emptyMap()
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
        
        var currentSection: String? = null // "common" or "environments"
        var currentBlock: String? = null   // e.g., "base" or "dev"
        var blockIndent: Int = -1          // Indentation of the current top-level block
        
        // 用于跟踪每行的缩进和对应的键路径
        val pathStack = mutableListOf<Pair<Int, String>>()
        
        file.forEachLine { raw ->
            val indent = raw.takeWhile { it == ' ' }.length
            val line = raw.trim()
            
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            
            if (line == "common:") {
                currentSection = "common"
                currentBlock = null
                blockIndent = -1
                pathStack.clear()
                return@forEachLine
            }
            if (line == "environments:") {
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
        
        return ParsedConfig(commonConfigs, envConfigs)
    }

    /**
     * 根据环境名称获取合并后的配置
     */
    fun getMergedConfigForEnvironment(file: File, environment: String): Map<String, String> {
        if (!file.exists()) throw ConfigException("配置文件不存在: ${file.absolutePath}")
        
        val parsedConfig = parseSimpleYamlWithBase(file)
        val envConfig = parsedConfig.envConfigs[environment] ?: emptyMap()
        
        val extends = envConfig["extends"]
        val baseConfig = if (extends != null) {
            parsedConfig.commonConfigs[extends] ?: throw ConfigException("环境${environment}试图继承不存在的配置块${extends}")
        } else {
            emptyMap()
        }
        
        val mergedConfig = baseConfig.toMutableMap()
        mergedConfig.putAll(envConfig)
        return mergedConfig
    }
}
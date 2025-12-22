package io.github.iuin8.remote

import java.io.File

/**
 * 表示解析后的配置结构，包含基础配置和环境配置
 */
data class ParsedConfig(
    val baseConfig: Map<String, String> = emptyMap(),
    val envConfigs: Map<String, Map<String, String>> = emptyMap()
)

/**
 * 配置合并工具类
 */
object ConfigMerger {
    
    class ConfigException(message: String) : Exception(message)
    
    /**
     * 解析YAML配置文件，支持base配置块和环境配置块
     */
    fun parseSimpleYamlWithBase(file: File): ParsedConfig {
        val baseConfig = mutableMapOf<String, String>()
        val envConfigs = mutableMapOf<String, MutableMap<String, String>>()
        val prefixStack = mutableListOf<String>()
        var currentSection = "" // "base", "environments", or empty for root level
        var inBaseSection = false
        var inEnvironmentsSection = false
        
        file.forEachLine { raw ->
            // 计算缩进级别
            val indentCount = raw.takeWhile { it == ' ' }.length
            val line = raw.trim()
            
            // 跳过空行和注释
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            
            // 检查是否是section标识
            when {
                line == "base:" -> {
                    currentSection = "base"
                    inBaseSection = true
                    inEnvironmentsSection = false
                    prefixStack.clear()
                    return@forEachLine
                }
                line == "environments:" -> {
                    currentSection = "environments"
                    inEnvironmentsSection = true
                    inBaseSection = false
                    prefixStack.clear()
                    return@forEachLine
                }
            }
            
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
                    
                    // 根据当前section决定存储位置
                    when {
                        inBaseSection -> {
                            baseConfig[fullKey] = value
                        }
                        inEnvironmentsSection && prefixStack.isNotEmpty() -> {
                            // environments下的配置需要特殊处理
                            val envName = prefixStack[0]
                            val envKey = if (prefixStack.size > 1) {
                                prefixStack.drop(1).joinToString(".") + "." + key
                            } else {
                                key
                            }
                            
                            if (!envConfigs.containsKey(envName)) {
                                envConfigs[envName] = mutableMapOf()
                            }
                            envConfigs[envName]?.set(envKey, value)
                        }
                        else -> {
                            // 根路径下的配置或其他配置，暂时忽略或根据需要处理
                        }
                    }
                }
            }
        }
        return ParsedConfig(baseConfig, envConfigs)
    }
    
    /**
     * 合并基础配置和环境特定配置
     */
    fun mergeConfig(baseConfig: Map<String, String>, envConfig: Map<String, String>): Map<String, String> {
        val mergedConfig = baseConfig.toMutableMap()
        mergedConfig.putAll(envConfig)
        return mergedConfig
    }
    
    /**
     * 根据环境名称获取合并后的配置
     */
    fun getMergedConfigForEnvironment(file: File, environment: String): Map<String, String> {
        if (!file.exists()) {
            throw ConfigException("配置文件不存在: ${file.absolutePath}")
        }
        
        val parsedConfig = parseSimpleYamlWithBase(file)
        val baseConfig = parsedConfig.baseConfig
        val envConfig = parsedConfig.envConfigs[environment] ?: emptyMap()
        
        // 检查是否存在extends字段
        val extends = envConfig["extends"]
        
        // 验证继承关系
        if (extends != null && extends != "base") {
            throw ConfigException("目前仅支持继承base配置，但环境${environment}试图继承${extends}")
        }
        
        val effectiveBaseConfig = if (extends == "base") {
            baseConfig
        } else {
            emptyMap()
        }
        
        return mergeConfig(effectiveBaseConfig, envConfig)
    }
}
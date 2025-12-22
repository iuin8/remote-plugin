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
        val prefixStack = mutableListOf<String>()
        var inCommonSection = false
        var inEnvironmentsSection = false
        
        file.forEachLine { raw ->
            // 计算缩进级别
            val indentCount = raw.takeWhile { it == ' ' }.length
            val line = raw.trim()
            
            // 跳过空行和注释
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            
            // 检查是否是section标识
            when {
                line == "common:" -> {
                    inCommonSection = true
                    inEnvironmentsSection = false
                    prefixStack.clear()
                    return@forEachLine
                }
                line == "environments:" -> {
                    inEnvironmentsSection = true
                    inCommonSection = false
                    prefixStack.clear()
                    return@forEachLine
                }
            }
            
            // 处理键值对或嵌套键
            if (line.endsWith(":")) {
                // 嵌套键（只有冒号，没有值）
                val key = line.substringBeforeLast(":").trim()
                
                // 计算当前缩进级别
                val indentLevel = indentCount / 2
                
                // 调整前缀栈，使其大小等于 indentLevel
                while (prefixStack.size > indentLevel) {
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
                    
                    // 移除引号
                    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length - 1)
                    }
                    
                    // 计算当前缩进级别
                    val indentLevel = indentCount / 2
                    
                    // 调整前缀栈，使其大小等于 indentLevel
                    while (prefixStack.size > indentLevel) {
                        prefixStack.removeLast()
                    }
                    
                    // 根据当前section决定存储位置
                    when {
                        inCommonSection && prefixStack.isNotEmpty() -> {
                            // common下的配置块（如common.base.remote.base_dir）
                            val blockName = prefixStack[0] // common下的块名（如base）
                            val nestedKey = if (prefixStack.size > 1) {
                                prefixStack.drop(1).joinToString(".") + "." + key
                            } else {
                                key
                            }
                            
                            // 确保块存在
                            if (!commonConfigs.containsKey(blockName)) {
                                commonConfigs[blockName] = mutableMapOf()
                            }
                            
                            // 存储配置项
                            commonConfigs[blockName]?.set(nestedKey, value)
                        }
                        inEnvironmentsSection && prefixStack.isNotEmpty() -> {
                            // environments下的配置（如environments.dev.ssh.server）
                            val envName = prefixStack[0] // 环境名（如dev）
                            val nestedKey = if (prefixStack.size > 1) {
                                prefixStack.drop(1).joinToString(".") + "." + key
                            } else {
                                key
                            }
                            
                            // 确保环境存在
                            if (!envConfigs.containsKey(envName)) {
                                envConfigs[envName] = mutableMapOf()
                            }
                            
                            // 存储配置项
                            envConfigs[envName]?.set(nestedKey, value)
                        }
                        else -> {
                            // 根路径下的配置，暂时忽略
                        }
                    }
                }
            }
        }
        return ParsedConfig(commonConfigs, envConfigs)
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
        val envConfig = parsedConfig.envConfigs[environment] ?: emptyMap()
        
        // 检查是否存在extends字段
        val extends = envConfig["extends"]
        
        // 获取继承的配置
        val baseConfig = if (extends != null) {
            parsedConfig.commonConfigs[extends] ?: throw ConfigException("环境${environment}试图继承不存在的配置块${extends}")
        } else {
            emptyMap()
        }
        
        // 合并配置
        val mergedConfig = baseConfig.toMutableMap()
        mergedConfig.putAll(envConfig)
        
        return mergedConfig
    }
}
package io.github.iuin8.remote

import java.io.File

/**
 * Simplified configuration structure
 */
data class ParsedConfig(
    val common: Map<String, Any> = emptyMap(),
    val environments: Map<String, Any> = emptyMap(),
    val servicePorts: Map<String, Any> = emptyMap()
)

data class ScannedConfig(
    val environments: Set<String> = emptySet(),
    val configuredServices: Set<String> = emptySet()
)

/**
 * Unified configuration utility with clean, high-cohesion logic.
 */
object ConfigMerger {

    class ConfigException(message: String) : Exception(message)

    /**
     * Parses a simple YAML file into a structured ParsedConfig object.
     * Logic is centered around building a general data tree first.
     */
    fun parseSimpleYaml(file: File): ParsedConfig {
        val root = mutableMapOf<String, Any>()
        val stack = mutableListOf<MutableMap<String, Any>>()
        stack.add(root)
        val indents = mutableListOf(-1)

        file.forEachLine { raw ->
            val indent = raw.takeWhile { it == ' ' }.length
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine

            // Handling lists (like in the environments section)
            if (line.startsWith("-") && stack.isNotEmpty()) {
                val value = line.substring(1).trim()
                val current = stack.last()
                @Suppress("UNCHECKED_CAST")
                val list = current.getOrPut("__list__") { mutableListOf<String>() } as MutableList<String>
                list.add(value)
                return@forEachLine
            }

            // Adjust stack based on indentation
            while (indent <= indents.last() && indents.size > 1) {
                stack.removeAt(stack.size - 1)
                indents.removeAt(indents.size - 1)
            }

            if (line.endsWith(":")) {
                val key = line.substringBeforeLast(":").trim()
                val newMap = mutableMapOf<String, Any>()
                stack.last()[key] = newMap
                stack.add(newMap)
                indents.add(indent)
            } else if (line.contains(":")) {
                val parts = line.split(":", limit = 2)
                val key = parts[0].trim()
                var value = parts[1].trim()
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length - 1)
                }
                stack.last()[key] = value
            }
        }

        @Suppress("UNCHECKED_CAST")
        val environmentsRaw = root["environments"] as? Map<String, Any> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val environmentsList = environmentsRaw["__list__"] as? List<String> ?: emptyList()
        
        // Ensure all environment names in the list are treated as keys in the map
        val finalEnvironments = environmentsRaw.toMutableMap()
        finalEnvironments.remove("__list__")
        environmentsList.forEach { env ->
            finalEnvironments.putIfAbsent(env, emptyMap<String, Any>())
        }

        @Suppress("UNCHECKED_CAST")
        return ParsedConfig(
            common = root["common"] as? Map<String, Any> ?: emptyMap(),
            environments = finalEnvironments,
            servicePorts = root["service_ports"] as? Map<String, Any> ?: emptyMap()
        )
    }

    /**
     * Scans for configuration and returns summary info.
     */
    fun scanConfig(project: org.gradle.api.Project): ScannedConfig {
        val environments = mutableSetOf<String>()
        val configuredServices = mutableSetOf<String>()
        
        val scriptDir = File(project.rootDir, "gradle/remote-plugin")
        listOf("remote.yml", "remote-local.yml")
            .map { File(scriptDir, it) }
            .filter { it.exists() }
            .forEach { file ->
                try {
                    val config = parseSimpleYaml(file)
                    environments.addAll(config.environments.keys)
                    
                    // Extract services from all possible locations
                    configuredServices.addAll(extractServices(config.servicePorts))
                    configuredServices.addAll(extractServices(config.common))
                    config.environments.values.forEach { 
                        if (it is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            configuredServices.addAll(extractServices(it as Map<String, Any>))
                        }
                    }
                } catch (e: Exception) {}
            }
        
        return ScannedConfig(environments, configuredServices)
    }

    private fun extractServices(map: Map<String, Any>): Set<String> {
        val services = mutableSetOf<String>()
        flattenMap(map).keys.forEach { key ->
            if (key.startsWith("service_ports.")) {
                services.add(key.substringAfter("service_ports."))
            } else if (key.contains(".service_ports.")) {
                services.add(key.substringAfter(".service_ports."))
            }
        }
        return services
    }

    /**
     * Gets a flattened map of all properties for a specific environment.
     */
    fun getEnvProperties(project: org.gradle.api.Project, profile: String): Map<String, Any> {
        val scriptDir = File(project.rootDir, "gradle/remote-plugin")
        val files = listOf("remote.yml", "remote-local.yml").map { File(scriptDir, it) }.filter { it.exists() }
        
        if (files.isEmpty()) return emptyMap()

        // Merge all files into one ParsedConfig
        var merged = ParsedConfig()
        files.forEach { file ->
            val p = parseSimpleYaml(file)
            merged = ParsedConfig(
                common = deepMerge(merged.common, p.common),
                environments = deepMerge(merged.environments, p.environments),
                servicePorts = deepMerge(merged.servicePorts, p.servicePorts)
            )
        }

        @Suppress("UNCHECKED_CAST")
        val envConfig = merged.environments[profile] as? Map<String, Any> ?: emptyMap()
        val extends = envConfig["extends"]?.toString()
        
        @Suppress("UNCHECKED_CAST")
        val baseConfig = if (extends != null) merged.common[extends] as? Map<String, Any> ?: emptyMap() else emptyMap()
        
        val finalMap = mutableMapOf<String, Any>()
        
        // 1. Base (common.extends)
        finalMap.putAll(flattenMap(baseConfig))
        // 2. Environment specific
        finalMap.putAll(flattenMap(envConfig))
        
        // 3. Remap all nested service_ports to top-level "service_ports."
        val allFlattened = mutableMapOf<String, Any>()
        allFlattened.putAll(flattenMap(merged.common))
        allFlattened.putAll(flattenMap(merged.environments))
        allFlattened.putAll(flattenMap(merged.servicePorts))
        
        allFlattened.forEach { (k, v) ->
            if (k == "service_ports" && v is Map<*, *>) {
                // Should not happen with flattenMap, but for safety
            } else if (k.startsWith("service_ports.")) {
                finalMap[k] = v
            } else if (k.contains(".service_ports.")) {
                val serviceName = k.substringAfter(".service_ports.")
                finalMap["service_ports.$serviceName"] = v
            }
        }

        // Apply placeholders
        finalMap.keys.forEach { key ->
            val value = finalMap[key]
            if (value is String) {
                finalMap[key] = RemotePluginUtils.resolvePlaceholders(value, project)
            }
        }
        
        // Logical overrides
        val autoKeygen = finalMap["ssh.setup.auto.keygen"]?.toString()?.toBoolean() ?: false
        finalMap["ssh.setup.auto.keygen"] = autoKeygen
        
        return finalMap
    }

    private fun flattenMap(map: Map<String, Any>, prefix: String = ""): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for ((key, value) in map) {
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                result.putAll(flattenMap(value as Map<String, Any>, fullKey))
            } else {
                result[fullKey] = value
            }
        }
        return result
    }

    private fun deepMerge(base: Map<String, Any>, override: Map<String, Any>): Map<String, Any> {
        val result = base.toMutableMap()
        for ((key, value) in override) {
            val existing = result[key]
            if (existing is Map<*, *> && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                result[key] = deepMerge(existing as Map<String, Any>, value as Map<String, Any>)
            } else {
                result[key] = value
            }
        }
        return result
    }
}
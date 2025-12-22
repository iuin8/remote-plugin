package io.github.iuin8.remote

import java.io.File

fun main() {
    // 测试配置继承功能
    val testConfigFile = File("src/test/resources/test-inheritance-config.yml")
    
    if (testConfigFile.exists()) {
        println("测试配置文件存在，开始测试配置继承功能...")
        
        // 测试解析配置
        val parsedConfig = ConfigMerger.parseSimpleYamlWithBase(testConfigFile)
        println("基础配置: ${parsedConfig.baseConfig}")
        println("环境配置: ${parsedConfig.envConfigs}")
        
        // 测试dev环境配置合并
        val devConfig = ConfigMerger.getMergedConfigForEnvironment(testConfigFile, "dev")
        println("\ndev环境合并后的配置:")
        devConfig.forEach { key, value ->
            println("  $key: $value")
        }
        
        // 验证dev环境是否正确继承了基础配置
        println("\ndev环境继承验证:")
        println("  remote.base.dir: ${devConfig["remote.base.dir"]}")
        println("  service.start.command: ${devConfig["service.start.command"]}")
        println("  log.filePattern: ${devConfig["log.filePattern"]}")
        println("  remote.server: ${devConfig["remote.server"]}")
        println("  ssh.setup.auto.keygen: ${devConfig["ssh.setup.auto.keygen"]}")
        
        // 测试prod环境配置合并
        val prodConfig = ConfigMerger.getMergedConfigForEnvironment(testConfigFile, "prod")
        println("\nprod环境合并后的配置:")
        prodConfig.forEach { key, value ->
            println("  $key: $value")
        }
        
        // 验证prod环境是否正确继承了基础配置
        println("\nprod环境继承验证:")
        println("  remote.base.dir: ${prodConfig["remote.base.dir"]}")
        println("  service.start.command: ${prodConfig["service.start.command"]}")
        println("  log.filePattern: ${prodConfig["log.filePattern"]}")
        println("  remote.server: ${prodConfig["remote.server"]}")
        
        println("\n测试完成!")
    } else {
        println("测试配置文件不存在: ${testConfigFile.absolutePath}")
    }
}
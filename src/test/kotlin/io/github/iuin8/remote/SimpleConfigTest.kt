package io.github.iuin8.remote

import java.io.File

fun main() {
    println("配置继承功能验证测试")
    
    // 创建测试配置内容
    val testConfigContent = """
# Base configuration
common:
  base:
    service:
      ports:
        app: 9090
      start:
        command: systemctl restart

# Environment configurations
environments:
  dev:
    extends: base
    remote:
      server: dev.example.com
    service:
      ports:
        app: 8080

  prod:
    remote:
      server: prod.example.com
    service:
      ports:
        app: 80
""".trimIndent()

    // 写入临时文件
    val tempFile = File.createTempFile("test-config", ".yml")
    tempFile.writeText(testConfigContent)
    
    try {
        // 测试解析功能
        val parsedConfig = ConfigMerger.parseSimpleYamlWithBase(tempFile)
        println("通用配置项数: ${parsedConfig.commonConfigs.size}")
        println("环境配置数: ${parsedConfig.envConfigs.size}")
        
        // 测试dev环境（有继承）
        val devConfig = ConfigMerger.getMergedConfigForEnvironment(tempFile, "dev")
        println("\ndev环境配置:")
        devConfig.forEach { k, v -> println("  $k: $v") }
        
        // 验证继承是否工作
        val hasInheritance = devConfig.containsKey("service.start.command")
        val hasOverride = devConfig["service_ports.app"] == "8080"
        println("\n继承功能正常: $hasInheritance")
        println("覆盖功能正常: $hasOverride")
        
        if (hasInheritance && hasOverride) {
            println("\n✅ 配置继承功能实现正确！")
        } else {
            println("\n❌ 配置继承功能有问题！")
        }
        
    } finally {
        tempFile.delete()
    }
}
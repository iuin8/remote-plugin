#!/usr/bin/env kotlin

import java.io.File

/**
 * 测试 SSH 配置自动注入功能
 */
fun main() {
    println("=== SSH 配置自动注入功能测试 ===\n")
    
    // 模拟项目配置
    val testProjectDir = File("/Users/fa/dev/projects/other/me/gradle/plugin/remote-plugin/consumer-gradle6-sample")
    val projectName = "consumer-gradle6-sample"
    
    println("测试项目: $projectName")
    println("项目路径: ${testProjectDir.absolutePath}")
    
    // 检查项目 ssh_config
    val projectSshConfig = File(testProjectDir, "gradle/remote-plugin/ssh_config")
    println("\n1. 检查项目 ssh_config:")
    println("   文件路径: ${projectSshConfig.absolutePath}")
    println("   文件存在: ${projectSshConfig.exists()}")
    
    if (projectSshConfig.exists()) {
        println("   文件内容预览:")
        projectSshConfig.readLines().take(3).forEach { line ->
            println("   $line")
        }
    }
    
    // 检查系统配置
    val userHome = System.getProperty("user.home")
    val systemConfig = File(userHome, ".ssh/config")
    println("\n2. 检查系统 SSH 配置:")
    println("   文件路径: ${systemConfig.absolutePath}")
    println("   文件存在: ${systemConfig.exists()}")
    
    if (systemConfig.exists()) {
        val content = systemConfig.readText()
        val hasPluginInclude = content.contains("Include ~/.ssh/gradle/remote-plugin/")
        println("   包含插件 Include: $hasPluginInclude")
        
        if (hasPluginInclude) {
            println("   相关配置行:")
            systemConfig.readLines().take(10).forEach { line ->
                if (line.contains("Gradle") || line.contains("Include") || line.startsWith("#")) {
                    println("   $line")
                }
            }
        }
    }
    
    // 检查插件管理配置
    val managedConfig = File(userHome, ".ssh/gradle/remote-plugin/config")
    println("\n3. 检查插件管理配置:")
    println("   文件路径: ${managedConfig.absolutePath}")
    println("   文件存在: ${managedConfig.exists()}")
    
    if (managedConfig.exists()) {
        println("   文件内容:")
        managedConfig.readLines().forEach { line ->
            println("   $line")
        }
        
        val hasProjectInclude = managedConfig.readText().contains(projectSshConfig.absolutePath)
        println("   包含当前项目: $hasProjectInclude")
    }
    
    println("\n=== 测试完成 ===")
    
    // 提供测试结论
    println("\n测试结论:")
    if (projectSshConfig.exists()) {
        println("✓ 项目 ssh_config 文件已创建")
    } else {
        println("✗ 项目 ssh_config 文件不存在")
    }
    
    if (systemConfig.exists() && systemConfig.readText().contains("Include ~/.ssh/gradle/remote-plugin/")) {
        println("✓ 系统配置已包含插件 Include 指令")
    } else {
        println("○ 系统配置尚未包含插件 Include 指令（需要运行插件触发）")
    }
    
    if (managedConfig.exists() && managedConfig.readText().contains(projectSshConfig.absolutePath)) {
        println("✓ 插件管理配置已包含当前项目")
    } else {
        println("○ 插件管理配置尚未包含当前项目（需要运行插件触发）")
    }
    
    println("\n提示: 要触发自动注入，请在项目中运行任意 Gradle 任务，例如：")
    println("  cd consumer-gradle6-sample && ./gradlew tasks")
}

main()

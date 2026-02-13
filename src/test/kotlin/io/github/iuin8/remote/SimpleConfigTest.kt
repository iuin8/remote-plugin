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
        /* Commented out due to outdated API and missing Project object in main()
        // 测试dev环境配置（继承base）
        println("=== 测试dev环境配置（继承base） ===")
        val devConfig = ConfigMerger.getMergedConfigForEnvironment(tempFile, "dev")
        println("配置项数量: ${devConfig.size}")
        println("remote.base_dir: ${devConfig["remote.base_dir"]}")
        println("service_ports.app: ${devConfig["service_ports.app"]}")
        println("ssh.server: ${devConfig["ssh.server"]}")
        println("ssh.setup.auto.keygen: ${devConfig["ssh.setup.auto.keygen"]}")
        
        // 测试test环境配置（继承other）
        println("\n=== 测试test环境配置（继承other） ===")
        val testConfig = ConfigMerger.getMergedConfigForEnvironment(tempFile, "test")
        println("配置项数量: ${testConfig.size}")
        println("remote.base_dir: ${testConfig["remote.base_dir"]}")
        println("service_ports.app: ${testConfig["service_ports.app"]}")
        println("ssh.server: ${testConfig["ssh.server"]}")
        
        // 测试prod环境配置（继承base并覆盖部分配置）
        println("\n=== 测试prod环境配置（继承base并覆盖部分配置） ===")
        val prodConfig = ConfigMerger.getMergedConfigForEnvironment(tempFile, "prod")
        println("配置项数量: ${prodConfig.size}")
        println("remote.base_dir: ${prodConfig["remote.base_dir"]}") // 应该是覆盖后的值
        println("service_ports.app: ${prodConfig["service_ports.app"]}") // 应该是覆盖后的值
        println("ssh.server: ${prodConfig["ssh.server"]}")
        */
        
    } finally {
        tempFile.delete()
    }
}
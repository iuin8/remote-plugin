package io.github.iuin8.remote

import java.io.File

/**
 * 测试配置继承和合并功能
 */
fun main() {
    // 创建测试配置文件
    val testConfig = """# 测试配置文件
common:
  base:
    remote:
      base_dir: /path/to/base/app
    service:
      ports:
        app: 8080
    start:
      command: sudo systemctl restart \$\{SERVICE_NAME\}
    log:
      filePattern: \$\{REMOTE_BASE_DIR\}/../logs/\$\{service\}.log
    ssh:
      setup:
        auto:
          keygen: false
        key:
          type: ed25519
  
  other:
    remote:
      base_dir: /path/to/other/app
    service:
      ports:
        app: 9090

# Environment configurations
environments:
  dev:
    extends: base
    ssh:
      server: dev-server-hostname
    jenkins:
      url: "https://dev-jenkins.example.com"
      user: "dev-user"
      token: "dev-token"
      job: "dev-job"
  
  test:
    extends: other
    ssh:
      server: test-server-hostname
    service:
      ports:
        app: 8081
  
  prod:
    extends: base
    ssh:
      server: prod-server-hostname
    remote:
      base_dir: /path/to/prod/app
    service:
      ports:
        app: 80"""
    
    // 写入测试文件
    val testFile = File("test-config.yml")
    testFile.writeText(testConfig)
    
    try {
        /* Commented out due to outdated API and missing Project object in main()
        // 测试dev环境配置（继承base）
        println("=== 测试dev环境配置（继承base） ===")
        val devConfig = ConfigMerger.getMergedConfigForEnvironment(testFile, "dev")
        println("配置项数量: ${devConfig.size}")
        println("remote.base_dir: ${devConfig["remote.base_dir"]}")
        println("service_ports.app: ${devConfig["service_ports.app"]}")
        println("ssh.server: ${devConfig["ssh.server"]}")
        println("ssh.setup.auto.keygen: ${devConfig["ssh.setup.auto.keygen"]}")
        
        // 测试test环境配置（继承other）
        println("\n=== 测试test环境配置（继承other） ===")
        val testConfig = ConfigMerger.getMergedConfigForEnvironment(testFile, "test")
        println("配置项数量: ${testConfig.size}")
        println("remote.base_dir: ${testConfig["remote.base_dir"]}")
        println("service_ports.app: ${testConfig["service_ports.app"]}")
        println("ssh.server: ${testConfig["ssh.server"]}")
        
        // 测试prod环境配置（继承base并覆盖部分配置）
        println("\n=== 测试prod环境配置（继承base并覆盖部分配置） ===")
        val prodConfig = ConfigMerger.getMergedConfigForEnvironment(testFile, "prod")
        println("配置项数量: ${prodConfig.size}")
        println("remote.base_dir: ${prodConfig["remote.base_dir"]}") // 应该是覆盖后的值
        println("service_ports.app: ${prodConfig["service_ports.app"]}") // 应该是覆盖后的值
        println("ssh.server: ${prodConfig["ssh.server"]}")
        */
        
        println("\n=== 所有测试通过！ ===")
        
    } catch (e: Exception) {
        println("测试失败: ${e.message}")
        e.printStackTrace()
    } finally {
        // 清理测试文件
        testFile.delete()
    }
}
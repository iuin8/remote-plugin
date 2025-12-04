# Jenkins 集成配置示例

## 基本配置

在 `gradle/remote-plugin/remote.yml` 中配置 Jenkins 信息：

```yaml
jenkins:
  # Jenkins服务器地址
  url: "http://10.0.1.181:8080"
  
  # Jenkins用户名
  user: "your_username"
  
  # Jenkins API Token (在 Jenkins > 用户 > 设置 > API Token 中生成)
  token: "11abcdef1234567890abcdef12345678"
  
  # Job路径模板 (${service} 会自动替换为当前Gradle项目名)
  jobPath: "东箭-后端(Test环境)/${service}"
```

## 多环境配置

如果不同环境使用不同的Jenkins Job路径：

```yaml
jenkins:
  url: "http://10.0.1.181:8080"
  user: "admin"
  token: "your_api_token_here"
  
  # 按环境配置不同的Job路径
  jobs:
    test: "东箭-后端(Test环境)/${service}"
    dev: "东箭-后端(Dev环境)/${service}"
    prod: "东箭-后端(生产环境)/${service}"
```

## 占位符说明

支持以下占位符：
- `${service}` 或 `$service`: 当前Gradle子项目名称
- `${SERVICE_NAME}` 或 `$SERVICE_NAME`: 同上（大写形式）

**示例**：
- 在 `aftersales-service` 项目中：`${service}` → `aftersales-service`
- 在 `order-service` 项目中：`${service}` → `order-service`

## 使用方法

### 1. 触发构建并查看信息

```bash
# 在具体的服务项目中执行
cd aftersales-service
../gradlew test_jenkins_build

# 或从根目录执行
./gradlew aftersales-service:test_jenkins_build
```

### 2. 输出示例

**安装了 jq 的情况**（推荐）：
```
[jenkins] 触发构建: 东箭-后端(Test环境)/aftersales-service (环境: test)
[jenkins] Jenkins URL: http://10.0.1.181:8080
[jenkins] 正在触发构建...
HTTP Status: 201
[jenkins] 等待构建开始...
[jenkins] 获取构建信息...

==================================================
 Jenkins 构建信息
==================================================
任务: 东箭-后端(Test环境) » aftersales-service
构建号: #42
结果: SUCCESS
URL: http://10.0.1.181:8080/job/东箭-后端(Test环境)/job/aftersales-service/42/
分支: origin/develop
--------------------------------------------------
提交记录:
  - [张三] 修复售后服务bug
  - [李四] 优化查询性能
==================================================
```

**未安装 jq 的情况**：
```
[jenkins] 触发构建: 东箭-后端(Test环境)/aftersales-service (环境: test)
[jenkins] Jenkins URL: http://10.0.1.181:8080
[jenkins] 正在触发构建...
HTTP Status: 201
[jenkins] 等待构建开始...
[jenkins] 获取构建信息...

构建已触发，详细信息请查看 Jenkins:
http://10.0.1.181:8080/job/东箭-后端(Test环境)/job/aftersales-service/42/

提示: 安装 jq 工具可获得更好的显示效果: brew install jq
```

## 安装 jq (可选但推荐)

```bash
# macOS
brew install jq

# Ubuntu/Debian
sudo apt-get install jq

# CentOS/RHEL
sudo yum install jq
```

## 故障排查

### 401 Unauthorized
检查：
1. `user` 和 `token` 是否正确
2. 使用 API Token 而不是密码
3. Jenkins用户是否有该Job的权限

### 404 Not Found
检查：
1. Job路径是否正确（注意URL编码，如空格、括号等）
2. `${service}` 占位符是否被正确替换

### 无法触发构建
检查：
1. Jenkins任务是否配置了"参数化构建"
2. 是否需要传递额外参数（当前不支持）
3. 网络连接是否正常

## 完整示例配置

```yaml
# gradle/remote-plugin/remote.yml

# Jenkins配置
jenkins:
  url: "http://10.0.1.181:8080"
  user: "admin"
  token: "11a1234567890abcdef1234567890abcdef"
  jobs:
    test: "东箭-后端(Test环境)/${service}"
    prod: "东箭-后端(生产环境)/${service}"

# 环境配置
environments:
  test:
    remote:
      server: test-server
      base:
        dir: /app/test
  prod:
    remote:
      server: prod-server
      base:
        dir: /app/prod

# 服务端口配置
service:
  ports:
    aftersales-service: 8080
    order-service: 8081
```

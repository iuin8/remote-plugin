# 环境配置合并设计方案

## 1. 背景与目标

### 1.1 当前状况
当前系统中的环境配置分散在 `remote.yml` 文件的不同部分，主要包括：
- SSH自动配置选项
- Jenkins配置
- 环境配置块（environments）
- 服务端口配置
- 日志文件模式配置
- 启动命令配置
- 环境变量配置

### 1.2 存在问题
1. 配置分散，不利于维护和管理
2. 不同环境的配置组织方式不够清晰
3. 配置项之间的关联性不够明确
4. 缺乏配置继承机制，导致重复配置较多

### 1.3 设计目标
1. 整合环境相关配置到统一的配置结构中
2. 提供更清晰的环境配置组织方式
3. 简化配置管理和使用
4. 支持环境间的配置继承机制
5. 保持向后兼容性（但本次设计不需要）

## 2. 设计方案

### 2.1 配置结构调整
将现有的配置结构重构为更清晰的层次结构，每个环境拥有独立且完整的配置：

```yaml
# 新的配置结构示例
environments:
  dev:
    remote:
      server: your-server-hostname
      base:
        dir: /path/to/your/app
    service:
      ports:
        app: 8080
      start:
        command: sudo systemctl restart ${SERVICE_NAME}
      env:
        JAVA_TOOL_OPTIONS: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:3${SERVICE_PORT}
    ssh:
      setup:
        auto:
          keygen: true
        key:
          type: ed25519
    jenkins:
      url: "https://your-jenkins-url.com"
      user: "your_username"
      token: "your_api_token"
      jobs:
        job1: "your-job-name"
    log:
      filePattern: ${REMOTE_BASE_DIR}/../logs/${service}.log

  prod:
    remote:
      server: prod-server-hostname
      base:
        dir: /path/to/your/app
    service:
      ports:
        app: 80
      start:
        command: sudo systemctl restart ${SERVICE_NAME}
      env:
        JAVA_TOOL_OPTIONS: -Xmx2g
    log:
      filePattern: ${REMOTE_BASE_DIR}/../logs/${service}.log
```

### 2.2 配置加载逻辑优化
修改配置加载逻辑，使其能够正确解析新的配置结构：

1. 完全采用新配置格式
2. 简化配置加载逻辑
3. 支持按环境加载完整配置集

### 2.3 环境配置继承机制
引入环境配置继承机制，允许定义基础配置并在特定环境中覆盖：

```yaml
# 基础配置
base:
  remote:
    base:
      dir: /path/to/your/app
  service:
    ports:
      app: 8080
    start:
      command: sudo systemctl restart ${SERVICE_NAME}
  log:
    filePattern: ${REMOTE_BASE_DIR}/../logs/${service}.log

# 环境特定配置
environments:
  dev:
    extends: base
    remote:
      server: dev-server-hostname
    service:
      ports:
        app: 8080
    ssh:
      setup:
        auto:
          keygen: true
        key:
          type: ed25519

  prod:
    extends: base
    remote:
      server: prod-server-hostname
    service:
      ports:
        app: 80
    log:
      filePattern: ${REMOTE_BASE_DIR}/../logs/prod-${service}.log
```

## 3. 实现计划

### 3.1 第一阶段：配置解析器增强
1. 扩展 `parseSimpleYaml` 方法以支持新的配置结构
2. 添加配置验证逻辑
3. 实现配置继承机制解析

### 3.2 第二阶段：配置加载逻辑重构
1. 修改 `envLoad` 方法以支持新的配置结构
2. 更新各任务中的配置读取逻辑
3. 移除所有向后兼容代码
4. 确保所有功能正常工作

### 3.3 第三阶段：模板和文档更新
1. 更新配置模板文件
2. 编写详细的配置说明文档
3. 提供多种环境配置示例

## 4. 配置详细说明

### 4.1 environments 环境配置块
这是主要的配置容器，包含了所有环境的配置。每个环境都有自己的命名空间，可以包含以下子配置：

#### 4.1.1 remote 远程服务器配置
- `server`: 远程服务器地址
- `base.dir`: 远程基础目录路径

#### 4.1.2 service 服务配置
- `ports`: 服务端口映射，键为服务名，值为端口号
- `start.command`: 服务启动命令
- `env`: 环境变量配置

#### 4.1.3 ssh SSH配置
- `setup.auto.keygen`: 是否自动生成SSH密钥
- `setup.key.type`: SSH密钥类型

#### 4.1.4 jenkins Jenkins配置
- `url`: Jenkins服务器URL
- `user`: 用户名
- `token`: API令牌
- `jobs`: Jenkins作业配置

#### 4.1.5 log 日志配置
- `filePattern`: 日志文件路径模式

### 4.2 extends 继承机制
通过 `extends` 字段指定要继承的基础配置，可以减少重复配置，提高配置的可维护性。
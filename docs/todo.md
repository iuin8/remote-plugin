# 待办

## 后续计划

### scp显示进度和速度

### 添加Jenkins最新构建记录查看(分支, 提交信息, 构建人等)


### 看看是否需要自动维护下ssh config配置, 帮助简化使用步骤

在`remote.yml`同级目录下, 通过`remote.yml`中的相关ssh配置, 自动创建个`ssh_config`文件
然后, 在用户的`~/.ssh/config`文件中添加个配置项去引用这个文件
这里要免密登录(PS: 需要用户自己确保自己的公钥已经上传到目标服务器中了)
也可以考虑看看是否用`expect`还是用`ssh-copy-id`, 或者结合使用

### 集成Jenkins插件(done)

这样能保留构建人, 以及本次构建的git提交信息, 并且统一固定了构建的分支

参考: https://www.doubao.com/thread/we5ec9a7d5ca63b9c

#### 整理Jenkins任务相关代码

### 把切换到指定用户的功能改成配置项: 可指定要切成哪个用户

### 指定某些模块不自动注册remote分组的任务

例如: component模块以及其子模块就不需要注册这些remote分组的任务

### 添加新的认证方式: 使用 expect 脚本自动化认证

示例:

```bash
# 创建expect脚本
nano restart-service.exp
```

```bash
#!/usr/bin/expect
spawn ssh dev.aaa.bbb bash -lc 'su - www -c "systemctl restart order-service"'
expect "Password:"
send "www_user_password\r"
expect eof
```

```bash
chmod +x restart-service.exp
./restart-service.exp
```

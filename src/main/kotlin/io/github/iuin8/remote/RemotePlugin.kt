package io.github.iuin8.remote

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.tasks.Exec
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Properties

// 导入工具类
import io.github.iuin8.remote.RemotePluginUtils

class RemotePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // 默认脚本目录：优先使用项目根目录下的 gradle/remote-plugin
        val scriptDirFile = File(project.rootDir, "gradle/remote-plugin")
        val scriptDir = scriptDirFile.absolutePath

        // 扫描环境配置
        val environments = mutableSetOf<String>()
        
        // 1. 从remote.yml的environments部分获取环境配置
        val remoteYmlFile = File(project.rootDir, "gradle/remote-plugin/remote.yml")
        if (remoteYmlFile.exists()) {
            try {
                val config = RemotePluginUtils.parseSimpleYaml(remoteYmlFile)
                // 提取所有environments.XXX前缀的环境名
                config.keys.forEach { key ->
                    if (key.startsWith("environments.")) {
                        val parts = key.split('.')
                        if (parts.size >= 3) {
                            environments.add(parts[1])
                        }
                    }
                }
            } catch (e: Exception) {
                println("[remote-plugin] 解析remote.yml时出错: ${e.message}")
            }
        }
        
        // 2. 从properties文件获取环境配置（保持向后兼容）
        val propertyFiles = project.rootDir.listFiles { _, name ->
              name.startsWith("gradle-") && name.endsWith(".properties")
          }?.toList() ?: emptyList()
        
        propertyFiles.forEach { propFile ->
            val fileName = propFile.name
            val profile = fileName.replace(Regex("^gradle-(.*)\\.properties$"), "$1")
            environments.add(profile)
        }

        // 在所有子模块注册任务
        project.subprojects.forEach { sub ->
            environments.forEach { profile ->
                // val profileCap = profile.substring(0, 1).toUpperCase() + profile.substring(1) // 不再使用

                // 发布任务
                sub.tasks.register("${profile}_publish", Exec::class.java) { t ->
                    t.group = "remote"
                    publishTask(t, profile, scriptDir)
                }

                // Debug任务
                sub.tasks.register("${profile}_debug", Exec::class.java) { t ->
                    t.group = "remote"
                    debugTask(t, profile, scriptDir)
                }

                // Arthas任务
                sub.tasks.register("${profile}_arthas", Exec::class.java) { t ->
                    t.group = "remote"
                    arthasTask(t, profile, scriptDir)
                }

                // 日志任务
                sub.tasks.register("${profile}_log") { t ->
                    t.group = "remote"
                    logTask(t, profile)
                }

                // 重启任务
                sub.tasks.register("${profile}_restart", Exec::class.java) { t ->
                    t.group = "remote"
                    restartTask(t, profile, scriptDir)
                }

                // Jenkins构建任务
                sub.tasks.register("${profile}_jenkins_build") { t ->
                    t.group = "remote"
                    jenkinsBuildTask(t, profile, scriptDir)
                }
            }
        }
    }

    companion object {

        @JvmStatic
        fun publishTask(task: Exec, profile: String, scriptDir: String) {
            RemotePluginUtils.configureTaskToDependOnBootJar(task)

            task.workingDir = File(scriptDir)
            task.isIgnoreExitValue = true

            task.doFirst {
                RemotePluginUtils.envLoad(task, profile)

                val extra = task.extensions.extraProperties
                println("[publish] 项目: ${task.project.name} 环境: $profile 服务器: ${if (extra.has("remote.server")) extra.get("remote.server") else "未设置"} 基础目录: ${if (extra.has("remote.base.dir")) extra.get("remote.base.dir") else "未设置"}")
                if (!extra.has("remote.server")) {
                    val allProperties = mutableMapOf<String, Any?>()
                    extra.properties.forEach { (key, value) ->
                        allProperties[key] = value
                    }
                    throw GradleException("环境变量不存在，当前所有属性: $allProperties")
                }

                // 读取服务端口并注入环境变量
                val servicePort = RemotePluginUtils.getServicePort(task, scriptDir)

                task.environment(
                    mapOf(
                        "TERM" to "xterm",
                        "LOCAL_BASE_DIR" to task.project.rootDir.absolutePath,
                        "REMOTE_SERVER" to extra.get("remote.server").toString(),
                        "REMOTE_BASE_DIR" to if (extra.has("remote.base.dir")) extra.get("remote.base.dir").toString() else "",
                        "SERVICE_NAME" to task.project.name,
                        "SERVICE_PORT" to servicePort,
                        "SERVICE_DIR" to java.io.File(task.project.rootDir, task.project.name).absolutePath
                    )
                )

                val remoteServer = extra.get("remote.server").toString()
                val remoteBaseDir = if (extra.has("remote.base.dir")) extra.get("remote.base.dir").toString() else ""
                if (remoteBaseDir.isBlank()) throw GradleException("remote.base.dir 未设置")
                val serviceName = task.project.name
                val serviceDir = File(task.project.rootDir, serviceName)
                val libsDir = File(serviceDir, "build/libs")
                if (!libsDir.exists()) throw GradleException("未找到目录: ${libsDir.absolutePath}")
                val jar = libsDir.listFiles { f -> f.isFile && f.name.startsWith("$serviceName-") && f.name.endsWith(".jar") }?.maxByOrNull { it.lastModified() }
                if (jar == null) throw GradleException("未找到可上传的JAR: ${libsDir.absolutePath}")
                val scpTarget = "$remoteServer:$remoteBaseDir/$serviceName/"
                val scpCmdStr = "scp ${jar.absolutePath} $scpTarget"
                println("[cmd] $scpCmdStr")
                task.project.exec { it.commandLine("scp", jar.absolutePath, "$remoteServer:$remoteBaseDir/$serviceName/") }
                val chownTarget = "$remoteBaseDir/$serviceName/$serviceName-*.jar"
                val chownCmdStr = "ssh $remoteServer bash -lc 'chown www:www $chownTarget'"
                println("[cmd] $chownCmdStr")
                task.project.exec { it.commandLine("ssh", remoteServer, "bash -lc 'chown www:www $remoteBaseDir/$serviceName/$serviceName-*.jar'") }

                val startCmd = io.github.iuin8.remote.RemotePluginUtils.resolveStartCommand(task, remoteBaseDir, serviceName)
                val sshStartCmdStr = "ssh $remoteServer bash -lc 'su - www -c \"$startCmd\"'"
                println("[cmd] $sshStartCmdStr")
                task.commandLine("ssh", remoteServer, "bash -lc 'su - www -c \"$startCmd\"'")
            }

            task.doLast {
                // 为兼容 Gradle 6，避免使用 executionResult（Gradle 7+ 才稳定提供）
                // Exec 任务在 ignoreExitValue=true 时不会使构建失败，这里不强行读取退出码
                println("[publish] ${task.project.name} 发布脚本执行完成")
            }
        }

        @JvmStatic
        fun debugTask(task: Exec, profile: String, scriptDir: String) {
            task.onlyIf { task.project.tasks.findByName("bootJar") != null }
            task.isIgnoreExitValue = true
            task.doFirst {
                RemotePluginUtils.envLoad(task, profile)
                val extra = task.extensions.extraProperties
                if (!extra.has("remote.server")) {
                    val allProperties = mutableMapOf<String, Any?>()
                    extra.properties.forEach { (key, value) -> allProperties[key] = value }
                    throw GradleException("环境变量不存在，当前所有属性: $allProperties")
                }
                val remoteServer = extra.get("remote.server").toString()
                val remoteBaseDir = if (extra.has("remote.base.dir")) extra.get("remote.base.dir").toString() else ""
                if (remoteBaseDir.isBlank()) throw GradleException("remote.base.dir 未设置")
                val serviceName = task.project.name
                val servicePort = RemotePluginUtils.getServicePort(task, scriptDir)
                val startCmd = RemotePluginUtils.resolveStartCommand(task, remoteBaseDir, serviceName)
                val envMap = RemotePluginUtils.resolveStartEnv(task, remoteBaseDir, serviceName, servicePort)
                val export = RemotePluginUtils.buildExportEnv(envMap)
                val full = if (export.isBlank()) startCmd else "$export && $startCmd"
                val debugSshCmdStr = "ssh -tt -o SendEnv=TERM -o RequestTTY=force $remoteServer bash -lc 'su - www -c \"$full\"'"
                println("[cmd] $debugSshCmdStr")
                task.setCommandLine(listOf("ssh", "-tt", "-o", "SendEnv=TERM", "-o", "RequestTTY=force", remoteServer, "bash -lc 'su - www -c \"$full\"'"))
                task.standardInput = System.`in`
                task.standardOutput = System.out
                task.errorOutput = System.err
            }

            task.doLast {
                // 为兼容 Gradle 6，避免使用 executionResult（Gradle 7+ 才稳定提供）
                println("[debug] ${task.project.name} 调试脚本执行完成")
            }
        }

        @JvmStatic
        fun arthasTask(task: Exec, profile: String, scriptDir: String) {
            // 只需要bootjar任务存在即可，不需要依赖它
            task.onlyIf { task.project.tasks.findByName("bootJar") != null }

            task.doFirst {
                RemotePluginUtils.envLoad(task, profile)

                // 获取服务端口并转换为Arthas端口（1开头）
                val servicePort = RemotePluginUtils.getServicePort(task, scriptDir)
                val arthasPort = "1$servicePort"

                val extra = task.extensions.extraProperties
                task.environment(
                    mapOf(
                        "TERM" to "xterm",
                        "LOCAL_BASE_DIR" to task.project.rootDir.absolutePath,
                        "REMOTE_SERVER" to extra.get("remote.server").toString(),
                        "REMOTE_BASE_DIR" to if (extra.has("remote.base.dir")) extra.get("remote.base.dir").toString() else ""
                    )
                )

                val remoteServer = extra.get("remote.server").toString()
                println("正在通过SSH连接到 $remoteServer 并启动Arthas(${task.project.name}:$arthasPort)...")

                val arthasCmdStr = "ssh -tt -o SendEnv=TERM -o RequestTTY=force $remoteServer bash -c 'stty intr ^c; export TERM=xterm; telnet localhost $arthasPort'"
                println("[cmd] $arthasCmdStr")
                task.setCommandLine(
                    listOf(
                        "ssh", "-tt",
                        "-o", "SendEnv=TERM",
                        "-o", "RequestTTY=force",
                        remoteServer,
                        "bash -c 'stty intr ^c; export TERM=xterm; telnet localhost $arthasPort'"
                    )
                )

                task.standardInput = System.`in`
                task.standardOutput = System.out
                task.errorOutput = System.err
            }
        }

        @JvmStatic
        fun restartTask(task: Exec, profile: String, scriptDir: String) {
            task.onlyIf { task.project.tasks.findByName("bootJar") != null }
            task.doFirst {
                RemotePluginUtils.envLoad(task, profile)
                val extra = task.extensions.extraProperties
                if (!extra.has("remote.server")) {
                    val all = mutableMapOf<String, Any?>()
                    extra.properties.forEach { (k, v) -> all[k] = v }
                    throw GradleException("环境变量不存在，当前所有属性: $all")
                }
                val remoteServer = extra.get("remote.server").toString()
                val remoteBaseDir = if (extra.has("remote.base.dir")) extra.get("remote.base.dir").toString() else ""
                if (remoteBaseDir.isBlank()) throw GradleException("remote.base.dir 未设置")
                val serviceName = task.project.name
                val startCmd = RemotePluginUtils.resolveStartCommand(task, remoteBaseDir, serviceName)
                val restartCmdStr = "ssh -tt -o SendEnv=TERM -o RequestTTY=force $remoteServer bash -lc 'su - www -c \"$startCmd\"'"
                println("[cmd] $restartCmdStr")
                task.setCommandLine(listOf("ssh", "-tt", "-o", "SendEnv=TERM", "-o", "RequestTTY=force", remoteServer, "bash -lc 'su - www -c \"$startCmd\"'"))
                task.standardInput = System.`in`
                task.standardOutput = System.out
                task.errorOutput = System.err
            }
        }

        @JvmStatic
        fun logTask(task: Task, profile: String) {
            // 只需要bootjar任务存在即可，不需要依赖它
            task.onlyIf { task.project.tasks.findByName("bootJar") != null }

            task.doFirst {
                RemotePluginUtils.envLoad(task, profile)

                val extra = task.extensions.extraProperties
                println("[log] 项目: ${task.project.name} 环境: $profile 服务器: ${if (extra.has("remote.server")) extra.get("remote.server") else "未设置"} 基础目录: ${if (extra.has("remote.base.dir")) extra.get("remote.base.dir") else "未设置"}")
                if (!extra.has("remote.server")) {
                    val allProperties = mutableMapOf<String, Any?>()
                    extra.properties.forEach { (key, value) ->
                        allProperties[key] = value
                    }
                    throw GradleException("环境变量不存在，当前所有属性: $allProperties")
                }
            }

            task.doLast {
                val serviceName = task.project.name
                val extra = task.extensions.extraProperties
                val remoteServer = extra.get("remote.server").toString()
                val remoteBaseDir = if (extra.has("remote.base.dir")) extra.get("remote.base.dir").toString() else ""
                val logFilePath = RemotePluginUtils.resolveLogFilePath(task, serviceName, remoteBaseDir)

                println("找到服务 $serviceName")
                println("正在通过SSH连接到 $remoteServer 并开始打印服务 $serviceName 的日志 $logFilePath")
                val cmdStr = "ssh $remoteServer ${RemotePluginUtils.buildRemoteTailCmd(logFilePath)}"
                println("[cmd] $cmdStr")

                task.project.exec { execSpec ->
                    execSpec.environment("TERM", "xterm")
                    execSpec.isIgnoreExitValue = true
                    execSpec.commandLine(
                        "ssh",
                        remoteServer,
                        RemotePluginUtils.buildRemoteTailCmd(logFilePath)
                    )
                    execSpec.standardOutput = System.out
                    execSpec.errorOutput = System.err
                }
                println("日志流结束")
            }
        }

        @JvmStatic
        fun jenkinsBuildTask(task: Task, profile: String, scriptDir: String) {
            task.doFirst {
                val jenkinsConfig = RemotePluginUtils.getJenkinsConfig(task, profile)
                val url = jenkinsConfig["url"]
                val user = jenkinsConfig["user"]
                val token = jenkinsConfig["token"]
                val job = jenkinsConfig["job"]

                if (url == null || user == null || token == null || job == null) {
                    throw GradleException("[jenkins] 配置不完整，请在 remote.yml 中配置 jenkins.{url,user,token,job}")
                }

                println("[jenkins] 触发构建: $job (环境: $profile)")
                println("[jenkins] Jenkins URL: $url")
                
                // 检查 jq 工具是否可用
                try {
                    task.project.exec { 
                        it.commandLine("which", "jq")
                        it.standardOutput = java.io.ByteArrayOutputStream()
                    }
                } catch (e: Exception) {
                    println("[jenkins] 警告: 未安装 jq 工具，输出将是原始 JSON 格式")
                    println("[jenkins] 建议安装: brew install jq")
                }

                // 设置环境变量供shell脚本使用
                task.extensions.extraProperties.apply {
                    set("JENKINS_URL", url)
                    set("JENKINS_USER", user)
                    set("JENKINS_TOKEN", token)
                    set("JENKINS_JOB", job)
                }
            }

            task.doLast {
                val extra = task.extensions.extraProperties
                val url = extra.get("JENKINS_URL").toString()
                val user = extra.get("JENKINS_USER").toString()
                val token = extra.get("JENKINS_TOKEN").toString()
                val job = extra.get("JENKINS_JOB").toString()

                val shellScript = """
                    set -e
                    
                    # 将job路径转换为Jenkins API格式
                    # "东箭-后端(Test环境)/marketing-service" -> "东箭-后端(Test环境)/job/marketing-service"
                    # 每个 / 都替换为 /job/，但第一个除外
                    JOB_API_PATH=$(echo "$job" | sed 's#/#/job/#g' | sed 's#^/job/##')
                    
                    echo "[jenkins] Job路径: $job"
                    echo "[jenkins] API路径: ${'$'}JOB_API_PATH"
                    
                    # URL编码（使用Python，比jq更可靠）
                    if command -v python3 &> /dev/null; then
                        ENCODED_JOB=$(python3 -c "import urllib.parse; print(urllib.parse.quote('${'$'}JOB_API_PATH', safe='/'))")
                    elif command -v python &> /dev/null; then
                        ENCODED_JOB=$(python -c "import urllib; print urllib.quote('${'$'}JOB_API_PATH', safe='/')")
                    else
                        # 降级：不编码，希望Jenkins能处理
                        ENCODED_JOB="${'$'}JOB_API_PATH"
                        echo "[jenkins] 警告: 未找到Python，无法进行URL编码"
                    fi
                    
                    # 触发构建
                    echo "[jenkins] 正在触发构建..."
                    BUILD_URL="$url/job/${'$'}ENCODED_JOB/build"
                    echo "[jenkins] 请求URL: ${'$'}BUILD_URL"
                    
                    # 获取队列URL（从Location header）
                    QUEUE_URL=${'$'}(curl -X POST "${'$'}BUILD_URL" \
                        --globoff \
                        -u "$user:$token" \
                        -s -D - -o /dev/null | grep -i "^Location:" | sed 's/Location: //i' | tr -d '\r')
                    
                    if [ -z "${'$'}QUEUE_URL" ]; then
                        echo "[jenkins] 错误: 未能获取队列URL，可能触发失败"
                        exit 1
                    fi
                    
                    echo "[jenkins] 已加入队列: ${'$'}QUEUE_URL"
                    
                    # 轮询队列，获取实际构建号
                    echo "[jenkins] 等待构建开始..."
                    MAX_QUEUE_WAIT=30
                    QUEUE_COUNT=0
                    BUILD_NUMBER=""
                    
                    while [ ${'$'}QUEUE_COUNT -lt ${'$'}MAX_QUEUE_WAIT ]; do
                        QUEUE_INFO=${'$'}(curl -s "${'$'}{QUEUE_URL}api/json" --globoff -u "$user:$token")
                        
                        # 检查构建是否已开始（executable字段存在）
                        BUILD_NUMBER=${'$'}(echo "${'$'}QUEUE_INFO" | jq -r '.executable.number // empty')
                        
                        if [ -n "${'$'}BUILD_NUMBER" ]; then
                            echo "[jenkins] 构建已开始，构建号: #${'$'}BUILD_NUMBER"
                            break
                        fi
                        
                        # 检查是否被取消
                        CANCELLED=${'$'}(echo "${'$'}QUEUE_INFO" | jq -r '.cancelled // false')
                        if [ "${'$'}CANCELLED" = "true" ]; then
                            echo "[jenkins] 构建在队列中被取消"
                            exit 1
                        fi
                        
                        QUEUE_COUNT=$((QUEUE_COUNT + 1))
                        echo -n "."
                        sleep 1
                    done
                    echo ""
                    
                    if [ -z "${'$'}BUILD_NUMBER" ]; then
                        echo "[jenkins] 警告: 超时未获取到构建号，使用lastBuild"
                        INFO_URL="$url/job/${'$'}ENCODED_JOB/lastBuild/api/json"
                    else
                        # 使用具体的构建号
                        INFO_URL="$url/job/${'$'}ENCODED_JOB/${'$'}BUILD_NUMBER/api/json"
                    fi
                    
                    # 等待构建信息就绪
                    echo "[jenkins] 获取构建信息..."
                    sleep 2
                    
                    MAX_RETRIES=6
                    RETRY_COUNT=0
                    while [ ${'$'}RETRY_COUNT -lt ${'$'}MAX_RETRIES ]; do
                        BUILD_JSON=${'$'}(curl -s "${'$'}INFO_URL" --globoff -u "$user:$token")
                        
                        # 检查是否有changeSet数据
                        if echo "${'$'}BUILD_JSON" | jq -e '.changeSet.items | length > 0' &> /dev/null; then
                            echo "[jenkins] 成功获取构建信息（包含提交记录）"
                            break
                        else
                            RETRY_COUNT=$((RETRY_COUNT + 1))
                            if [ ${'$'}RETRY_COUNT -lt ${'$'}MAX_RETRIES ]; then
                                echo "[jenkins] 提交记录尚未就绪，等待3秒后重试... (${'$'}RETRY_COUNT/${'$'}MAX_RETRIES)"
                                sleep 3
                            else
                                echo "[jenkins] 已达最大重试次数，使用当前数据"
                            fi
                        fi
                    done
                    
                    # 尝试使用 jq 格式化输出，如果不可用则输出原始JSON
                    if command -v jq &> /dev/null; then
                        echo ""
                        echo "=================================================="
                        echo " Jenkins 构建信息"
                        echo "=================================================="
                        echo "${'$'}BUILD_JSON" | jq -r '
                            "任务: " + .fullDisplayName,
                            "构建号: #" + (.number|tostring),
                            "结果: " + (.result // "进行中"),
                            "URL: " + .url,
                            "分支: " + (.actions[]?.lastBuiltRevision?.branch?[]?.name // "未知"),
                            "--------------------------------------------------",
                            "提交记录:",
                            (.changeSet.items[] | "  - [" + .author.fullName + "] " + .msg)
                        '
                        echo "=================================================="
                    else
                        echo ""
                        echo "构建已触发，详细信息请查看 Jenkins:"
                        echo "${'$'}BUILD_JSON" | grep -o '"url":"[^"]*"' | head -1 | cut -d'"' -f4
                        echo ""
                        echo "提示: 安装 jq 工具可获得更好的显示效果: brew install jq"
                    fi
                """.trimIndent()

                task.project.exec { execSpec ->
                    execSpec.commandLine("bash", "-c", shellScript)
                    execSpec.standardOutput = System.out
                    execSpec.errorOutput = System.err
                }
            }
        }
    }
}

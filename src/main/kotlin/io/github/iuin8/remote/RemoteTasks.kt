package io.github.iuin8.remote

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import java.io.File
import javax.inject.Inject

abstract class BaseRemoteTask @Inject constructor(
    @get:Internal protected val execOperations: ExecOperations,
    @get:Internal protected val projectLayout: ProjectLayout,
    @get:Internal protected val objectFactory: ObjectFactory
) : DefaultTask() {

    @get:Input
    abstract val profile: Property<String>

    @get:Internal
    abstract val extraProperties: MapProperty<String, Any>

    @get:Input
    abstract val serviceName: Property<String>

    @get:Input
    abstract val rootDir: Property<File>

    @get:Input
    @get:Optional
    abstract val sensitive: Property<Boolean>

    init {
        sensitive.convention(false)
    }

    @Internal
    protected fun getExtra(): Map<String, Any> = extraProperties.get()

    protected fun applyCommonEnv(spec: ExecSpec, servicePort: String? = null) {
        val extra = getExtra()
        val envMap = mutableMapOf(
            "TERM" to "xterm",
            "LOCAL_BASE_DIR" to rootDir.get().absolutePath,
            "RP_PROJECT_ROOT_PATH" to rootDir.get().absolutePath,
            "REMOTE_SERVER" to (extra["ssh.server"]?.toString() ?: ""),
            "REMOTE_BASE_DIR" to (extra["remote.base_dir"]?.toString() ?: ""),
            "SERVICE_NAME" to serviceName.get(),
            "SERVICE_DIR" to File(rootDir.get(), serviceName.get()).absolutePath
        )
        if (servicePort != null) {
            envMap["SERVICE_PORT"] = servicePort
        }
        spec.environment(envMap)
    }
}

abstract class RemotePreCheckTask @Inject constructor(
    execOperations: ExecOperations,
    projectLayout: ProjectLayout,
    objectFactory: ObjectFactory
) : BaseRemoteTask(execOperations, projectLayout, objectFactory) {
    
    @TaskAction
    fun run() {
        // 预检查逻辑主要在 whenReady 中通过 checkConfirmation 执行
        // 这里可以添加其他运行时检查
    }
}

abstract class RemotePublishTask @Inject constructor(
    execOperations: ExecOperations,
    projectLayout: ProjectLayout,
    objectFactory: ObjectFactory
) : BaseRemoteTask(execOperations, projectLayout, objectFactory) {

    @TaskAction
    fun run() {
        val extra = getExtra()
        val profileName = profile.get()
        val sName = serviceName.get()
        val rDir = rootDir.get()

        println("[publish] 项目: $sName 环境: $profileName 服务器: ${extra["ssh.server"] ?: "未设置"} 基础目录: ${extra["remote.base_dir"] ?: "未设置"}")
        
        val remoteServer = extra["ssh.server"]?.toString() ?: throw GradleException("ssh.server 未设置")
        val remoteBaseDir = extra["remote.base_dir"]?.toString() ?: throw GradleException("remote.base_dir 未设置")
        
        val servicePort = RemotePluginUtils.getServicePort(extra, sName)
        
        val serviceDir = File(rDir, sName)
        val libsDir = File(serviceDir, "build/libs")
        if (!libsDir.exists()) throw GradleException("未找到目录: ${libsDir.absolutePath}")
        
        val jar = libsDir.listFiles { f -> f.isFile && f.name.startsWith("$sName-") && f.name.endsWith(".jar") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("未找到可上传的JAR: ${libsDir.absolutePath}")

        val scpTarget = "$remoteServer:$remoteBaseDir/$sName/"
        println("[cmd] scp ${jar.absolutePath} $scpTarget")
        
        execOperations.exec { spec ->
            spec.environment("RP_PROJECT_ROOT_PATH", rDir.absolutePath)
            spec.commandLine(RemotePluginUtils.wrapWithPty(listOf("scp", jar.absolutePath, scpTarget)))
            spec.standardOutput = System.out
            spec.errorOutput = System.err
        }

        val sshUser = extra["ssh.user"]?.toString() ?: ""
        if (sshUser.isNotBlank()) {
            val chownTarget = "$remoteBaseDir/$sName/$sName-*.jar"
            println("[cmd] ssh $remoteServer bash -lc 'chown $sshUser:$sshUser $chownTarget'")
            execOperations.exec { spec ->
                spec.environment("RP_PROJECT_ROOT_PATH", rDir.absolutePath)
                spec.commandLine("ssh", remoteServer, "bash -lc 'chown $sshUser:$sshUser $chownTarget'")
            }
        }

        val startCmd = RemotePluginUtils.resolveStartCommand(extra, remoteBaseDir, sName, servicePort)
        val remoteCmd = RemotePluginUtils.wrapRemoteCommand(startCmd, sshUser)
        
        println("[cmd] ssh $remoteServer $remoteCmd")
        execOperations.exec { spec ->
            applyCommonEnv(spec, servicePort)
            spec.commandLine("ssh", remoteServer, remoteCmd)
        }
        
        println("[publish] $sName 发布脚本执行完成")
    }
}

abstract class RemoteDebugTask @Inject constructor(
    execOperations: ExecOperations,
    projectLayout: ProjectLayout,
    objectFactory: ObjectFactory
) : BaseRemoteTask(execOperations, projectLayout, objectFactory) {

    @TaskAction
    fun run() {
        val extra = getExtra()
        val sName = serviceName.get()
        val remoteServer = extra["ssh.server"]?.toString() ?: throw GradleException("ssh.server 未设置")
        val remoteBaseDir = extra["remote.base_dir"]?.toString() ?: throw GradleException("remote.base_dir 未设置")
        
        val servicePort = RemotePluginUtils.getServicePort(extra, sName)
        val startCmd = RemotePluginUtils.resolveStartCommand(extra, remoteBaseDir, sName, servicePort)
        val envMap = RemotePluginUtils.resolveStartEnv(extra, remoteBaseDir, sName, servicePort)
        val export = RemotePluginUtils.buildExportEnv(envMap)
        val full = if (export.isBlank()) startCmd else "$export && $startCmd"
        
        val sshUser = extra["ssh.user"]?.toString() ?: ""
        val remoteCmd = RemotePluginUtils.wrapRemoteCommand(full, sshUser)
        
        println("[cmd] ssh -tt -o SendEnv=TERM -o RequestTTY=force $remoteServer $remoteCmd")
        execOperations.exec { spec ->
            applyCommonEnv(spec)
            spec.isIgnoreExitValue = true
            spec.commandLine("ssh", "-tt", "-o", "SendEnv=TERM", "-o", "RequestTTY=force", remoteServer, remoteCmd)
            spec.standardInput = System.`in`
            spec.standardOutput = System.out
            spec.errorOutput = System.err
        }
        println("[debug] $sName 调试脚本执行完成")
    }
}

abstract class RemoteArthasTask @Inject constructor(
    execOperations: ExecOperations,
    projectLayout: ProjectLayout,
    objectFactory: ObjectFactory
) : BaseRemoteTask(execOperations, projectLayout, objectFactory) {

    @TaskAction
    fun run() {
        val extra = getExtra()
        val sName = serviceName.get()
        val servicePort = RemotePluginUtils.getServicePort(extra, sName)
        val arthasPort = "1$servicePort"
        val remoteServer = extra["ssh.server"]?.toString() ?: throw GradleException("ssh.server 未设置")
        
        println("正在通过SSH连接到 $remoteServer 并启动Arthas($sName:$arthasPort)...")
        val cmd = "bash -c 'stty intr ^c; export TERM=xterm; telnet localhost $arthasPort'"
        val sshUser = extra["ssh.user"]?.toString() ?: ""
        val remoteCmd = RemotePluginUtils.wrapRemoteCommand(cmd, sshUser)
        
        execOperations.exec { spec ->
            applyCommonEnv(spec, servicePort)
            spec.isIgnoreExitValue = true
            spec.commandLine("ssh", "-tt", "-o", "SendEnv=TERM", "-o", "RequestTTY=force", remoteServer, remoteCmd)
            spec.standardInput = System.`in`
            spec.standardOutput = System.out
            spec.errorOutput = System.err
        }
    }
}

abstract class RemoteLogTask @Inject constructor(
    execOperations: ExecOperations,
    projectLayout: ProjectLayout,
    objectFactory: ObjectFactory
) : BaseRemoteTask(execOperations, projectLayout, objectFactory) {

    @TaskAction
    fun run() {
        val extra = getExtra()
        val sName = serviceName.get()
        val remoteServer = extra["ssh.server"]?.toString() ?: throw GradleException("ssh.server 未设置")
        val remoteBaseDir = extra["remote.base_dir"]?.toString() ?: ""
        
        val servicePort = RemotePluginUtils.getServicePort(extra, sName)
        val logFilePath = RemotePluginUtils.resolveLogFilePath(extra, sName, remoteBaseDir, servicePort)

        println("找到服务 $sName")
        println("正在通过SSH连接到 $remoteServer 并开始打印服务 $sName 的日志 $logFilePath")
        
        execOperations.exec { spec ->
            spec.environment("RP_PROJECT_ROOT_PATH", rootDir.get().absolutePath)
            spec.environment("TERM", "xterm")
            spec.isIgnoreExitValue = true
            spec.commandLine("ssh", remoteServer, RemotePluginUtils.buildRemoteTailCmd(logFilePath))
            spec.standardOutput = System.out
            spec.errorOutput = System.err
        }
        println("日志流结束")
    }
}

abstract class RemoteRestartTask @Inject constructor(
    execOperations: ExecOperations,
    projectLayout: ProjectLayout,
    objectFactory: ObjectFactory
) : BaseRemoteTask(execOperations, projectLayout, objectFactory) {

    @TaskAction
    fun run() {
        val extra = getExtra()
        val sName = serviceName.get()
        val remoteServer = extra["ssh.server"]?.toString() ?: throw GradleException("ssh.server 未设置")
        val remoteBaseDir = extra["remote.base_dir"]?.toString() ?: throw GradleException("remote.base_dir 未设置")
        
        val servicePort = RemotePluginUtils.getServicePort(extra, sName)
        val startCmd = RemotePluginUtils.resolveStartCommand(extra, remoteBaseDir, sName, servicePort)
        
        val sshUser = extra["ssh.user"]?.toString() ?: ""
        val remoteCmd = RemotePluginUtils.wrapRemoteCommand(startCmd, sshUser)
        
        println("[cmd] ssh -tt -o SendEnv=TERM -o RequestTTY=force $remoteServer $remoteCmd")
        execOperations.exec { spec ->
            applyCommonEnv(spec)
            spec.isIgnoreExitValue = true
            spec.commandLine("ssh", "-tt", "-o", "SendEnv=TERM", "-o", "RequestTTY=force", remoteServer, remoteCmd)
            spec.standardInput = System.`in`
            spec.standardOutput = System.out
            spec.errorOutput = System.err
        }
    }
}

abstract class RemoteJenkinsBuildTask @Inject constructor(
    execOperations: ExecOperations,
    projectLayout: ProjectLayout,
    objectFactory: ObjectFactory
) : BaseRemoteTask(execOperations, projectLayout, objectFactory) {

    @TaskAction
    fun run() {
        JenkinsTask.jenkinsBuildTask(getExtra(), serviceName.get(), profile.get())
    }
}

abstract class RemoteJenkinsInfoTask @Inject constructor(
    execOperations: ExecOperations,
    projectLayout: ProjectLayout,
    objectFactory: ObjectFactory
) : BaseRemoteTask(execOperations, projectLayout, objectFactory) {

    @TaskAction
    fun run() {
        JenkinsTask.jenkinsLastBuildInfoTask(getExtra(), serviceName.get(), profile.get())
    }
}

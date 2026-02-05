package io.github.iuin8.remote

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * Main entry point for the Gradle Remote Plugin.
 * High-cohesion task registration logic focusing on maintainability.
 */
class RemotePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val scannedConfig = ConfigMerger.scanConfig(project)
        val environments = scannedConfig.environments
        val configuredServices = scannedConfig.configuredServices
        
        // Setup central SSH config
        SshConfigManager.setupSshConfig(project.rootDir, project.name)

        // Register tasks for applicable subprojects
        project.subprojects { sub ->
            if (!configuredServices.contains(sub.name)) return@subprojects

            environments.forEach { profile ->
                val groupName = "remote-$profile"
                registerEnvironmentTasks(sub, profile, groupName)
            }
        }
    }

    private fun registerEnvironmentTasks(sub: Project, profile: String, groupName: String) {
        // 1. Pre-Check Task
        val preCheck = sub.tasks.register("${profile}_pre_check", RemotePreCheckTask::class.java) { t ->
            configureBaseTask(t, groupName, profile, sub)
            t.sensitive.set(true)
        }

        // 2. Publish Task
        sub.tasks.register("${profile}_publish", RemotePublishTask::class.java) { t ->
            configureBaseTask(t, groupName, profile, sub)
            t.dependsOn(preCheck)
            RemotePluginUtils.configureTaskToDependOnBuild(sub, t)
        }

        // 3. Operational Tasks
        listOf(
            "debug" to RemoteDebugTask::class.java,
            "arthas" to RemoteArthasTask::class.java,
            "log" to RemoteLogTask::class.java,
            "restart" to RemoteRestartTask::class.java
        ).forEach { (name, type) ->
            sub.tasks.register("${profile}_$name", type) { t ->
                configureBaseTask(t, groupName, profile, sub)
            }
        }

        // 4. Jenkins Tasks
        sub.tasks.register("${profile}_jenkins_build", RemoteJenkinsBuildTask::class.java) { t ->
            configureBaseTask(t, groupName, profile, sub)
        }
        sub.tasks.register("${profile}_jenkins_info", RemoteJenkinsInfoTask::class.java) { t ->
            configureBaseTask(t, groupName, profile, sub)
        }
    }

    private fun configureBaseTask(t: BaseRemoteTask, groupName: String, profile: String, sub: Project) {
        t.group = groupName
        t.profile.set(profile)
        t.serviceName.set(sub.name)
        t.rootDir.set(sub.rootDir)
        t.projectDir.set(sub.projectDir)
        
        // Capture properties at configuration time to avoid execution-time Project access
        val envProperties = ConfigMerger.getEnvProperties(sub.rootProject, profile)
        t.extraProperties.set(envProperties)
    }
}
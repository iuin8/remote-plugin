package io.github.iuin8.remote

import org.gradle.api.Task
import java.net.URI

object JenkinsTask {

    /**
     * Jenkins构建任务
     * 使用 jenkins-client 库实现
     */
    fun jenkinsBuildTask(task: Task, platform: String) {
        val config = RemotePluginUtils.getJenkinsConfig(task, platform)
        val url = config["url"]
        val user = config["user"]
        val token = config["token"]
        val jobName = config["job"]

        if (url == null || user == null || token == null || jobName == null) {
            println("Jenkins配置不完整，跳过任务")
            return
        }

        task.doFirst {
            println("[jenkins] 触发构建: $jobName (环境: $platform)")
            println("[jenkins] Jenkins URL: $url")

            val jenkinsServer = try {
                com.offbytwo.jenkins.JenkinsServer(URI(url), user, token)
            } catch (e: Exception) {
                println("[jenkins] 连接失败: ${e.message}")
                throw e
            }

            // 尝试获取 Job
            try {
                // 递归查找嵌套Job
                val targetJob = findJobRecursive(jenkinsServer, jobName)
                
                if (targetJob != null) {
                    println("[jenkins] 正在触发构建...")
                    // true表示请求crumb，防止403
                    val queueRef = targetJob.build(true) 
                    
                    println("[jenkins] 已加入队列: ${queueRef.queueItemUrlPart}")
                    
                    // 等待构建开始并获取构建号
                    println("[jenkins] 等待构建开始...")
                    var build: com.offbytwo.jenkins.model.Build? = null
                    
                    // 轮询队列项 (30秒超时)
                    var maxWait = 30
                    while (maxWait > 0) {
                        val queueItem = jenkinsServer.getQueueItem(queueRef)
                        if (queueItem == null) {
                            // 有时队列项消化太快可能查不到，尝试直接查Job的最后构建？
                            // 这里简单重试
                            Thread.sleep(1000)
                            maxWait--
                            continue
                        }
                        
                        if (queueItem.executable != null) {
                            val buildInfo = queueItem.executable
                            println("[jenkins] 构建已开始，构建号: #${buildInfo.number}")
                            // 获取Build对象以便查询详情
                            build = targetJob.details().getBuildByNumber(buildInfo.number.toInt())
                            break
                        }
                        
                        if (queueItem.isCancelled) {
                             println("[jenkins] 构建在队列中被取消")
                             return@doFirst
                        }
                        
                        Thread.sleep(1000)
                        maxWait--
                    }
                    
                    if (build != null) {
                         // 获取构建信息
                         println("[jenkins] 获取构建信息...")
                         
                         // 尝试等待提交记录就绪 (因为 SCM Checkout 可能需要时间)
                         // 如果 changeSet 为空且重试次数内，则等待
                         var details = build.details()
                         var retryCount = 0
                         val maxRetries = 6 // 最多等待 18秒
                         
                         // 注意: 某些 Job 可能确实没有提交记录，所以我们只对自己判定"可能还没Checkou完成"的情况做有限等待
                         // 但 API 无法通过 stage 判断，只能盲等一会
                         while ((details.changeSet == null || details.changeSet.items.isEmpty()) && retryCount < maxRetries) {
                             println("[jenkins] 提交记录为空，正在等待 SCM Checkout... (${retryCount + 1}/$maxRetries)")
                             Thread.sleep(3000)
                             details = build.details()
                             retryCount++
                         }
                         
                         printBuildDetails(details)
                    } else {
                        println("[jenkins] 警告: 超时未获取到构建号，您可以稍后在Jenkins查看。")
                    }

                } else {
                    println("[jenkins] 错误: 无法找到Job '$jobName'")
                    println("提示: 请检查名称是否正确，支持 'Folder/SubFolder/JobName' 格式")
                }
                
            } catch (e: Exception) {
                println("[jenkins] 执行异常: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    // 递归查找 Job 的辅助函数
    private fun findJobRecursive(server: com.offbytwo.jenkins.JenkinsServer, path: String): com.offbytwo.jenkins.model.Job? {
        val parts = path.split("/")
        if (parts.isEmpty()) return null

        // 1. 获取根 Job
        val rootName = parts[0]
        var currentJob: com.offbytwo.jenkins.model.Job? = null
        
        try {
            // 优先尝试未编码名称
            currentJob = server.getJob(rootName)
            if (currentJob == null) {
                // 尝试编码后的名称
                val encodedRoot = java.net.URLEncoder.encode(rootName, "UTF-8").replace("+", "%20")
                currentJob = server.getJob(encodedRoot)
            }
        } catch (e: Exception) {
            println("[jenkins] 查找根任务异常: ${e.message}")
        }
        
        if (currentJob == null) {
            println("[jenkins] 未找到根任务: $rootName")
            return null
        }

        // 2. 逐级向下查找
        for (i in 1 until parts.size) {
            val subName = parts[i]
            // 将当前 Job 包装为 FolderJob 以便查找子任务
            // 解决 jenkins-client 可能不识别 FolderJob 类型的问题
            val folderWrapper = com.offbytwo.jenkins.model.FolderJob(currentJob!!.name, currentJob!!.url)
            
            try {
                var nextJob = server.getJob(folderWrapper, subName)
                if (nextJob == null) {
                    // 尝试编码查找
                    val encodedSub = java.net.URLEncoder.encode(subName, "UTF-8").replace("+", "%20")
                    nextJob = server.getJob(folderWrapper, encodedSub)
                }
                
                if (nextJob == null) {
                    println("[jenkins] 在 '${currentJob!!.name}' 下未找到子任务 '$subName'")
                    return null
                }
                currentJob = nextJob
            } catch (e: Exception) {
                println("[jenkins] 遍历子任务 '$subName' 时出错: ${e.message}")
                return null
            }
        }

        return currentJob
    }

    private fun printBuildDetails(details: com.offbytwo.jenkins.model.BuildWithDetails) {
        println("")
        println("==================================================")
        println(" Jenkins 构建信息")
        println("==================================================")
        println("任务: ${details.fullDisplayName}")
        println("构建号: #${details.number}")
        println("结果: ${details.result ?: "进行中"}")
        
        // 解析分支信息
        var branchName = "(未知)"
        try {
            val actions = details.actions
            if (actions != null) {
                for (action in actions) {
                    if (action is Map<*, *>) {
                        val lastBuiltRevision = action["lastBuiltRevision"] as? Map<*, *>
                        if (lastBuiltRevision != null) {
                            val branchList = lastBuiltRevision["branch"] as? List<*>
                            if (branchList != null && branchList.isNotEmpty()) {
                                val branchMap = branchList[0] as? Map<*, *>
                                val name = branchMap?.get("name")?.toString()
                                if (name != null) {
                                    branchName = name.replace("refs/remotes/", "").replace("origin/", "")
                                    break
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 解析失败忽略
        }
        
        println("分支: $branchName")
        println("URL: ${details.url}")
        
        println("--------------------------------------------------")
        println("提交记录:")
        if (details.changeSet != null && details.changeSet.items != null) {
            details.changeSet.items.forEach { item ->
                println("  - [${item.author.fullName}] ${item.msg}")
            }
        } else {
            println("  (无提交记录)")
        }
        println("==================================================")
    }
}

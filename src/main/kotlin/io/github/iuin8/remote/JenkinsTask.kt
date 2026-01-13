package io.github.iuin8.remote

import org.gradle.api.Task
import java.net.URI

object JenkinsTask {

    /**
     * Jenkins构建任务
     * 使用 jenkins-client 库实现
     */
    fun jenkinsBuildTask(task: Task, platform: String) {
        task.doFirst {
            // 确保环境配置已加载
            RemotePluginUtils.envLoad(task, platform)
            
            val config = RemotePluginUtils.getJenkinsConfig(task, platform)
            val url = config["url"]
            val user = config["user"]
            val token = config["token"]
            val jobName = config["job"]

            if (url == null || user == null || token == null || jobName == null) {
                println("[jenkins] Jenkins配置不完整，跳过任务。")
                return@doFirst
            }

            println("[jenkins] 触发构建: $jobName (环境: $platform)")

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
                         
                         var details = build.details()
                         var retryCount = 0
                         val maxRetries = 40 // 3秒一次，共2分钟
                         
                         while (retryCount < maxRetries) {
                             // 1. 如果已经有提交记录，直接退出循环
                             if (details.changeSet != null && details.changeSet.items.isNotEmpty()) {
                                 break
                             }
                             
                             // 2. 如果任务已经结束（成功、失败或取消），且还没有提交记录，直接退出
                             if (!details.isBuilding) {
                                 println("[jenkins] 构建已结束，共获取到 0 条提交记录")
                                 break
                             }
                             
                             // 3. 检查是否已经完成了 SCM Checkout (通过检查 Action 中是否包含 GitRevision)
                             if (hasScmAction(details)) {
                                 // 已检出但没记录，说明本次构建确实没有新的提交，无需再等
                                 println("[jenkins] SCM 检出已完成，本次无新增提交记录")
                                 break
                             }
                             
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
                }
                
            } catch (e: Exception) {
                println("[jenkins] 执行异常: ${e.message}")
            }
        }
    }

    /**
     * 查看 Jenkins 最后一次构建信息
     */
    fun jenkinsLastBuildInfoTask(task: Task, platform: String) {
        task.doFirst {
            // 确保环境配置已加载
            RemotePluginUtils.envLoad(task, platform)
            
            val config = RemotePluginUtils.getJenkinsConfig(task, platform)
            val url = config["url"]
            val user = config["user"]
            val token = config["token"]
            val jobName = config["job"]

            if (url == null || user == null || token == null || jobName == null) {
                println("[jenkins] Jenkins配置不完整，跳过任务。")
                return@doFirst
            }

            println("[jenkins] 正在获取最后一次构建信息: $jobName")

            val jenkinsServer = try {
                com.offbytwo.jenkins.JenkinsServer(URI(url), user, token)
            } catch (e: Exception) {
                println("[jenkins] 连接失败: ${e.message}")
                throw e
            }

            try {
                val targetJob = findJobRecursive(jenkinsServer, jobName)
                if (targetJob != null) {
                    val jobDetails = targetJob.details()
                    val lastBuild = jobDetails.lastBuild
                    if (lastBuild != null) {
                        printBuildDetails(lastBuild.details())
                    } else {
                        println("[jenkins] 任务 '$jobName' 尚无构建记录")
                    }
                } else {
                    println("[jenkins] 错误: 无法找到Job '$jobName'")
                }
            } catch (e: Exception) {
                println("[jenkins] 获取信息异常: ${e.message}")
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
            val parentJob = currentJob ?: return null
            
            // 将当前 Job 包装为 FolderJob 以便查找子任务
            // 解决 jenkins-client 可能不识别 FolderJob 类型的问题
            val folderWrapper = com.offbytwo.jenkins.model.FolderJob(parentJob.name, parentJob.url)
            
            try {
                var nextJob = server.getJob(folderWrapper, subName)
                if (nextJob == null) {
                    // 尝试编码查找
                    val encodedSub = java.net.URLEncoder.encode(subName, "UTF-8").replace("+", "%20")
                    nextJob = server.getJob(folderWrapper, encodedSub)
                }
                
                if (nextJob == null) {
                    println("[jenkins] 在 '${parentJob.name}' 下未找到子任务 '$subName'")
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
        
        // 解析分支和构建人信息
        var branchName = "(未知)"
        var startedBy = "(未知)"
        try {
            val actions = details.actions
            if (actions != null) {
                for (action in actions) {
                    if (action is Map<*, *>) {
                        // 1. 解析分支 (Git 插件)
                        val lastBuiltRevision = action["lastBuiltRevision"] as? Map<*, *>
                        if (lastBuiltRevision != null) {
                            val branchList = lastBuiltRevision["branch"] as? List<*>
                            if (branchList != null && branchList.isNotEmpty()) {
                                val branchMap = branchList[0] as? Map<*, *>
                                val name = branchMap?.get("name")?.toString()
                                if (name != null) {
                                    branchName = name.replace("refs/remotes/", "").replace("origin/", "")
                                }
                            }
                        }
                        
                        // 2. 解析构建人 (CauseAction)
                        val causes = action["causes"] as? List<*>
                        if (causes != null && causes.isNotEmpty()) {
                            val cause = causes[0] as? Map<*, *>
                            val userName = cause?.get("userName")?.toString()
                            val shortDescription = cause?.get("shortDescription")?.toString()
                            
                            if (userName != null) {
                                startedBy = userName
                            } else if (shortDescription != null) {
                                startedBy = shortDescription.replace("Started by ", "").replace("用户 ", "")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 解析失败忽略
        }
        
        println("分支: $branchName")
        println("构建人: $startedBy")
        
        // 解析时间信息
        try {
            val timestamp = details.timestamp
            val duration = details.duration
            
            if (timestamp > 0) {
                val startTime = java.time.Instant.ofEpochMilli(timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                
                val now = java.time.Instant.now()
                val diffSeconds = java.time.Duration.between(java.time.Instant.ofEpochMilli(timestamp), now).seconds
                val relativeTime = when {
                    diffSeconds < 60 -> "刚刚"
                    diffSeconds < 3600 -> "${diffSeconds / 60} 分钟前"
                    diffSeconds < 86400 -> "${diffSeconds / 3600} 小时前"
                    else -> "${diffSeconds / 86400} 天前"
                }

                println("开始时间: $startTime ($relativeTime)")
            }
            
            if (duration > 0) {
                val minutes = duration / 1000 / 60
                val seconds = (duration / 1000) % 60
                val durationStr = if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
                println("构建时长: $durationStr")
            } else if (details.isBuilding) {
                println("构建时长: 进行中...")
            }
        } catch (e: Exception) {
            // 时间解析失败忽略
        }
        
        // 处理 URL 编码，确保终端可点击 (特别是中文和小括号)
        val niceUrl = encodeJenkinsUrl(details.url)
        println("URL: $niceUrl")
        
        println("--------------------------------------------------")
        println("提交记录:")
        val items = details.changeSet?.items
        if (items != null && items.isNotEmpty()) {
            items.forEach { item ->
                println("  - [${item.author.fullName}] ${item.msg}")
            }
        } else {
            if (details.isBuilding) {
                if (hasScmAction(details)) {
                    println("  (SCM 检出已完成，本次无新增提交记录)")
                } else {
                    println("  (正在从 SCM 拉取记录，可稍后执行 _jenkins_last_build_info 或前往页面查看)")
                }
            } else {
                // 如果是已经结束的构建，且没有提交记录
                println("  (本次构建无代码变动，或未检测到新增提交)")
            }
        }
        println("==================================================")
    }

    private fun hasScmAction(details: com.offbytwo.jenkins.model.BuildWithDetails): Boolean {
        val actions = details.actions ?: return false
        for (action in actions) {
            if (action is Map<*, *>) {
                // Git 插件通常包含 lastBuiltRevision 或 remoteUrls
                if (action.containsKey("lastBuiltRevision") || action.containsKey("remoteUrls")) {
                    return true
                }
                // SVN 或其他插件
                if (action.containsKey("revision") || action.containsKey("buildsByBranchName")) {
                    return true
                }
            }
        }
        return false
    }

    private fun encodeJenkinsUrl(rawUrl: String): String {
        return try {
            val uri = java.net.URI(rawUrl)
            // 获取解码后的 path (例如 /job/测试(Test)/...)
            val decodedPath = uri.path
            
            // 手动对每一段进行 URL 编码
            // 注意: split("/") 会保留空字符串，这对于保留首尾斜杠很重要
            val encodedPath = decodedPath.split("/").joinToString("/") { part ->
                if (part.isEmpty()) "" else java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")
            }
            
            // 重组 URL (不使用 URI 类重组，因为 URI 类不会编码括号)
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$portPart$encodedPath"
        } catch (e: Exception) {
            // 降级处理: 仅做基础替换
            rawUrl.replace("(", "%28").replace(")", "%29")
        }
    }
}

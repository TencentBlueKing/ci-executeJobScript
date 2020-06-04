package com.tencent.bk.devops.atom.task

import com.tencent.bk.devops.atom.AtomContext
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import com.tencent.bk.devops.atom.task.pojo.FastExecuteScriptRequest
import com.tencent.bk.devops.atom.task.pojo.InnerJobParam
import com.tencent.bk.devops.atom.task.pojo.IpDTO
import com.tencent.bk.devops.atom.task.utils.JobUtils
import com.tencent.bk.devops.atom.task.utils.Keys
import com.tencent.bk.devops.atom.utils.json.JsonUtil
import java.nio.charset.Charset
import java.util.Base64
import org.slf4j.LoggerFactory

@AtomService(paramClass = InnerJobParam::class)
class JobScriptAtom : TaskAtom<InnerJobParam> {

    private var jobHost: String = ""
    private var esbApiHost: String = ""
    private var appId: String = ""
    private var appSecret: String = ""

    override fun execute(atomContext: AtomContext<InnerJobParam>) {
        val param = atomContext.param
        logger.info("param:${JsonUtil.toJson(param)}")
        exceute(param)
        logger.info("atom run success")
    }

    companion object {
        val logger = LoggerFactory.getLogger(JobScriptAtom::class.java)
    }

    fun exceute(param: InnerJobParam) {
        logger.info("开始执行脚本(Begin to execute script)")
        val esbHost = getConfigValue(Keys.ESB_HOST, param)
        val jobHost = getConfigValue(Keys.JOB_HOST, param)
        val appId = getConfigValue(Keys.BK_APP_ID, param)
        val appSecret = getConfigValue(Keys.BK_APP_SECRET, param)
        if (!checkVariable(jobHost, appId, appSecret)) {
            throw RuntimeException("请联系管理员，配置插件私有配置(Please contact administrator to init plugin private configuration)")
        }
        this.jobHost = jobHost!!.trim().trimEnd('/')
        this.esbApiHost = esbHost!!.trim().trimEnd('/')
        this.appId = appId!!
        this.appSecret = appSecret!!

        fastExecuteScript(param)
    }

    private fun fastExecuteScript(
        param: InnerJobParam
    ) {
        val bizId = param.bizId
        val buildId = param.pipelineBuildId
        val taskId = param.pipelineTaskId
        val targetAccount = param.account
        val timeout = 0L + (param.timeout ?: 1000)
        var operator = param.pipelineStartUserName
        val scriptContent =
            Base64.getEncoder().encodeToString(param.scriptContent.toByteArray(Charset.forName("UTF-8")))
        val scriptParam = Base64.getEncoder().encodeToString(param.scriptParam.toByteArray(Charset.forName("UTF-8")))

        val lastModifyUser = param.pipelineUpdateUserName
        if (null != lastModifyUser && operator != lastModifyUser) {
            // 以流水线的最后一次修改人身份执行；如果最后一次修改人也没有这个环境的操作权限，这种情况不考虑，有问题联系产品!
            logger.info("operator:$operator, lastModifyUser:$lastModifyUser")
            operator = lastModifyUser
        }
        val targetEnvType = param.targetEnvType
        val dynamicGroupIdListStr = param.dynamicGroupIdList
        logger.info("获取节点类型(targetEnvType)：$targetEnvType")
        var ipList = emptyList<IpDTO>()
        var dynamicGroupIdList = emptyList<String>()
        when (targetEnvType) {
            "MANUAL" -> {
                if (param.targetIpList.isEmpty()) {
                    throw RuntimeException("IpList is not init")
                }
                val ipArr = param.targetIpList.trim().split(",", "，", ";", "\n")
                logger.info("targetIpList:$ipArr")
                ipList = ipArr.map { IpDTO(it.split(":")[1].trim(), it.split(":")[0].trim().toLong()) }
            }
            "DYNAMIC_GROUP" -> {
                if (dynamicGroupIdListStr.isEmpty()) {
                    throw RuntimeException("dynamicGroupIdListStr is not init")
                }
                dynamicGroupIdList = dynamicGroupIdListStr.trim().split(",", "，", ";", "\n")
                logger.info("dynamicGroupIdList:$dynamicGroupIdList")
            }
            else -> {
                throw RuntimeException("Unsupported targetEnvType: $targetEnvType")
            }
        }

        val fastExecuteScriptReq = FastExecuteScriptRequest(
            appCode = this.appId,
            appSecret = this.appSecret,
            username = operator,
            bizId = bizId.toLong(),
            account = targetAccount,
            scriptType = param.scriptType.toInt(),
            scriptContent = scriptContent,
            scriptParam = scriptParam,
            scriptTimeout = timeout,
            dynamicGroupIdList = dynamicGroupIdList,
            ipList = ipList
        )

        val taskInstanceId = JobUtils.fastExecuteScript(fastExecuteScriptReq, this.esbApiHost)
        val startTime = System.currentTimeMillis()

        checkStatus(
            bizId = bizId,
            startTime = startTime,
            taskId = taskId,
            taskInstanceId = taskInstanceId,
            operator = operator,
            buildId = buildId,
            jobHost = esbApiHost
        )

        logger.info(JobUtils.getDetailUrl(bizId, taskInstanceId, jobHost))
    }

    private fun checkStatus(
        bizId: String,
        startTime: Long,
        taskInstanceId: Long,
        operator: String,
        buildId: String,
        taskId: String,
        jobHost: String
    ) {

        var jobSuccess = true

        while (jobSuccess) {
            Thread.sleep(2000)
            val taskResult = JobUtils.getTaskResult(appId, appSecret, bizId, taskInstanceId, operator, jobHost)
            if (taskResult.isFinish) {
                if (taskResult.success) {
                    logger.info("[$buildId]|SUCCEED|taskInstanceId=$taskId|${taskResult.msg}")
                    jobSuccess = false
                } else {
                    logger.info("[$buildId]|FAIL|taskInstanceId=$taskId|${taskResult.msg}")
                    throw RuntimeException("job execute fail, mssage:${taskResult.msg}")
                }
            } else {
                logger.info("执行中/Waiting for job:$taskInstanceId", taskId)
            }
        }
        logger.info("job执行耗时(Time consuming)：${System.currentTimeMillis() - startTime}")
    }

    private fun getConfigValue(key: String, param: InnerJobParam): String? {
        val configMap = param.bkSensitiveConfInfo
        if (configMap == null) {
            logger.warn("插件私有配置为空，请补充配置(Plugin private configuration is null, please config it)")
        }
        if (configMap.containsKey(key)) {
            return configMap[key]
        }
        return null
    }

    private fun checkVariable(jobHost: String?, appId: String?, appSecret: String?): Boolean {
        if (jobHost.isNullOrBlank()) {
            logger.error("请补充插件 Job Host 配置(Please config plugin private configuration:JOB_HOST)")
            return false
        }
        if (appId.isNullOrBlank()) {
            logger.error("请补充插件 Bk App Id 配置(Please config plugin private configuration:BK_APP_ID)")
            return false
        }
        if (appSecret.isNullOrBlank()) {
            logger.error("请补充插件 Bk App Secret 配置(Please config plugin private configuration:BK_APP_SECRET)")
            return false
        }
        return true
    }
}

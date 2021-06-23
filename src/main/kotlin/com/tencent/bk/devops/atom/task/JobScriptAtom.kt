package com.tencent.bk.devops.atom.task

import com.tencent.bk.devops.atom.AtomContext
import com.tencent.bk.devops.atom.common.Status
import com.tencent.bk.devops.atom.pojo.AtomResult
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import com.tencent.bk.devops.atom.task.pojo.FastExecuteScriptRequest
import com.tencent.bk.devops.atom.task.pojo.InnerJobParam
import com.tencent.bk.devops.atom.task.pojo.IpDTO
import com.tencent.bk.devops.atom.task.utils.JobUtils
import com.tencent.bk.devops.atom.task.utils.Keys
import com.tencent.bk.devops.atom.utils.json.JsonUtil
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.nio.charset.Charset
import java.util.*

@AtomService(paramClass = InnerJobParam::class)
class JobScriptAtom : TaskAtom<InnerJobParam> {

    private var jobHost: String = ""
    private var esbApiHost: String = ""
    private var appId: String = ""
    private var appSecret: String = ""

    override fun execute(atomContext: AtomContext<InnerJobParam>) {
        val param = atomContext.param
        val result = atomContext.result
        logger.info("param:${JsonUtil.toJson(param)}")
        exceute(param, result)
        logger.info("atom run success")
    }

    companion object {
        val logger = LoggerFactory.getLogger(JobScriptAtom::class.java)
    }

    fun exceute(param: InnerJobParam, result: AtomResult) {
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

        fastExecuteScript(param, result)
    }

    private fun printLink(bizId: String, taskInstanceId: Long, jobHost: String) {
        logger.info("对接蓝鲸企业版3.x/社区版6.x中的作业平台请点击以下链接查看详情：")
        logger.info(JobUtils.getV3DetailUrl(bizId, taskInstanceId, jobHost))
        logger.info("对接蓝鲸企业版2.x/社区版5.x中的作业平台请点击以下链接查看详情：")
        logger.info(JobUtils.getV2DetailUrl(bizId, taskInstanceId, jobHost))
    }

    private fun fastExecuteScript(
        param: InnerJobParam,
        result: AtomResult
    ) {
        val bizId = param.bizId
        val buildId = param.pipelineBuildId
        val taskId = param.pipelineTaskId
        val targetAccount = param.account
        val timeout = 0L + (param.timeout ?: 1000)
        if (timeout < 60L || timeout > 72000) {
            result.status = Status.failure
            result.message = "timeout参数不合法，必须为60-72000之间的整数"
            return
        }
        var operator = param.pipelineStartUserName
        var scriptContent = when (param.scriptType) {
            "1" -> {
                param.shellScriptContent
            }
            "2" -> {
                param.batScriptContent
            }
            "3" -> {
                param.perlScriptContent
            }
            "4" -> {
                param.pythonScriptContent
            }
            "5" -> {
                param.powershellScriptContent
            }
            else -> {
                ""
            }
        }
        // 兼容老版本插件字段
        if (StringUtils.isBlank(scriptContent)) {
            scriptContent = param.scriptContent
        }
        scriptContent =
            Base64.getEncoder().encodeToString(scriptContent.toByteArray(Charset.forName("UTF-8")))
        val scriptParam = Base64.getEncoder().encodeToString(param.scriptParam.toByteArray(Charset.forName("UTF-8")))

        val lastModifyUser = param.pipelineUpdateUserName
        if (null != lastModifyUser && operator != lastModifyUser) {
            // 以流水线的最后一次修改人身份执行；如果最后一次修改人也没有这个环境的操作权限，这种情况不考虑，有问题联系产品!
            logger.info("operator:$operator, lastModifyUser:$lastModifyUser")
            operator = lastModifyUser
        }
        val dynamicGroupIdListStr = param.dynamicGroupIdList
        var ipDTOList = emptyList<IpDTO>()
        var dynamicGroupIdList = emptyList<String>()
        if (param.targetIpList.isEmpty()) {
            logger.info("IpList is empty")
        } else {
            val ipList = param.targetIpList.trim().split(",", "，", ";", "\n").filter(StringUtils::isNotBlank).toList()
            logger.info("targetIpList:$ipList")
            try {
                ipDTOList = ipList.map { IpDTO(it.split(":", "：")[1].trim(), it.split(":", "：")[0].trim().toLong()) }
            } catch (e: IndexOutOfBoundsException) {
                result.status = Status.failure
                result.message = "IP输入格式不正确，请检查，正确格式：云区域ID:IP，例如0:192.168.1.1 (Please check IP format, right IP format:cloud area id:ip, ex:0:192.168.1.1)"
                return
            }
        }
        if (dynamicGroupIdListStr.isEmpty()) {
            logger.info("dynamicGroupIdListStr is empty")
        } else {
            dynamicGroupIdList = dynamicGroupIdListStr.trim().split(",", "，", ";", "\n").filter(StringUtils::isNotBlank).toList()
        }
        logger.info("dynamicGroupIdList:$dynamicGroupIdList")
        if (ipDTOList.isEmpty() && dynamicGroupIdList.isEmpty()) {
            throw RuntimeException("At least one of ipList/dynamicGroupIdList required")
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
            ipList = ipDTOList
        )

        try {
            val taskInstanceId = JobUtils.fastExecuteScript(fastExecuteScriptReq, this.esbApiHost)
            printLink(bizId, taskInstanceId, jobHost)
            val startTime = System.currentTimeMillis()
            checkStatus(
                bizId = bizId,
                startTime = startTime,
                taskId = taskId,
                taskInstanceId = taskInstanceId,
                operator = operator,
                buildId = buildId,
                result = result
            )
            printLink(bizId, taskInstanceId, jobHost)
        } catch (e: Exception) {
            logger.error("Job API invoke failed", e)
            result.status = Status.failure
            result.message = e.message
            if (e.message != null && e.message!!.contains("permission")) {
                logger.info("====================================================")
                logger.info("看这里！(Attention Please!)")
                logger.info("看这里！(Attention Please!)")
                logger.info("看这里！(Attention Please!)")
                logger.info("====================================================")
                logger.info("Job插件使用流水线最后一次保存人的身份调用作业平台接口，请使用有权限的用户身份保存流水线，权限可到蓝鲸权限中心申请(Job plugin invoke Job API with last modifier of this pipeline, please ensure the last modifier has perssion to access the business on Job, user can apply authorization using Blueking Authorization Center, which is on Blueking Desktop)")
            }
        }
    }

    private fun checkStatus(
        bizId: String,
        startTime: Long,
        taskInstanceId: Long,
        operator: String,
        buildId: String,
        taskId: String,
        result: AtomResult
    ) {

        var needContinue = true

        while (needContinue) {
            Thread.sleep(2000)
            val taskResult = JobUtils.getTaskResult(appId, appSecret, bizId, taskInstanceId, operator, esbApiHost)
            printLink(bizId, taskInstanceId, jobHost)
            if (taskResult.isFinish) {
                needContinue = false
                if (taskResult.success) {
                    logger.info("[$buildId]|SUCCEED|taskInstanceId=$taskId|${taskResult.msg}")
                } else {
                    logger.info("[$buildId]|FAIL|taskInstanceId=$taskId|${taskResult.msg}")
                    logger.info("脚本本身执行失败(Script failure)：${taskResult.msg}")
                    result.status = Status.failure
                    result.message =
                        "脚本执行失败，请根据使用的蓝鲸版本点击插件日志中对应的链接前往作业平台查看脚本失败详情，作业平台以脚本最后一条语句的返回码为0来判断脚本为执行成功状态，请检查脚本本身业务逻辑，若为脚本执行超时失败，请检查脚本自身逻辑有无死循环或耗时操作，若正常逻辑耗时较长请调整插件的超时参数（默认1000s）(Script fail, please click the link and go to Job to check the detail of script failure, Job judges that the script is executed successfully by the return code of the last line of the script as 0. Please check the content of script. If script fail because of timeout, please check whether there is a dead loop or long-time operation in script content. If the normal business logic requires long time, please adjust the timeout parameter of this plugin.)"
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

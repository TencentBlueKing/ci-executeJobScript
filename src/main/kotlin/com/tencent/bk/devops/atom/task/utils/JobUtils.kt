package com.tencent.bk.devops.atom.task.utils

import com.tencent.bk.devops.atom.task.pojo.FastExecuteScriptRequest
import com.tencent.bk.devops.atom.task.service.JobResourceApi

object JobUtils {

    private val jobResourceApi = JobResourceApi()

    fun fastExecuteScript(executeScriptRequest: FastExecuteScriptRequest, jobHost: String): Long {
        return jobResourceApi.fastExecuteScript(executeScriptRequest, jobHost)
    }

    fun getTaskResult(
        appId: String,
        appSecret: String,
        bizId: String,
        taskInstanceId: Long,
        operator: String,
        esbHost: String
    ): JobResourceApi.TaskResult {
        return jobResourceApi.getTaskResult(appId, appSecret, bizId, taskInstanceId, operator, esbHost)
    }

    fun getV2DetailUrl(appId: String, taskInstanceId: Long, jobHost: String): String {
        return "JobV2: <a target='_blank' href='$jobHost/?taskInstanceList&appId=$appId#taskInstanceId=$taskInstanceId'>到作业平台V2查看详情(Go to JobV2 for Detail, click this if using BlueKing5.x)</a>";
    }

    fun getV3DetailUrl(appId: String, taskInstanceId: Long, jobHost: String): String {
        return "JobV3: <a target='_blank' href='$jobHost/api_execute/$taskInstanceId'>到作业平台V3查看详情(Go to JobV3 for Detail, click this if using BlueKing6.x)</a>"
    }
}

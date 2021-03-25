
package com.tencent.bk.devops.atom.task.pojo

import com.tencent.bk.devops.atom.pojo.AtomBaseParam
import lombok.Data
import lombok.EqualsAndHashCode

@Data
@EqualsAndHashCode(callSuper = true)
class InnerJobParam : AtomBaseParam() {
    val bizId: String = ""
    val scriptType: String = ""
    // shell脚本内容
    var shellScriptContent: String = ""
    // bat脚本内容
    var batScriptContent: String = ""
    // perl脚本内容
    var perlScriptContent: String = ""
    // python脚本内容
    var pythonScriptContent: String = ""
    // powershell脚本内容
    var powershellScriptContent: String = ""
    val scriptParam: String = ""
    val timeout: Int? = 1000
    val account: String = ""
    val targetIpList: String = ""
    val dynamicGroupIdList: String = ""
}

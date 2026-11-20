package me.weishu.kernelsu.ui.webui

import java.io.File

/**
 * 管理器内置 WebUI（不依赖任何已安装模块）。
 *
 * FinaSU 原生的 WebViewHelper 只认 /data/adb/modules/<id>/webroot；
 * 这里登记一个 app 私有目录，prepareWebView 会优先用它代替模块目录。
 */
object BuiltinWebUI {
    /** 伪模块 id，仅用于 Intent 传参 */
    const val MODULE_ID = "kpm-builtin"

    @Volatile
    private var rootDir: File? = null

    @Volatile
    var displayName: String = "KPM 管理界面"
        private set

    /** 登记/更新内置界面根目录（其下需有 webroot/） */
    fun register(root: File, name: String = "KPM 管理界面") {
        rootDir = root
        displayName = name
    }

    /** 若 id 命中内置界面，返回其根目录 */
    fun rootFor(moduleId: String): File? =
        if (moduleId == MODULE_ID) rootDir?.takeIf { it.isDirectory } else null
}

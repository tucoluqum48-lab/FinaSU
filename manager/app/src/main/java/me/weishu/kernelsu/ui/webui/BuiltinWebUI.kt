package me.weishu.kernelsu.ui.webui

import java.io.File

/**
 * 管理器内置 WebUI（不依赖任何已安装模块）。
 *
 * FinaSU 原生的 WebViewHelper 只认 /data/adb/modules/<id>/webroot；
 * 这里登记 app 私有目录，prepareWebView 会优先用它代替模块目录。
 * 支持多个内置界面（KPM / Zygisk Next）。
 */
object BuiltinWebUI {
    /** KPM 界面 id */
    const val KPM_ID = "kpm-builtin"

    /** Zygisk Next 界面 id */
    const val ZNEXT_ID = "znext"

    private val roots = HashMap<String, File>()
    private val names = HashMap<String, String>()

    /** 登记/更新内置界面根目录（其下需有 webroot/） */
    @Synchronized
    fun register(id: String, root: File, name: String) {
        roots[id] = root
        names[id] = name
    }

    /** 若 id 命中内置界面，返回其根目录 */
    @Synchronized
    fun rootFor(moduleId: String): File? = roots[moduleId]?.takeIf { it.isDirectory }

    /** 内置界面的显示名 */
    @Synchronized
    fun nameFor(moduleId: String): String? = names[moduleId]
}

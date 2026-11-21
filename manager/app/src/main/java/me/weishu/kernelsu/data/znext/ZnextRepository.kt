package me.weishu.kernelsu.data.znext

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.webui.BuiltinWebUI

/**
 * 内置 Zygisk Next。
 *
 * 与 KPM 同一思路：全部资源随 APK 分发，缺失/版本变化时经 root shell 补回。
 *
 * 与官方模块的差异：
 *   · 不部署到 /data/adb/modules/zygisksu（模块列表里不会出现）
 *   · 二进制与资源放 /data/adb/znext
 *   · 用 /data/adb/post-fs-data.d 与 /data/adb/service.d 的脚本替代模块的两个阶段
 *   · sepolicy 规则在 post-fs-data 里用 ksud sepolicy patch 逐条应用
 *   · 界面放 app 私有目录，用 BuiltinWebUI 打开
 */
class ZnextRepository(private val context: Context) {

    companion object {
        const val TOOL_DIR = "/data/adb/znext"
        const val POST_FS_SCRIPT = "/data/adb/post-fs-data.d/10-znext.sh"
        const val SERVICE_SCRIPT = "/data/adb/service.d/10-znext.sh"
        const val STATE_DIR = "/data/adb/zygisksu"
        const val WEBUI_DIR = "znextwebui"
        private const val ASSET_DIR = "znext"
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** 全量部署（版本不同或缺失时） */
    suspend fun ensureInstalled(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val stage = File(context.cacheDir, "znext-assets")
            if (stage.exists()) stage.deleteRecursively()
            stage.mkdirs()
            copyAssetDir(ASSET_DIR, stage)

            val bundledVer = readProp(File(stage, "module.prop"), "versionCode")
            val installedVer = readProp(File("$TOOL_DIR/module.prop"), "versionCode")
            val hasBinary = File("$TOOL_DIR/bin/zygiskd64").isFile
            val needDeploy = !hasBinary || bundledVer != installedVer

            if (needDeploy) {
                Shell.cmd(
                    "rm -rf " + q(TOOL_DIR) + " && mkdir -p " + q(TOOL_DIR) +
                        " && cp -af " + q(stage.absolutePath) + "/. " + q(TOOL_DIR) + "/" +
                        " && rm -rf " + q("$TOOL_DIR/webroot") + " " + q("$TOOL_DIR/boot") +
                        " " + q("$TOOL_DIR/module.prop.orig") + " " + q("$TOOL_DIR/cleanup.sh") +
                        " " + q("$TOOL_DIR/uninstall.sh") + " " + q("$TOOL_DIR/emulated-soft-reboot.sh") +
                        " " + q("$TOOL_DIR/post-fs-data.sh") + " " + q("$TOOL_DIR/service.sh") +
                        " " + q("$TOOL_DIR/machikado") + " " + q("$TOOL_DIR/mazoku") +
                        " && chmod -R 755 " + q(TOOL_DIR) +
                        " && ln -sf zygiskd64 " + q("$TOOL_DIR/bin/zygiskd") +
                        " && mkdir -p " + q(STATE_DIR),
                ).exec()
            }

            // 开机脚本（每次都按模板重写，保证路径正确）
            Shell.cmd(
                "mkdir -p /data/adb/post-fs-data.d /data/adb/service.d" +
                    " && cp -f " + q("${stage.absolutePath}/boot/post-fs-data.sh") + " " + q(POST_FS_SCRIPT) +
                    " && cp -f " + q("${stage.absolutePath}/boot/service.sh") + " " + q(SERVICE_SCRIPT) +
                    " && chmod 755 " + q(POST_FS_SCRIPT) + " " + q(SERVICE_SCRIPT),
            ).exec()

            // 当前这次也立刻应用策略（开机由脚本负责）
            applySepolicy(File(stage, "sepolicy.rule"))

            // 与内置 FolkZygisk 互斥：关掉它
            Shell.cmd(
                "mkdir -p /data/adb/ksu && echo '{\"enabled\":false}' > /data/adb/ksu/zygisk.json",
            ).exec()
            Shell.cmd("/data/adb/ksud zygisk disable").exec()

            stage.deleteRecursively()
            File("$TOOL_DIR/bin/zygiskd64").isFile
        }.getOrDefault(false)
    }

    /** 释放 WebUI 并登记给 WebViewHelper */
    suspend fun ensureWebUi(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val root = File(context.filesDir, WEBUI_DIR)
            val stage = File(context.cacheDir, "znext-webui-assets")
            if (stage.exists()) stage.deleteRecursively()
            stage.mkdirs()
            copyAssetDir("$ASSET_DIR/webroot", stage)

            val webroot = File(root, "webroot")
            if (webroot.exists()) webroot.deleteRecursively()
            webroot.mkdirs()
            stage.copyRecursively(webroot, overwrite = true)
            stage.deleteRecursively()

            BuiltinWebUI.register(BuiltinWebUI.ZNEXT_ID, root, "Zygisk Next")
            File(webroot, "index.html").isFile
        }.getOrDefault(false)
    }

    private fun applySepolicy(ruleFile: File) {
        if (!ruleFile.isFile) return
        ruleFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                Shell.cmd("/data/adb/ksud sepolicy patch " + q(line)).exec()
            }
    }

    private fun readProp(file: File, key: String): String? {
        if (!file.isFile) return null
        return file.readLines()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val list = context.assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                dest.parentFile?.mkdirs()
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (name in list) {
            val child = File(dest, name)
            val childAsset = "$assetPath/$name"
            val grand = context.assets.list(childAsset)
            if (grand.isNullOrEmpty()) {
                context.assets.open(childAsset).use { input ->
                    child.parentFile?.mkdirs()
                    child.outputStream().use { input.copyTo(it) }
                }
            } else {
                copyAssetDir(childAsset, child)
            }
        }
    }
}

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
 * 结论性的部署形态（实测得出）：
 *   · 守护进程 zygiskd 硬性要求「官方模块布局」——它从 /data/adb/modules/zygisksu
 *     读 module.prop、校验自身文件、回写运行状态；把它挪到别处会报
 *     "Module files corrupted" 或 "Last injection failed"。
 *   · 因此这里按官方布局部署到 /data/adb/modules/zygisksu，
 *     由 FinaSU 的模块机制负责 post-fs-data / service / sepolicy 三个阶段；
 *   · 为了"模块列表里看不到"，模块列表在 ModuleRepositoryImpl 里过滤掉 zygisksu；
 *   · 额外建 /data/adb/znext -> /data/adb/modules/zygisksu 的软链，
 *     让内置 WebUI（页面里的命令走 /data/adb/znext 路径）也能工作；
 *   · 界面本身仍释放到 app 私有目录，用 BuiltinWebUI 打开。
 */
class ZnextRepository(private val context: Context) {

    companion object {
        const val MODULE_DIR = "/data/adb/modules/zygisksu"
        const val TOOL_DIR = "/data/adb/znext"
        const val WEBUI_DIR = "znextwebui"
        private const val ASSET_DIR = "znext"
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** 全量部署（缺失或版本变化时） */
    suspend fun ensureInstalled(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val stage = File(context.cacheDir, "znext-assets")
            if (stage.exists()) stage.deleteRecursively()
            stage.mkdirs()
            copyAssetDir(ASSET_DIR, stage)

            val bundledVer = readProp(File(stage, "module.prop"), "versionCode")
            val installedVer = readProp(File("$MODULE_DIR/module.prop"), "versionCode")
            val hasBinary = File("$MODULE_DIR/bin/zygiskd64").isFile
            val needDeploy = !hasBinary || bundledVer != installedVer

            if (needDeploy) {
                Shell.cmd(
                    "rm -rf " + q(MODULE_DIR) + " && mkdir -p " + q(MODULE_DIR) +
                        " && cp -af " + q(stage.absolutePath) + "/. " + q(MODULE_DIR) + "/" +
                        " && rm -rf " + q("$MODULE_DIR/webroot") + " " + q("$MODULE_DIR/boot") +
                        " && chmod -R 755 " + q(MODULE_DIR) +
                        " && ln -sf zygiskd64 " + q("$MODULE_DIR/bin/zygiskd"),
                ).exec()
            }

            // /data/adb/znext 作为同一份内容的别名（内置界面里的命令按此路径执行）
            Shell.cmd(
                "rm -rf " + q(TOOL_DIR) + " && ln -sfn " + q(MODULE_DIR) + " " + q(TOOL_DIR) +
                    " && ls -ld " + q(TOOL_DIR),
            ).exec()

            // 清掉早期版本留下的自建启动脚本（现在由模块机制接管）
            Shell.cmd(
                "rm -f /data/adb/post-fs-data.d/10-znext.sh /data/adb/service.d/10-znext.sh",
            ).exec()

            // 与内置 FolkZygisk 互斥
            Shell.cmd(
                "mkdir -p /data/adb/ksu && echo '{\"enabled\":false}' > /data/adb/ksu/zygisk.json",
            ).exec()
            Shell.cmd("/data/adb/ksud zygisk disable").exec()

            stage.deleteRecursively()
            File("$MODULE_DIR/bin/zygiskd64").isFile
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

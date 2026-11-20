package me.weishu.kernelsu.data.kpm

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置 KPM 工具链与脚本的"缺了就补"逻辑（与 BakaSU 管理器同思路）。
 *
 * 与 BakaSU 的差别：WebUI 不走 app 私有目录，而是落到
 * /data/adb/modules/kpm_bridge/webroot，这样 FinaSU 管理器自带的 WebUI
 * 框架（按 /data/adb/modules/<id>/webroot 解析）可以直接打开它。
 */
object KpmBridge {

    const val TOOL_DIR = "/data/adb/kpm-tool"
    const val BIN_DIR = "$TOOL_DIR/bin"
    const val KPM_DIR = "$TOOL_DIR/kpm"
    const val LOG = "$TOOL_DIR/selfheal.log"

    const val MODULE_ID = "kpm_bridge"
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"

    /** 随 APK 打包的工具（来自 assets/kpmbin 目录） */
    val BUNDLED_BINS = listOf("kpatch", "kpimg", "kptools", "magiskboot")

    /** 随 APK 打包的脚本及其落点（来自 assets/kpmscripts 目录） */
    val BUNDLED_SCRIPTS = listOf(
        "00-volkey-rescue.sh" to "/data/adb/post-fs-data.d/00-volkey-rescue.sh",
        "95-uid-refresh.sh" to "/data/adb/service.d/95-uid-refresh.sh",
        "96-hide-ko.sh" to "/data/adb/service.d/96-hide-ko.sh",
        "97-color-saturation.sh" to "/data/adb/service.d/97-color-saturation.sh",
        "99-uidhide.sh" to "/data/adb/boot-completed.d/99-uidhide.sh",
        "status.sh" to "$TOOL_DIR/status.sh",
    )

    private const val MODULE_PROP = """id=kpm_bridge
name=KPM 管理界面（WebUI 桥）
version=v1.0
versionCode=1
author=dsh
description=为管理器提供本地 KPM 管理页面（内置 kpmwebui + kpmscripts）。纯本地，无网络。
"""

    private const val POST_FS_DATA = """#!/system/bin/sh
MODDIR=${'$'}{0%/*}
DST=/data/adb/kpm-tool
mkdir -p "${'$'}DST/bin" "${'$'}DST/scripts"
cp -af "${'$'}MODDIR/bin/." "${'$'}DST/bin/" 2>/dev/null
cp -af "${'$'}MODDIR/scripts/." "${'$'}DST/scripts/" 2>/dev/null
chmod -R 755 "${'$'}DST/bin" "${'$'}DST/scripts" 2>/dev/null
chmod 700 "${'$'}DST" 2>/dev/null
"""

    private const val SERVICE = """#!/system/bin/sh
DST=/data/adb/kpm-tool
LOG="${'$'}DST/selfheal.log"
KSUD=/data/adb/ksud
mkdir -p "${'$'}DST" 2>/dev/null
log(){ echo "[${'$'}(date '+%m-%d %H:%M:%S')] ${'$'}*" >> "${'$'}LOG"; }
[ -x "${'$'}KSUD" ] || { log "ksud 不存在"; exit 0; }
fix_feature() {
  n="${'$'}1"; id="${'$'}2"; want="${'$'}{3:-1}"
  cur=${'$'}("${'$'}KSUD" feature get "${'$'}n" 2>/dev/null | awk '/^Value:/{print ${'$'}2}')
  [ "${'$'}cur" = "${'$'}want" ] && return 0
  "${'$'}KSUD" feature set "${'$'}id" "${'$'}want" >/dev/null 2>&1
  new=${'$'}("${'$'}KSUD" feature get "${'$'}n" 2>/dev/null | awk '/^Value:/{print ${'$'}2}')
  [ "${'$'}new" = "${'$'}want" ] && log "feature ${'$'}n: ${'$'}cur -> ${'$'}new (已修复)"
}
fix_feature selinux_hide 4 1
fix_feature avc_spoof 6 1
fix_feature kernel_umount 1 1
fix_feature su_compat 0 1
"${'$'}KSUD" susfs apply >/dev/null 2>&1 && log "susfs apply: ok" || log "susfs apply: 跳过/失败"
[ -r /proc/oplus_mem_zone ] && log "node: ${'$'}(head -2 /proc/oplus_mem_zone | tail -1)"
log "==== 自愈完成 ===="
"""

    /** 首次安装/重装后补齐工具链、脚本与 kpm_bridge 模块；已存在则跳过 */
    suspend fun bootstrap(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cache = File(context.cacheDir, "kpmstage")
            if (cache.exists()) cache.deleteRecursively()
            cache.mkdirs()

            // 1) 工具 → cache/bin
            val binStage = File(cache, "bin").apply { mkdirs() }
            BUNDLED_BINS.forEach { name ->
                // 某个二进制没随包打进来时跳过，不要整块失败
                runCatching { copyAsset(context, "kpmbin/$name", File(binStage, name)) }
            }
            // 2) 脚本 → cache/scripts
            val scriptStage = File(cache, "scripts").apply { mkdirs() }
            BUNDLED_SCRIPTS.forEach { (name, _) ->
                copyAsset(context, "kpmscripts/$name", File(scriptStage, name))
            }
            // 3) WebUI → cache/webroot
            val webroot = File(cache, "webroot").apply { mkdirs() }
            copyAssetDir(context, "kpmwebui", webroot)

            val cmd = buildString {
                append("mkdir -p $BIN_DIR $KPM_DIR /data/adb/service.d /data/adb/post-fs-data.d /data/adb/boot-completed.d ")
                append("$MODULE_DIR/webroot $MODULE_DIR/bin $MODULE_DIR/scripts && ")
                append("cp -af ${q(binStage)}/. ${q("$BIN_DIR")}/ && ")
                append("cp -af ${q(binStage)}/. ${q("$MODULE_DIR/bin")}/ && ")
                append("cp -af ${q(scriptStage)}/. ${q("$MODULE_DIR/scripts")}/ && ")
                append("cp -af ${q(webroot)}/. ${q("$MODULE_DIR/webroot")}/ && ")
                BUNDLED_SCRIPTS.forEach { (name, dst) ->
                    append("cp -af ${q("$scriptStage/$name")} ${q(dst)} && ")
                }
                append("chmod 755 ${q("$MODULE_DIR/post-fs-data.sh")} ${q("$MODULE_DIR/service.sh")} 2>/dev/null; ")
                append("chmod -R 755 $BIN_DIR $MODULE_DIR/bin $MODULE_DIR/scripts && ")
                append("chmod -R 755 $MODULE_DIR/webroot && ")
                BUNDLED_SCRIPTS.forEach { (_, dst) -> append("chmod 755 ${q(dst)} && ") }
                append("chmod 700 $TOOL_DIR && chmod 644 ${q("$MODULE_DIR/module.prop")}")
            }
            // 先写 module.prop / 脚本（app 无权限写 /data/adb，用 root shell 的 heredoc）
            File(cache, "module.prop").writeText(MODULE_PROP)
            File(cache, "post-fs-data.sh").writeText(POST_FS_DATA)
            File(cache, "service.sh").writeText(SERVICE)

            val head = "cp -af ${q(File(cache, "module.prop").absolutePath)} ${q("$MODULE_DIR/module.prop")} && " +
                "cp -af ${q(File(cache, "post-fs-data.sh").absolutePath)} ${q("$MODULE_DIR/post-fs-data.sh")} && " +
                "cp -af ${q(File(cache, "service.sh").absolutePath)} ${q("$MODULE_DIR/service.sh")} && "
            Shell.cmd(head + cmd).exec().isSuccess
        }.getOrDefault(false)
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun q(f: File) = q(f.absolutePath)

    private fun copyAsset(context: Context, asset: String, out: File) {
        out.parentFile?.mkdirs()
        context.assets.open(asset).use { input -> out.outputStream().use { input.copyTo(it) } }
        out.setExecutable(true, false)
    }

    private fun copyAssetDir(context: Context, assetDir: String, outDir: File) {
        val children = context.assets.list(assetDir).orEmpty()
        if (children.isEmpty()) {
            copyAsset(context, assetDir, outDir)
            return
        }
        outDir.mkdirs()
        children.forEach { child ->
            val childAsset = "$assetDir/$child"
            val childOut = File(outDir, child)
            if (context.assets.list(childAsset).orEmpty().isEmpty()) {
                copyAsset(context, childAsset, childOut)
            } else {
                copyAssetDir(context, childAsset, childOut)
            }
        }
    }
}

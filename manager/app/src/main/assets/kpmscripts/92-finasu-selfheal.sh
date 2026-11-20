#!/system/bin/sh
# FinaSU 特性兜底（放在 boot-completed 阶段，最后执行）
# 负责的是"内核特性位"，不是 SUSFS 规则（后者由管理器「开机自动应用」负责）

KSUD=/data/adb/ksud
LOG=/data/adb/kpm-tool/selfheal.log
mkdir -p /data/adb/kpm-tool 2>/dev/null
log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG"; }

[ -x "$KSUD" ] || { log "ksud 不存在，跳过"; exit 0; }

fix_feature() {
  n="$1"; id="$2"; want="${3:-1}"
  cur=$("$KSUD" feature get "$n" 2>/dev/null | awk '/^Value:/{print $2}')
  [ "$cur" = "$want" ] && return 0
  "$KSUD" feature set "$id" "$want" >/dev/null 2>&1
  new=$("$KSUD" feature get "$n" 2>/dev/null | awk '/^Value:/{print $2}')
  [ "$new" = "$want" ] && log "feature $n: $cur -> $new (已修复)"
}

fix_feature selinux_hide 4 1
fix_feature avc_spoof 6 1
fix_feature kernel_umount 1 1
fix_feature su_compat 0 1

"$KSUD" susfs apply >/dev/null 2>&1 && log "susfs apply: ok" || log "susfs apply: 跳过/失败"

[ -r /proc/oplus_mem_zone ] && log "node: $(head -2 /proc/oplus_mem_zone | tail -1)"
log "==== 自愈完成 ===="

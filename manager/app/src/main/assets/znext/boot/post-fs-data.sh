#!/system/bin/sh
# 内置 Zygisk Next：post-fs-data 阶段（替代官方模块脚本）
# 资源在 /data/adb/znext，不占用 /data/adb/modules
Z=/data/adb/znext
[ -x "$Z/bin/zygiskd64" ] || exit 0

# 每轮启动都要重新应用 SELinux 策略（策略不会跨启动保留）
if [ -f "$Z/sepolicy.rule" ]; then
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    case "$line" in \#*) continue ;; esac
    /data/adb/ksud sepolicy patch "$line" >/dev/null 2>&1
  done < "$Z/sepolicy.rule"
fi

mkdir -p /data/adb/zygisksu
[ -f /data/adb/zygisksu/znctx ] && mv -f /data/adb/zygisksu/znctx /data/adb/zygisksu/znctx.old
[ -f /data/adb/zygisksu/modules_info ] && mv -f /data/adb/zygisksu/modules_info /data/adb/zygisksu/modules_info.old

if [ -d /data/adb/ksu/log ]; then
  [ -f /data/adb/ksu/log/znctx ] && mv -f /data/adb/ksu/log/znctx /data/adb/ksu/log/znctx.old
  [ -f /data/adb/ksu/log/modules_info ] && mv -f /data/adb/ksu/log/modules_info /data/adb/ksu/log/modules_info.old
fi

[ -f /data/adb/zygisksu/klog ] && [ "1" = "$(cat /data/adb/zygisksu/klog)" ] && export KLOG_ENABLED=1

cd "$Z" || exit 0
./bin/zygiskd daemon

if [ -d /data/adb/ksu/log ]; then
  cp -f /data/adb/zygisksu/znctx /data/adb/ksu/log/znctx 2>/dev/null
  cp -f /data/adb/zygisksu/modules_info /data/adb/ksu/log/modules_info 2>/dev/null
fi
exit 0

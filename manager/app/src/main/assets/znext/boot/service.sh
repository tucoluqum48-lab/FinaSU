#!/system/bin/sh
# 内置 Zygisk Next：service 阶段（替代官方模块脚本）
Z=/data/adb/znext
[ -x "$Z/bin/zygiskd64" ] || exit 0
# 官方脚本在模块被禁用时跳过；这里用状态文件控制
if [ -f /data/adb/zygisksu/disabled ]; then exit 0; fi
"$Z/bin/zygiskd" service-stage
exit 0

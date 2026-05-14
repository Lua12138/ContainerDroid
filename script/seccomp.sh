#!/bin/bash

set -e

DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp

cat > /tmp/seccomp.sh << 'EOF'
  TR=/sys/kernel/tracing
  echo 0 > $TR/tracing_on
  echo > $TR/trace
  echo > $TR/set_event_pid
  echo 1 > $TR/events/raw_syscalls/sys_enter/enable
  echo 1 > $TR/events/raw_syscalls/sys_exit/enable
  echo 16384 > $TR/buffer_size_kb
  am force-stop top.niunaijun.blackboxa32
  logcat -c
  timeout 8s logcat > /data/local/tmp/logcat.log &
  echo 1 > $TR/tracing_on
  am start -n top.niunaijun.blackboxa32/top.niunaijun.blackboxa.view.main.WelcomeActivity --ez FLAG_TEST true --es TEST_PACKAGE com.bestv.tv.video.iqy.tjdx
  sleep 8
  echo 0 > $TR/tracing_on
  am force-stop top.niunaijun.blackboxa32
  cat $TR/trace
EOF
adb -s $DEVICE push /tmp/seccomp.sh /data/local/tmp/seccomp.sh
adb -s $DEVICE shell chmod +x /data/local/tmp/seccomp.sh
adb -s $DEVICE shell su -c /data/local/tmp/seccomp.sh > /tmp/seccomp.log
adb -s $DEVICE pull /data/local/tmp/logcat.log /tmp/logcat

#   3. 更现代的替代方案：使用 Perfetto (推荐)

#   如果你觉得直接看文本日志太痛苦，Android 9.0+ 推荐使用官方的 Perfetto，底层原理一样是基于 ftrace，但提供了极好的图形化界面。

#   直接使用一行命令即可抓取：

#    1 adb shell perfetto -o /data/misc/perfetto-traces/trace_file.perfetto-trace -t 10s sched freq idle am wm gfx view binder_driver
#    2 adb pull /data/misc/perfetto-traces/trace_file.perfetto-trace ./
#   抓取后，将文件拖入 https://ui.perfetto.dev/ (https://ui.perfetto.dev/) 即可在浏览器中查看带有时间轴和进程过滤的 Binder 交互图。

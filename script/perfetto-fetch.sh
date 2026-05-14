#!/bin/bash

set -e

CUR=$(cd "$(dirname "$0")"; pwd)

DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp
TR=/data/misc/perfetto-traces/trace_file.perfetto-trace

cat > /tmp/binderlog.sh << 'EOF'
#!/system/bin/sh

SRC="/dev/binderfs/binder_logs/transaction_log"
OUTDIR="/data/local/tmp/binder_capture"

INTERVAL=0.1

rm -rf "$OUTDIR"
mkdir -p "$OUTDIR"

INDEX=1

while true
do
    FILE=$(printf "%s/%04d.log" "$OUTDIR" "$INDEX")

    cat "$SRC" > "$FILE"

    INDEX=$((INDEX + 1))

    # sleep "$INTERVAL"
done
EOF

cat > /tmp/perfetto.sh << EOF
  am force-stop top.niunaijun.blackboxa32
  logcat -c
  /data/local/tmp/android-arm/perfetto --background -o $TR --txt --config /data/local/tmp/perfetto.config
  timeout 10 logcat > /sdcard/logcat &
  timeout 10 sh /data/local/tmp/binderlog.sh &
  am start -n top.niunaijun.blackboxa32/top.niunaijun.blackboxa.view.main.WelcomeActivity --ez FLAG_TEST true --es TEST_PACKAGE com.bestv.tv.video.iqy.tjdx
  sleep 11
  am force-stop top.niunaijun.blackboxa32
  cp -f $TR /sdcard/trace_file.perfetto-trace
  cat /data/local/tmp/perfetto.config
  ls -lah /data/misc/perfetto-traces/
EOF
# 配置文件里不建议书写通配符，建议用全称
# 通过命令可以列出所有支持的事件
# cat /sys/kernel/tracing/available_events \
#   | grep "^binder:" \
#   | sed 's#^\([^:]*\):\(.*\)$#ftrace_events: "\1/\2"#'

cat > /tmp/perfetto.config << EOF
buffers {
     size_kb: 262144
     fill_policy: RING_BUFFER
}
data_sources {
  config {
    name: "linux.sys_stats"
    sys_stats_config {
      stat_period_ms: 1000
      stat_counters: STAT_CPU_TIMES
      stat_counters: STAT_FORK_COUNT
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    process_stats_config {
      scan_all_processes_on_start: true
      proc_stats_poll_ms: 1000
    }
  }
}
data_sources {
    config {
        name: "linux.ftrace"
        ftrace_config {
            ftrace_events: "binder/binder_ioctl"
            ftrace_events: "binder/binder_lock"
            ftrace_events: "binder/binder_locked"
            ftrace_events: "binder/binder_unlock"
            ftrace_events: "binder/binder_ioctl_done"
            ftrace_events: "binder/binder_write_done"
            ftrace_events: "binder/binder_read_done"
            ftrace_events: "binder/binder_set_priority"
            ftrace_events: "binder/binder_wait_for_work"
            ftrace_events: "binder/binder_transaction"
            ftrace_events: "binder/binder_transaction_received"
            ftrace_events: "binder/binder_transaction_node_to_ref"
            ftrace_events: "binder/binder_transaction_ref_to_node"
            ftrace_events: "binder/binder_transaction_ref_to_ref"
            ftrace_events: "binder/binder_transaction_fd"
            ftrace_events: "binder/binder_transaction_alloc_buf"
            ftrace_events: "binder/binder_transaction_buffer_release"
            ftrace_events: "binder/binder_transaction_failed_buffer_release"
            ftrace_events: "binder/binder_update_page_range"
            ftrace_events: "binder/binder_alloc_lru_start"
            ftrace_events: "binder/binder_alloc_lru_end"
            ftrace_events: "binder/binder_free_lru_start"
            ftrace_events: "binder/binder_free_lru_end"
            ftrace_events: "binder/binder_alloc_page_start"
            ftrace_events: "binder/binder_alloc_page_end"
            ftrace_events: "binder/binder_unmap_user_start"
            ftrace_events: "binder/binder_unmap_user_end"
            ftrace_events: "binder/binder_unmap_kernel_start"
            ftrace_events: "binder/binder_unmap_kernel_end"
            ftrace_events: "binder/binder_command"
            ftrace_events: "binder/binder_return"
            ftrace_events: "raw_syscalls/sys_enter"
            ftrace_events: "raw_syscalls/sys_exit"
            ftrace_events: "sched/sched_process_fork"
            ftrace_events: "sched/sched_process_exec"
            ftrace_events: "sched/sched_process_exit"
            buffer_size_kb: 32768
            drain_period_ms: 10
        }
    }
}
duration_ms: 10000
EOF
adb -s $DEVICE push /tmp/perfetto.sh /data/local/tmp/perfetto.sh
adb -s $DEVICE push /tmp/perfetto.config /data/local/tmp/perfetto.config
adb -s $DEVICE push /tmp/binderlog.sh /data/local/tmp/binderlog.sh
adb -s $DEVICE shell chmod +x /data/local/tmp/perfetto.sh
adb -s $DEVICE shell su -c /data/local/tmp/perfetto.sh
adb -s $DEVICE pull /sdcard/trace_file.perfetto-trace $CUR/log/trace_file.perfetto-trace
adb -s $DEVICE pull /sdcard/logcat /tmp/logcat
adb -s $DEVICE pull /data/local/tmp/binder_capture $CUR/log/

#   3. 更现代的替代方案：使用 Perfetto (推荐)

#   如果你觉得直接看文本日志太痛苦，Android 9.0+ 推荐使用官方的 Perfetto，底层原理一样是基于 ftrace，但提供了极好的图形化界面。

#   直接使用一行命令即可抓取：

# adb shell perfetto -o /data/misc/perfetto-traces/trace_file.perfetto-trace -t 10s sched freq idle am wm gfx view binder_driver
# adb pull /data/misc/perfetto-traces/trace_file.perfetto-trace ./
#   抓取后，将文件拖入 https://ui.perfetto.dev/ (https://ui.perfetto.dev/) 即可在浏览器中查看带有时间轴和进程过滤的 Binder 交互图。

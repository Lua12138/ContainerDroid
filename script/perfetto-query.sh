#!/bin/bash

set -e

# 如果设备上不包含此工具 可自行下载Android版本
# https://github.com/google/perfetto/releases
CUR=$(cd "$(dirname "$0")"; pwd)

FILE=$1
PID=$2

TRACE_SHELL=$CUR/trace_processor_shell

export PATH=$TRACE_SHELL:$PATH

# Binder
echo "WITH binder_tx AS (
  SELECT
    f.id,
    f.ts,
    p.name AS caller_process,
    p.pid AS caller_pid,
    t.tid AS caller_tid,
    t.name AS caller_thread,

    MAX(CASE WHEN a.flat_key IN ('debug_id', 'transaction') THEN a.int_value END) AS debug_id,

    MAX(CASE WHEN a.flat_key IN ('dest_proc', 'to_proc') THEN a.int_value END) AS target_pid,
    MAX(CASE WHEN a.flat_key IN ('dest_thread', 'to_thread') THEN a.int_value END) AS target_tid,
    MAX(CASE WHEN a.flat_key IN ('dest_node', 'target_node') THEN a.int_value END) AS target_node,

    MAX(CASE WHEN a.flat_key = 'code' THEN a.int_value END) AS transaction_code,
    MAX(CASE WHEN a.flat_key = 'flags' THEN a.int_value END) AS flags,
    MAX(CASE WHEN a.flat_key = 'reply' THEN a.int_value END) AS reply
  FROM ftrace_event f
  JOIN thread t USING (utid)
  JOIN process p USING (upid)
  JOIN args a ON a.arg_set_id = f.arg_set_id
  WHERE f.name = 'binder_transaction'
    AND p.pid = $PID
  GROUP BY
    f.id, f.ts, p.name, p.pid, t.tid, t.name
)
SELECT
  tx.ts,
  tx.caller_process,
  tx.caller_pid,
  tx.caller_thread,
  tx.caller_tid,

  dp.name AS target_process,
  tx.target_pid,
  tx.target_tid,
  tx.target_node,

  tx.transaction_code,
  printf('0x%x', tx.transaction_code) AS transaction_code_hex,
  tx.flags,
  printf('0x%x', tx.flags) AS flags_hex,
  tx.reply,
  tx.debug_id
FROM binder_tx tx
LEFT JOIN process dp
  ON dp.pid = tx.target_pid
ORDER BY tx.ts;
" | trace_processor_shell query $FILE
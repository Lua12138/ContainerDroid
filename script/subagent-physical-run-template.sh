#!/bin/bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
PKG_FILE="$ROOT_DIR/docs/test_package_name"
DEFAULT_DEVICE_VALUE="adb-c253b76f-pgzbCA._adb-tls-connect._tcp"

if [ ! -f "$PKG_FILE" ]; then
  echo "missing package file: $PKG_FILE" >&2
  exit 1
fi

TEST_PACKAGE=$(tr -d '\r' < "$PKG_FILE" | sed -n '1p' | xargs)
if [ -z "$TEST_PACKAGE" ]; then
  echo "empty package in $PKG_FILE" >&2
  exit 1
fi

cat <<EOF
Sub-agent execution template (physical, non-sandbox):

You must run in a physical environment (not sandbox).
Only perform build/install/start collection execution and report success/failure.
Do not analyze logs in this step.

Workspace: $ROOT_DIR
Device selection:
- If multiple devices are available, choose any reachable one.
- Optional override before running commands:
  export DEVICE="<your-device-serial>"
- Default DEVICE value used by scripts when not overridden:
  $DEFAULT_DEVICE_VALUE
Package from docs/test_package_name: $TEST_PACKAGE

Run exactly:
1) cd "$ROOT_DIR"
2) source "\$HOME/.sdkman/bin/sdkman-init.sh"
3) sdk use java 11.0.14.1-jbr
4) ./script/codex.sh plan-sync-check
5) ./script/codex.sh preflight-check
6) ./script/snapshot-acceptance-state.sh
7) ./script/codex.sh collect-and-check
8) ./script/snapshot-acceptance-state.sh

Return format:
- execution_status=success|failed
- plan_sync_status=<value from command output if present>
- preflight_status=<value from command output if present>
- collect_and_check_status=<value from command output if present>
- manifest_status=<value from /tmp/blackbox_artifacts_manifest.txt if present>
- failed_stage=<value from /tmp/blackbox_artifacts_manifest.txt if present>
- notes=<one line>
EOF

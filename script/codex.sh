#!/bin/bash

set -euo pipefail

CUR=$(cd "$(dirname "$0")" && pwd)
SOURCE_CODE=$(cd "$CUR/.." && pwd)

DEFAULT_DEVICE=${DEFAULT_DEVICE:-adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp}
DEVICE=${DEVICE:-}
ADB_HOST=${ADB_HOST:-}
ADB_PORT=${ADB_PORT:-}
APP_ID=${APP_ID:-top.niunaijun.blackboxa32}
COMPONENT="$APP_ID/top.niunaijun.blackboxa.view.main.WelcomeActivity"
WAIT_SECONDS=${WAIT_SECONDS:-30}
LOG_FILE=${LOG_FILE:-/tmp/blackbox_logcat.txt}
SCREENSHOT_FILE=${SCREENSHOT_FILE:-/tmp/blackbox_screenshot.png}
REAL_SCREENSHOT_FILE=${REAL_SCREENSHOT_FILE:-/tmp/blackbox_real_screenshot.png}
EXIT_INFO_FILE=${EXIT_INFO_FILE:-/tmp/blackbox_exit_info.txt}
DEVICE_LOG_FILE=${DEVICE_LOG_FILE:-/data/local/tmp/blackbox_logcat.txt}
REAL_LOG_FILE=${REAL_LOG_FILE:-/tmp/blackbox_real_logcat.txt}
REAL_EXIT_INFO_FILE=${REAL_EXIT_INFO_FILE:-/tmp/blackbox_real_exit_info.txt}
REAL_DEVICE_LOG_FILE=${REAL_DEVICE_LOG_FILE:-/data/local/tmp/blackbox_real_logcat.txt}
GETPROP_FILE=${GETPROP_FILE:-/tmp/blackbox_getprop.txt}
REAL_GETPROP_FILE=${REAL_GETPROP_FILE:-/tmp/blackbox_real_getprop.txt}
ARTIFACT_MANIFEST_FILE=${ARTIFACT_MANIFEST_FILE:-/tmp/blackbox_artifacts_manifest.txt}
ARTIFACT_MAX_AGE_MINUTES=${ARTIFACT_MAX_AGE_MINUTES:-120}
LATEST_ACCEPTANCE_STATE_FILE=${LATEST_ACCEPTANCE_STATE_FILE:-$SOURCE_CODE/docs/v3/LATEST_ACCEPTANCE_STATE.md}
PLAN_FILE=${PLAN_FILE:-$SOURCE_CODE/docs/v3/PLAN_v3.md}
SCREENSHOT_IGNORE_TOP=${SCREENSHOT_IGNORE_TOP:-48}
SCREENSHOT_MAX_AVERAGE_ABS=${SCREENSHOT_MAX_AVERAGE_ABS:-2.0}
SCREENSHOT_MAX_HIGH_DELTA_PERCENT=${SCREENSHOT_MAX_HIGH_DELTA_PERCENT:-3.0}
SCREENSHOT_MAX_MAJOR_DELTA_PERCENT=${SCREENSHOT_MAX_MAJOR_DELTA_PERCENT:-0.5}

if [ "$DEVICE" = "adb-c253b76f-pgzbCA._adb-tls-connect._tc" ]; then
  echo "normalize DEVICE suffix: _tc -> _tcp" >&2
  DEVICE="adb-c253b76f-pgzbCA._adb-tls-connect._tcp"
fi

TEST_PACKAGE=${TEST_PACKAGE:-}
TEST_PACKAGE_FILE="$SOURCE_CODE/docs/test_package_name"
if [ -z "$TEST_PACKAGE" ]; then
  if [ ! -f "$TEST_PACKAGE_FILE" ]; then
    echo "missing test package file: docs/test_package_name" >&2
    exit 1
  fi

  TEST_PACKAGE=$(tr -d '\r' < "$TEST_PACKAGE_FILE" | sed -n '1p' | xargs)
fi
if [ -z "$TEST_PACKAGE" ]; then
  echo "empty test package in $TEST_PACKAGE_FILE" >&2
  exit 1
fi

DEVICE_LOGCAT_PID=""

adb_cmd() {
  select_device
  if [ -n "$ADB_HOST" ] && [ -n "$ADB_PORT" ]; then
    adb -H "$ADB_HOST" -P "$ADB_PORT" -s "$DEVICE" "$@"
  else
    adb -s "$DEVICE" "$@"
  fi
}

select_device() {
  if [ -n "$DEVICE" ]; then
    return
  fi

  if adb -s "$DEFAULT_DEVICE" get-state >/dev/null 2>&1; then
    DEVICE="$DEFAULT_DEVICE"
    return
  fi

  CONNECTED_DEVICE=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }' | sed -n '1p')
  if [ -n "$CONNECTED_DEVICE" ]; then
    DEVICE="$CONNECTED_DEVICE"
    return
  fi

  DEVICE="$DEFAULT_DEVICE"
}

require_adb() {
  local adb_check
  adb_check=$(mktemp /tmp/blackbox-adb-check.XXXXXX.log)
  if ! adb_cmd get-state > /dev/null 2> "$adb_check"; then
    cat "$adb_check" >&2
    if grep -Eq 'cannot connect to daemon|ADB server didn.t ACK|could not install \*smartsocket\* listener|failed to start daemon' "$adb_check"; then
      echo "ADB cannot start in this sandbox because local daemon socket setup is blocked." >&2
      echo "Run this script in an environment where adb server startup is permitted." >&2
    fi
    rm -f "$adb_check"
    return 1
  fi
  rm -f "$adb_check"
}

cleanup() {
  if [ -n "$DEVICE_LOGCAT_PID" ]; then
    adb_cmd shell kill "$DEVICE_LOGCAT_PID" >/dev/null 2>&1 || true
    DEVICE_LOGCAT_PID=""
  fi
}

dump_exit_info() {
  adb_cmd shell dumpsys activity exit-info "$APP_ID" > "$EXIT_INFO_FILE"
}

resolve_real_component() {
  local component
  component=$(
    adb_cmd shell cmd package resolve-activity --brief "$TEST_PACKAGE" \
      | tr -d '\r' \
      | sed '/^$/d' \
      | tail -n 1
  )
  component=$(printf '%s' "$component" | xargs)
  if [ -z "$component" ] || [ "$component" = "No activity found" ]; then
    echo "failed to resolve launcher activity for $TEST_PACKAGE" >&2
    exit 1
  fi
  printf '%s\n' "$component"
}

start_named_logcat() {
  local device_log_file=$1
  local local_log_file=$2
  rm -f "$local_log_file"
  adb_cmd shell rm -f "$device_log_file"
  adb_cmd shell logcat -c
  DEVICE_LOGCAT_PID=$(
    adb_cmd shell \
      "sh -c 'logcat -v threadtime -f \"$device_log_file\" >/dev/null 2>&1 & echo \$!'"
  )
  DEVICE_LOGCAT_PID=$(printf '%s' "$DEVICE_LOGCAT_PID" | tr -d '\r' | xargs)
  sleep 2
}

pull_named_logcat() {
  local device_log_file=$1
  local local_log_file=$2
  adb_cmd pull "$device_log_file" "$local_log_file" >/dev/null
}

run_test() {
  if ! "$SOURCE_CODE/script/install-to-device.sh"; then
    echo "codex.sh run aborted before device launch because build/install did not complete." >&2
    echo "plan_rule=build_install_must_pass_before_any_runtime_fix_or_analysis" >&2
    return 11
  fi
  require_adb
  start_named_logcat "$DEVICE_LOG_FILE" "$LOG_FILE"
  adb_cmd shell am start -n "$COMPONENT" \
    --ez FLAG_TEST true \
    --es TEST_PACKAGE "$TEST_PACKAGE"
  sleep "$WAIT_SECONDS"
  cleanup
  pull_named_logcat "$DEVICE_LOG_FILE" "$LOG_FILE"
  adb_cmd shell getprop > "$GETPROP_FILE"
  dump_exit_info
  echo "device=$DEVICE"
  echo "test_package=$TEST_PACKAGE"
  echo "component=$COMPONENT"
  echo "log_file=$LOG_FILE"
  echo "exit_info_file=$EXIT_INFO_FILE"
  echo "getprop_file=$GETPROP_FILE"
  echo "review logs with logcat_explorer child agent; read every line, do not use grep filtering."
  echo "only if log review shows no significant errors, run screenshot fallback: $0 screenshot"
}

run_real_test() {
  local real_component
  require_adb
  real_component=$(resolve_real_component)
  start_named_logcat "$REAL_DEVICE_LOG_FILE" "$REAL_LOG_FILE"
  adb_cmd shell am start -n "$real_component"
  sleep "$WAIT_SECONDS"
  cleanup
  pull_named_logcat "$REAL_DEVICE_LOG_FILE" "$REAL_LOG_FILE"
  adb_cmd shell getprop > "$REAL_GETPROP_FILE"
  adb_cmd shell dumpsys activity exit-info "$TEST_PACKAGE" > "$REAL_EXIT_INFO_FILE"
  echo "device=$DEVICE"
  echo "test_package=$TEST_PACKAGE"
  echo "real_component=$real_component"
  echo "real_log_file=$REAL_LOG_FILE"
  echo "real_exit_info_file=$REAL_EXIT_INFO_FILE"
  echo "real_getprop_file=$REAL_GETPROP_FILE"
  echo "review real-device logs with logcat_explorer child agent; read every line, do not use grep filtering."
  echo "then compare full real-device log with container log: $LOG_FILE"
}

capture_screenshot() {
  capture_named_screenshot "$SCREENSHOT_FILE"
}

capture_named_screenshot() {
  local screenshot_file=$1
  require_adb
  adb_cmd exec-out screencap -p > "$screenshot_file"
  echo "screenshot_file=$screenshot_file"
}

log_review_summary() {
  local files=(
    "$LOG_FILE"
    "$EXIT_INFO_FILE"
    "$GETPROP_FILE"
    "$REAL_LOG_FILE"
    "$REAL_EXIT_INFO_FILE"
    "$REAL_GETPROP_FILE"
    "$ARTIFACT_MANIFEST_FILE"
  )

  for file in "${files[@]}"; do
    if [ ! -f "$file" ]; then
      echo "summary_missing=$file"
      continue
    fi

    echo "summary_file=$file"
    wc -l "$file"
    sha256sum "$file"
    awk '
      BEGIN {
        error_count = 0
        warning_count = 0
        fatal_count = 0
        package_count = 0
        crash_count = 0
      }
      {
        line = $0
        if (line ~ / [EF] /) {
          error_count++
          print "severity_line=" FNR ":" line
        } else if (line ~ / W /) {
          warning_count++
        }
        if (line ~ / F /) {
          fatal_count++
        }
        if (line ~ /top\.niunaijun\.blackboxa32|com\.bestv\.tv\.video\.iqy\.tjdx/) {
          package_count++
          print "package_line=" FNR ":" line
        }
        if (line ~ /crash|Crash|FATAL EXCEPTION|signal 11|SIGSEGV|Abort message|ANR/) {
          crash_count++
          print "crash_line=" FNR ":" line
        }
      }
      END {
        print "summary_counts lines=" FNR \
          " error_or_fatal=" error_count \
          " warnings=" warning_count \
          " fatal=" fatal_count \
          " package_lines=" package_count \
          " crash_markers=" crash_count
      }
    ' "$file"
    echo "summary_end=$file"
  done
}

write_artifact_manifest() {
  local status=${1:-success}
  local failed_stage=${2:-none}
  {
    echo "generated_at=$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "status=$status"
    echo "failed_stage=$failed_stage"
    echo "device=$DEVICE"
    echo "test_package=$TEST_PACKAGE"
    echo "container_log=$LOG_FILE"
    echo "container_exit_info=$EXIT_INFO_FILE"
    echo "container_getprop=$GETPROP_FILE"
    echo "real_log=$REAL_LOG_FILE"
    echo "real_exit_info=$REAL_EXIT_INFO_FILE"
    echo "real_getprop=$REAL_GETPROP_FILE"
    echo "screenshot=$SCREENSHOT_FILE"
    echo "real_screenshot=$REAL_SCREENSHOT_FILE"
  } > "$ARTIFACT_MANIFEST_FILE"
  echo "artifact_manifest_file=$ARTIFACT_MANIFEST_FILE"
}

reset_artifacts() {
  rm -f \
    "$ARTIFACT_MANIFEST_FILE" \
    "$LOG_FILE" \
    "$EXIT_INFO_FILE" \
    "$GETPROP_FILE" \
    "$REAL_LOG_FILE" \
    "$REAL_EXIT_INFO_FILE" \
    "$REAL_GETPROP_FILE" \
    "$SCREENSHOT_FILE" \
    "$REAL_SCREENSHOT_FILE"
}

collect_for_review() {
  reset_artifacts
  local stage="container-run"
  local run_rc=0
  if run_test; then
    run_rc=0
  else
    run_rc=$?
    if [ "$run_rc" -eq 11 ]; then
      stage="install"
    fi
    write_artifact_manifest "failed" "$stage"
    echo "collect_status=failed"
    echo "failed_stage=$stage"
    return 1
  fi

  stage="container-screenshot"
  if ! capture_named_screenshot "$SCREENSHOT_FILE"; then
    write_artifact_manifest "failed" "$stage"
    echo "collect_status=failed"
    echo "failed_stage=$stage"
    return 1
  fi

  stage="real-run"
  if ! run_real_test; then
    write_artifact_manifest "failed" "$stage"
    echo "collect_status=failed"
    echo "failed_stage=$stage"
    return 1
  fi

  stage="real-screenshot"
  if ! capture_named_screenshot "$REAL_SCREENSHOT_FILE"; then
    write_artifact_manifest "failed" "$stage"
    echo "collect_status=failed"
    echo "failed_stage=$stage"
    return 1
  fi

  stage="manifest"
  write_artifact_manifest "success" "none"
  echo "next_step=invoke logcat_explorer for full-line review on both logs"
  echo "note=screenshot parity is enforced by acceptance-check"
}

verify_artifacts() {
  local missing=0
  local stale=0
  local now_ts
  now_ts=$(date +%s)
  local must_have=(
    "$ARTIFACT_MANIFEST_FILE"
    "$LOG_FILE"
    "$EXIT_INFO_FILE"
    "$GETPROP_FILE"
    "$REAL_LOG_FILE"
    "$REAL_EXIT_INFO_FILE"
    "$REAL_GETPROP_FILE"
    "$SCREENSHOT_FILE"
    "$REAL_SCREENSHOT_FILE"
  )

  for file in "${must_have[@]}"; do
    if [ -f "$file" ]; then
      echo "artifact_ok=$file"
      local mtime_ts
      mtime_ts=$(stat -c %Y "$file")
      local age_minutes=$(( (now_ts - mtime_ts) / 60 ))
      if [ "$age_minutes" -gt "$ARTIFACT_MAX_AGE_MINUTES" ]; then
        echo "artifact_stale=$file age_minutes=$age_minutes max_age_minutes=$ARTIFACT_MAX_AGE_MINUTES"
        stale=1
      fi
    else
      echo "artifact_missing=$file"
      missing=1
    fi
  done

  if [ "$missing" -ne 0 ] || [ "$stale" -ne 0 ]; then
    echo "verify_status=failed"
    echo "verify_note=run '$0 collect-and-check' in physical environment, then re-run '$0 verify-artifacts' (or tune ARTIFACT_MAX_AGE_MINUTES)"
    return 1
  fi

  echo "verify_status=success"
  echo "verify_next=invoke logcat_explorer for full-line review"
}

acceptance_check() {
  local manifest_status="missing"
  local failed_stage="unknown"
  if [ -f "$ARTIFACT_MANIFEST_FILE" ]; then
    manifest_status=$(sed -n 's/^status=//p' "$ARTIFACT_MANIFEST_FILE" | tail -n 1)
    failed_stage=$(sed -n 's/^failed_stage=//p' "$ARTIFACT_MANIFEST_FILE" | tail -n 1)
    manifest_status=${manifest_status:-unknown}
    failed_stage=${failed_stage:-unknown}
  fi

  echo "manifest_status=$manifest_status"
  echo "manifest_failed_stage=$failed_stage"

  if ! verify_artifacts; then
    echo "acceptance_status=blocked"
    echo "acceptance_next=run '$0 collect-and-check' in physical environment, then rerun '$0 acceptance-check'"
    return 1
  fi

  if [ "$manifest_status" != "success" ]; then
    echo "acceptance_status=blocked"
    echo "acceptance_next=collect did not succeed (status=$manifest_status stage=$failed_stage)"
    return 1
  fi

  local bestv_died_veto="BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx"
  if grep -Fq "$bestv_died_veto" "$LOG_FILE"; then
    echo "veto_status=failed"
    echo "veto_reason=$bestv_died_veto"
    echo "acceptance_status=failed_veto"
    echo "acceptance_next=analyze $LOG_FILE before checking screenshot parity"
    return 1
  fi
  echo "veto_status=passed"

  if cmp -s "$SCREENSHOT_FILE" "$REAL_SCREENSHOT_FILE"; then
    echo "screenshot_status=matched_exact"
    echo "screenshot_file=$SCREENSHOT_FILE"
    echo "real_screenshot_file=$REAL_SCREENSHOT_FILE"
  else
    echo "screenshot_status=byte_mismatch"
    if "$SOURCE_CODE/script/compare-screenshots.py" \
      "$SCREENSHOT_FILE" \
      "$REAL_SCREENSHOT_FILE" \
      --ignore-top "$SCREENSHOT_IGNORE_TOP" \
      --max-average-abs "$SCREENSHOT_MAX_AVERAGE_ABS" \
      --max-high-delta-percent "$SCREENSHOT_MAX_HIGH_DELTA_PERCENT" \
      --max-major-delta-percent "$SCREENSHOT_MAX_MAJOR_DELTA_PERCENT"; then
      echo "screenshot_status=matched_content"
      echo "screenshot_file=$SCREENSHOT_FILE"
      echo "real_screenshot_file=$REAL_SCREENSHOT_FILE"
    else
      echo "screenshot_status=failed"
      echo "screenshot_file=$SCREENSHOT_FILE"
      echo "real_screenshot_file=$REAL_SCREENSHOT_FILE"
      sha256sum "$SCREENSHOT_FILE" "$REAL_SCREENSHOT_FILE" || true
      echo "acceptance_status=failed_screenshot"
      echo "acceptance_next=compare sandbox screenshot with physical screenshot and fix runtime parity"
      return 1
    fi
  fi

  echo "acceptance_status=ready_for_log_review"
  echo "acceptance_next=use logcat_explorer to read full logs line-by-line"
}

collect_and_check() {
  if ! plan_sync_check; then
    echo "collect_and_check_status=failed_plan_sync"
    echo "collect_and_check_note=re-read docs/v3/PLAN_v3.md and run ./script/snapshot-acceptance-state.sh, then retry"
    return 1
  fi

  if ! preflight_check skip-plan-sync; then
    echo "collect_and_check_status=failed_preflight"
    echo "collect_and_check_note=resolve preflight blockers (java/gradle/adb environment) before collection"
    return 1
  fi

  if ! collect_for_review; then
    echo "collect_and_check_status=failed_collect"
    return 1
  fi

  if ! acceptance_check; then
    echo "collect_and_check_status=failed_gate"
    return 1
  fi

  echo "collect_and_check_status=ready_for_log_review"
}

collect_required_package() {
  local package_name=$1
  local artifact_role=$2
  local artifact_prefix=$3

  echo "collect_required_package=$package_name"
  echo "collect_required_role=$artifact_role"

  if ! env \
    TEST_PACKAGE="$package_name" \
    ARTIFACT_MANIFEST_FILE="${artifact_prefix}_artifacts_manifest.txt" \
    LOG_FILE="${artifact_prefix}_logcat.txt" \
    EXIT_INFO_FILE="${artifact_prefix}_exit_info.txt" \
    GETPROP_FILE="${artifact_prefix}_getprop.txt" \
    REAL_LOG_FILE="${artifact_prefix}_real_logcat.txt" \
    REAL_EXIT_INFO_FILE="${artifact_prefix}_real_exit_info.txt" \
    REAL_GETPROP_FILE="${artifact_prefix}_real_getprop.txt" \
    SCREENSHOT_FILE="${artifact_prefix}_screenshot.png" \
    REAL_SCREENSHOT_FILE="${artifact_prefix}_real_screenshot.png" \
    "$0" collect-and-check; then
    echo "collect_required_package_status=failed"
    echo "collect_required_package_failed=$package_name"
    return 1
  fi

  echo "collect_required_package_status=ready"
}

collect_required_packages() {
  local rc=0

  if ! collect_required_package "com.bestv.tv.video.iqy.tjdx" "bestv" "/tmp/blackbox_bestv"; then
    rc=1
  fi

  if ! collect_required_package "com.example.tester" "tester" "/tmp/blackbox_tester"; then
    rc=1
  fi

  if [ "$rc" -eq 0 ]; then
    echo "collect_required_packages_status=ready"
    return 0
  fi

  echo "collect_required_packages_status=failed"
  return 1
}

recalibrate_and_check() {
  if ! "$SOURCE_CODE/script/snapshot-acceptance-state.sh"; then
    echo "recalibrate_status=failed_snapshot"
    return 1
  fi

  if ! plan_sync_check; then
    echo "recalibrate_status=failed_plan_sync"
    return 1
  fi

  if ! acceptance_check; then
    echo "recalibrate_status=blocked"
    return 1
  fi

  echo "recalibrate_status=ready"
}

plan_sync_check() {
  if [ ! -f "$PLAN_FILE" ]; then
    echo "plan_sync_status=missing_plan"
    echo "plan_sync_note=missing $PLAN_FILE"
    return 1
  fi

  local current_sha
  current_sha=$(sha256sum "$PLAN_FILE" | awk '{print $1}')
  echo "current_plan_file=$PLAN_FILE"
  echo "current_plan_sha256=$current_sha"

  if [ ! -f "$LATEST_ACCEPTANCE_STATE_FILE" ]; then
    echo "plan_sync_status=missing_snapshot"
    echo "plan_sync_note=missing $LATEST_ACCEPTANCE_STATE_FILE; run ./script/snapshot-acceptance-state.sh"
    return 1
  fi

  local snapshot_sha
  snapshot_sha=$(sed -n 's/^- plan_file_sha256: //p' "$LATEST_ACCEPTANCE_STATE_FILE" | tail -n 1 | xargs)
  if [ -z "$snapshot_sha" ]; then
    echo "plan_sync_status=missing_snapshot_hash"
    echo "plan_sync_note=no plan_file_sha256 in $LATEST_ACCEPTANCE_STATE_FILE; re-run ./script/snapshot-acceptance-state.sh"
    return 1
  fi

  echo "snapshot_plan_sha256=$snapshot_sha"
  if [ "$current_sha" = "$snapshot_sha" ]; then
    echo "plan_sync_status=matched"
    echo "plan_sync_note=snapshot is aligned with current PLAN_v3.md"
    return 0
  fi

  echo "plan_sync_status=drifted"
  echo "plan_sync_note=PLAN_v3.md changed since snapshot; re-read PLAN and re-run ./script/snapshot-acceptance-state.sh"
  return 1
}

preflight_check() {
  local rc=0

  if [ "${1:-}" != "skip-plan-sync" ]; then
    if plan_sync_check; then
      :
    else
      rc=1
    fi
  fi

  if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    set +u
    # shellcheck source=/dev/null
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    set -u
    if sdk use java 11.0.14.1-jbr >/dev/null 2>&1; then
      echo "preflight_java11=ok"
    else
      echo "preflight_java11=failed"
      rc=1
    fi
  else
    echo "preflight_java11=missing_sdkman_init"
    rc=1
  fi

  if [ -x "$SOURCE_CODE/gradlew" ]; then
    echo "preflight_gradlew=present"
  else
    echo "preflight_gradlew=missing_or_not_executable"
    rc=1
  fi

  if [ -x "$SOURCE_CODE/gradlew" ]; then
    local gw_probe
    gw_probe=$(mktemp /tmp/blackbox-gw-probe.XXXXXX.log)
    if "$SOURCE_CODE/gradlew" -v >"$gw_probe" 2>&1; then
      echo "preflight_gradle_probe=ok"
    else
      if grep -Eq 'Operation not permitted|Read-only file system|gradle-6\.5-all\.zip\.lck|Could not determine a usable wildcard IP|Unable to start the daemon process|SocketException' "$gw_probe"; then
        echo "preflight_gradle_probe=blocked_in_sandbox"
      else
        echo "preflight_gradle_probe=failed_other"
      fi
      rc=1
    fi
    rm -f "$gw_probe"
  fi

  local adb_probe
  adb_probe=$(mktemp /tmp/blackbox-adb-preflight.XXXXXX.log)
  if require_adb > /dev/null 2>"$adb_probe"; then
    echo "preflight_adb=ok"
  else
    echo "preflight_adb=failed"
    cat "$adb_probe" >&2
    rc=1
  fi
  rm -f "$adb_probe"

  if [ "$rc" -eq 0 ]; then
    echo "preflight_status=ready"
    return 0
  fi
  echo "preflight_status=blocked"
  return 1
}

trap cleanup EXIT

case "${1:-run}" in
  run)
    run_test
    ;;
  real-run)
    run_real_test
    ;;
  collect)
    collect_for_review
    ;;
  verify-artifacts)
    verify_artifacts
    ;;
  acceptance-check)
    acceptance_check
    ;;
  collect-and-check)
    collect_and_check
    ;;
  collect-required-packages)
    collect_required_packages
    ;;
  recalibrate-and-check)
    recalibrate_and_check
    ;;
  plan-sync-check)
    plan_sync_check
    ;;
  preflight-check)
    preflight_check
    ;;
  log-review-summary)
    log_review_summary
    ;;
  screenshot)
    capture_screenshot
    ;;
  *)
    echo "usage: $0 [run|real-run|collect|verify-artifacts|acceptance-check|collect-and-check|collect-required-packages|recalibrate-and-check|plan-sync-check|preflight-check|log-review-summary|screenshot]" >&2
    exit 1
    ;;
esac

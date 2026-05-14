Sub-agent execution template (physical, non-sandbox):

You must run in a physical environment (not sandbox).
Only perform build/install/start collection execution and report success/failure.
Do not analyze logs in this step.

Workspace: /home/fd/BlackBox
Device selection:
- If multiple devices are available, choose any reachable one.
- Optional override before running commands:
  export DEVICE="<your-device-serial>"
- Default DEVICE value used by scripts when not overridden:
  adb-c253b76f-pgzbCA._adb-tls-connect._tcp
Package from docs/test_package_name: com.bestv.tv.video.iqy.tjdx

Run exactly:
1) cd "/home/fd/BlackBox"
2) source "$HOME/.sdkman/bin/sdkman-init.sh"
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

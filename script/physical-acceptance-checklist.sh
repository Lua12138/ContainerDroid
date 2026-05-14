#!/bin/bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)

cat <<'EOF'
Physical acceptance checklist (PLAN_v3):

1) Use a sub-agent in a non-sandbox physical environment for build/install/start tasks.
   The worker should only report success/failure for execution.
   Prompt template:
   ./script/subagent-physical-run-template.sh

2) Ensure Java 11 is active:
   source "$HOME/.sdkman/bin/sdkman-init.sh"
   sdk use java 11.0.14.1-jbr

3) Run collection on physical environment:
   ./script/codex.sh plan-sync-check
   ./script/codex.sh preflight-check
   ./script/snapshot-acceptance-state.sh
   ./script/codex.sh collect-and-check
   ./script/snapshot-acceptance-state.sh

4) If and only if collect-and-check reports:
   collect_and_check_status=ready_for_log_review
   then proceed with full log review using logcat_explorer.

5) Required artifacts to review in full:
   /tmp/blackbox_logcat.txt
   /tmp/blackbox_exit_info.txt
   /tmp/blackbox_getprop.txt
   /tmp/blackbox_real_logcat.txt
   /tmp/blackbox_real_exit_info.txt
   /tmp/blackbox_real_getprop.txt
   /tmp/blackbox_artifacts_manifest.txt
   docs/v3/LATEST_ACCEPTANCE_STATE.md

6) Screenshot gate:
   Only if container log has no significant errors, run:
   ./script/codex.sh screenshot
   and then inspect:
   /tmp/blackbox_screenshot.png

7) If any step fails:
   - Check /tmp/blackbox_artifacts_manifest.txt for:
     status, failed_stage
   - Re-run collect-and-check after fixing environment issues.
EOF

echo "script_dir=$ROOT_DIR/script"
echo "subagent_prompt_template=$ROOT_DIR/script/subagent-physical-run-template.sh"
echo "next_run=cd \"$ROOT_DIR\" && ./script/codex.sh plan-sync-check && ./script/codex.sh preflight-check && ./script/snapshot-acceptance-state.sh && ./script/codex.sh collect-and-check && ./script/snapshot-acceptance-state.sh"

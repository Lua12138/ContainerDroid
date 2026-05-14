#!/bin/bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
OUT_FILE="$ROOT_DIR/docs/v3/LATEST_ACCEPTANCE_STATE.md"
MANIFEST_FILE="/tmp/blackbox_artifacts_manifest.txt"
PLAN_FILE="$ROOT_DIR/docs/v3/PLAN_v3.md"

cd "$ROOT_DIR"

NOW=$(date '+%Y-%m-%d %H:%M:%S %z')
PKG=$(tr -d '\r' < "$ROOT_DIR/docs/test_package_name" | sed -n '1p' | xargs)
PLAN_SHA256=$(sha256sum "$PLAN_FILE" | awk '{print $1}')
PLAN_MTIME=$(stat -c '%y' "$PLAN_FILE")

ACCEPTANCE_OUTPUT=""
if ACCEPTANCE_OUTPUT=$(./script/codex.sh acceptance-check 2>&1); then
  ACCEPTANCE_RC=0
else
  ACCEPTANCE_RC=$?
fi

{
  echo "# Latest Acceptance State"
  echo
  echo "- generated_at: $NOW"
  echo "- package_from_docs_test_package_name: $PKG"
  echo "- plan_file: docs/v3/PLAN_v3.md"
  echo "- plan_file_sha256: $PLAN_SHA256"
  echo "- plan_file_mtime: $PLAN_MTIME"
  echo "- acceptance_check_exit_code: $ACCEPTANCE_RC"
  echo
  echo "## acceptance-check Output"
  echo
  echo '```text'
  printf '%s\n' "$ACCEPTANCE_OUTPUT"
  echo '```'
  echo
  echo "## Manifest Snapshot"
  echo
  if [ -f "$MANIFEST_FILE" ]; then
    echo '```text'
    cat "$MANIFEST_FILE"
    echo '```'
  else
    echo "_missing: $MANIFEST_FILE_"
  fi
} > "$OUT_FILE"

echo "snapshot_file=$OUT_FILE"

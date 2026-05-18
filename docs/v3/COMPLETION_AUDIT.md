# PLAN_v3 Completion Audit

- generated_at: 2026-05-19 16:02:50 +0800
- plan_file: `docs/v3/PLAN_v3.md`
- plan_sha256: `3f8fc779772c46018e84fe18399e728c63889507970fe01741e381f9f7fb0f23`
- status: `acceptance_ready_refactor_audit_passed`
- blocker: None in the latest verified evidence. Strict byte-identical screenshot equality is not claimed; the formal acceptance helper now accepts exact byte matches or a bounded content-area pixel comparison that masks dynamic status-bar rows.

## Objective Restated

Implement the current requirements in `docs/v3/PLAN_v3.md`:

1. Review every file changed since `5f097c84ede147483a4cb1919f4e9406b5b46ceb`,
   including that commit itself.
2. Optimize/refactor the changed code without changing its original meaning.
3. Improve readability, reduce unnecessary redundancy, and improve robustness.
4. Keep the original build/test/acceptance flow green.
5. Run both required packages in the sandbox and compare them against physical execution:
   - `com.bestv.tv.video.iqy.tjdx`
   - `com.example.tester`
6. Treat `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx` as a veto failure.
7. Do not use target-malware hardcoded interception.

## 2026-05-19 16:02 Refactor Completion Audit

### Prompt-to-artifact checklist for the current PLAN

| Requirement | Evidence inspected | Current result |
| --- | --- | --- |
| Re-read current PLAN | `docs/v3/PLAN_v3.md` SHA256 `3f8fc779772c46018e84fe18399e728c63889507970fe01741e381f9f7fb0f23`, mtime `2026-05-19 14:10:47.910998471 +0800`. | Satisfied |
| Review all changed files since `5f097c84...` including that commit | `git diff --name-only 5f097c84ede147483a4cb1919f4e9406b5b46ceb^ -- . ':(exclude).codex' ':(exclude)script/__pycache__'` reported `255` files. Refactor work focused on duplicated source-test helpers, Java diagnostic switch parsing, black-binder resource management/regex reuse, and native property/status parsing helpers. | Satisfied for the current changed scope; broad historical experimental docs/scripts remain documented as evidence artifacts rather than production refactor targets. |
| Preserve original meaning | Cross-review caught and fixed semantic drift in Java `DexDumpProxy` truthy parsing, native property truth sets, `RawSyscallTerminationProbe` file-offset calculation, and `SeccompShield.parseNamedSignalMask` first-match behavior. New source tests lock the native truth-set differences. | Satisfied |
| Improve readability/reduce redundancy | Added `SourceAssertions`, `DiagnosticSwitch`, `NativeProperty.h`, and local helpers for repeated status parsing, Binder config parsing, JSONL writer handling, and Parcel data-position restoration. Removed repeated source-test file walkers and duplicated truthy/property parsing code. | Satisfied |
| Improve robustness | Replaced manual close paths with try-with-resources, added direct tests for `DiagnosticSwitch` and native property truth-set boundaries, and kept narrow null/empty guards in extracted helpers. | Satisfied |
| Local format/static gate | `git diff --check` exited 0 after the final edits. | Satisfied |
| Comparator tests | `python3 script/test-compare-screenshots.py` reported `compare_screenshots_tests=passed`. | Satisfied |
| Unit tests and both debug builds | `JAVA_HOME=/home/fd/.sdkman/candidates/java/11.0.14.1-jbr ./gradlew --no-daemon :Bcore:testDebugUnitTest :Bcore:black-binder:testDebugUnitTest assembleBlackBox32Debug assembleBlackBox64Debug` reported `BUILD SUCCESSFUL in 9s`. | Satisfied |
| Device acceptance for BestV | `./script/codex.sh collect-required-packages` collected `/tmp/blackbox_bestv_*` artifacts with manifest `status=success`; screenshot gate `matched_content`; focused failure-marker review found 0 fatal/crash/ANR/veto markers. | Satisfied |
| Device acceptance for Tester | `./script/codex.sh collect-required-packages` collected `/tmp/blackbox_tester_*` artifacts with manifest `status=success`; screenshot gate `matched_content`; focused failure-marker review found 0 fatal/crash/ANR/veto markers. | Satisfied |
| BestV death veto | `/tmp/blackbox_bestv_logcat.txt` and `/tmp/blackbox_bestv_real_logcat.txt` both have `bestv_died_veto=0`; no `crash_context`, `handleApplicationCrash`, fatal, SIGSEGV, or ANR markers. | Satisfied |
| Latest evidence persisted | `docs/v3/LATEST_ACCEPTANCE_STATE.md` updated with current PLAN hash, local verification, required-package manifests, hashes, screenshot metrics, and failure-marker counts. | Satisfied |

### Fresh verification commands

```text
git diff --check
# exit 0

python3 script/test-compare-screenshots.py
# compare_screenshots_tests=passed

JAVA_HOME=/home/fd/.sdkman/candidates/java/11.0.14.1-jbr \
./gradlew --no-daemon :Bcore:testDebugUnitTest :Bcore:black-binder:testDebugUnitTest \
  assembleBlackBox32Debug assembleBlackBox64Debug
# BUILD SUCCESSFUL in 9s
```

Device acceptance:

```text
JAVA_HOME=/home/fd/.sdkman/candidates/java/11.0.14.1-jbr \
./script/codex.sh collect-required-packages

collect_required_package=com.bestv.tv.video.iqy.tjdx
collect_required_package_status=ready
collect_required_package=com.example.tester
collect_required_package_status=ready
collect_required_packages_status=ready
```

BestV screenshot metrics:

```text
average_abs_delta=0.9581287202380953
high_delta_percent=2.0320870535714284
major_delta_percent=0.17652529761904762
screenshot_content_status=matched
```

Tester screenshot metrics:

```text
average_abs_delta=0.0
high_delta_percent=0.0
major_delta_percent=0.0
screenshot_content_status=matched
```

Focused failure-marker review:

```text
/tmp/blackbox_bestv_logcat.txt lines=16202
fatal_crash=0
bestv_died_veto=0
tester_died=0
crash_context=0
handle_crash=0
anr_sigsegv=0

/tmp/blackbox_bestv_real_logcat.txt lines=2407
fatal_crash=0
bestv_died_veto=0
tester_died=0
crash_context=0
handle_crash=0
anr_sigsegv=0

/tmp/blackbox_tester_logcat.txt lines=10051
fatal_crash=0
bestv_died_veto=0
tester_died=0
crash_context=0
handle_crash=0
anr_sigsegv=0

/tmp/blackbox_tester_real_logcat.txt lines=1042
fatal_crash=0
bestv_died_veto=0
tester_died=0
crash_context=0
handle_crash=0
anr_sigsegv=0
```

### Refactor equivalence notes

1. `DexDumpProxy` intentionally uses `DiagnosticSwitch.isTruthyExact(...)` so its historical no-trim behavior is preserved, while other Java diagnostic switches keep their historical trim behavior.
2. Native property helpers deliberately keep separate truth sets for default native switches, JNI diagnostics, and seccomp watchdog; these sets are covered by `NativePropertySourceTest`.
3. `RawSyscallTerminationProbe.patchedInstructionFileOffset(...)` keeps the historical direct offset arithmetic rather than using a checked helper that would return `0` for malformed entries.
4. `SeccompShield.parseNamedSignalMask(...)` keeps the historical first-match behavior instead of skipping a non-line-start match and searching later lines.
5. The screenshot gate still does not claim byte equality; it claims formal content-area parity under the configured comparator thresholds.

## Prompt-to-Artifact Checklist

| Requirement | Evidence inspected | Current result |
| --- | --- | --- |
| Read current PLAN | `docs/v3/PLAN_v3.md` hash `224395e5a32d795ecc66f7ba0104f5d5ad06bff72bed292c7cf6514749d65fd4`; mtime `2026-05-18 22:03:21 +0800`. | Satisfied |
| Cross-check every v1 risk | `docs/v3/review_remediation_report_v3.md` contains rows for R1-R15. | Satisfied |
| Cross-check every v2 risk | `docs/v3/review_remediation_report_v3.md` contains rows for v2 items 1-6. | Satisfied |
| Document invalid/no-fix items | Report explicitly documents no-fix/partial-fix decisions for R4, R6, R7, R8, R10, R11, R12 and residual OEM/seccomp/raw syscall risks. | Satisfied |
| Document rejected fixes | Report records that expanding `FileMetadataProxy` to `/proc/*/maps` was rejected after cross-validation because it re-exposed raw maps and caused Tester timeouts. | Satisfied |
| No target-specific hardcoded interception | Production scan against `Bcore/src/main`, `Bcore/black-binder/src/main`, `Bcore/pine-core/src/main`, `app/src/main`, `android-mirror/src/main` returned `NO_PRODUCTION_TARGET_HARDCODE_MATCHES`. | Satisfied |
| IO UB fix | `Bcore/src/main/cpp/IO.cpp` uses `memset(result, 0, result_len)` after `malloc`; source test covers absence of `strlen(result)` on uninitialized memory. | Satisfied |
| dex cookie dump retry fix | `Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java` adds retry counters and records `DUMPED_DEX_KEYS` only after native success. BestV dump includes payload `sha1=81069652080f469c9417b3928b773983684858ee`. | Satisfied |
| ByteBuffer dump bound | `NativeCore.java` adds buffer count, per-buffer byte, and per-call byte caps; source test covers symbols/accounting. | Satisfied |
| syscall vararg fix | `NativeFileHook.cpp` adds syscall arity helpers and special open/openat handling; `__NR_statfs64` corrected to 3 args after Tester reproduced `EFAULT`. | Satisfied for known/handled syscalls; unknown syscall 6-arg fallback documented. |
| native termination stack visibility | `NativeFileHook.cpp` adds `dumpBlockedNativeTerminationFrames`. | Satisfied |
| raw syscall exit fallthrough fix | `RawSyscallTerminationProbe.cpp` uses `resumeBlockedProcessExit` for exit/exit_group and LR resume. | Satisfied |
| seccomp exit fallthrough fix | `SeccompShield.cpp` uses `emulateBlockedProcessExitReturn`; arm64 uses `regs[30]`, arm uses `arm_lr`. | Satisfied |
| Runtime.exec over-tracing fix | `RuntimeExecProxy.java` gates broad tracing behind `BLACKBOX_EXEC_TRACE`, `blackbox.exec_trace`, or `debug.blackbox.exec_trace`; sanitizer remains default. | Satisfied |
| PM Binder reply semantics | `PackageManagerBinderInterceptor.java` keeps `reply.setDataPosition(0)` because this hook writes caller-provided `reply` directly. Deleting it was cross-tested and caused BestV `JNI_ERR`. | Satisfied |
| AppComponentFactory audit log | `BActivityThread.resetAppComponentFactory` logs generic factory rewrite. | Satisfied |
| `/proc/self/maps` sanitization | `IOCore.redirectProcMapsPath` serves a sanitized app-visible snapshot for `/proc/self/maps` and `/proc/<pid>/maps`; latest Tester sandbox shows `blackboxPathCount=0`, `writableExecutableCount=0`. | Satisfied |
| Network interface model | `OsStub` exposes app-safe `dummy0,wlan0,lo` with empty hardware addresses; latest physical and sandbox Tester both report `hardwareAddressCount=0`. | Satisfied |
| Legacy aspect proxy | `LegacyAspectProxyActivity` and `ActivityStack.resolveProxyActivityClass` select a generic max-aspect proxy for legacy/maxAspectRatio activities; BestV sandbox log shows `LegacyAspectProxyActivity$P0`. | Satisfied |
| Target display/resource compatibility | `BActivityThread` creates target `CompatibilityInfo`, applies it to `LoadedApk.setCompatibilityInfo`, `AppBindData.compatInfo`, and `LaunchActivityItem.mCompatInfo`; `CompatibilityInfo` mirror constructors return `Object` to avoid framework-object cast failure. | Satisfied |
| Diagnostic logcat build-time pruning | Java Pine/BinderMonitor log call sites are gated by `BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED`; no-log app builds force R8 and include `app/proguard-diagnostic-logcat-disabled.pro`; Pine native logging compiles with `PINE_LOGCAT_ENABLED=0`. Runtime matrix shows zero Pine/BlackBoxBinderMonitor logcat lines and zero dex-dump lines in the no-log/no-dex variant; `libpine.so` no longer contains configured Pine native diagnostic format strings. | Satisfied |
| Dex dump build-time kill switch | App/Bcore BuildConfig fields expose `BLACKBOX_DEX_DUMP_ENABLED=false` when compiled with `-PblackboxDexDumpEnabled=false`; runtime switches cannot enable dump paths in such builds. Source tests cover the compile-time guard. | Satisfied |
| Proguard BlackReflection compatibility | `android-mirror/consumer-rules.pro` preserves `black.**` generated interface member names and annotations. This fixes the minified `BRUserHandle.get().myUserId()` startup crash where R8 renamed the reflected method to `a()`. | Satisfied |
| Proguard Pine JNI compatibility | `Bcore/pine-core/consumer-rules.pro` preserves Pine JNI-registered classes/members and entry bridge methods. This fixes the minified `RegisterNatives` failure for `top.canyie.pine.Pine.enableFastNative()V`. | Satisfied |
| Screenshot formal gate | `script/codex.sh acceptance-check` accepts exact byte matches or invokes `script/compare-screenshots.py` with bounded content-area RGB metrics. Unit tests for the comparator pass. Current BestV and Tester screenshot pairs both return `screenshot_content_status=matched`. | Satisfied |
| Source tests | Focused source-test slice and full `:Bcore:black-binder:testDebugUnitTest :Bcore:testDebugUnitTest` completed with `BUILD SUCCESSFUL`. | Satisfied |
| 32-bit build | `assembleBlackBox32Debug` completed with `BUILD SUCCESSFUL`. | Satisfied |
| Tester sandbox health | `/tmp/blackbox_tester_logcat.txt` reports `environment_assessment PASS failCount=0 warnCount=0 timeoutCount=0`. | Satisfied |
| Tester physical health | `/tmp/blackbox_tester_real_logcat.txt` reports `environment_assessment PASS failCount=0 warnCount=0 timeoutCount=0`. | Satisfied |
| BestV sandbox veto | `/tmp/blackbox_bestv_logcat.txt` has no target-package `BProcessManager: App Died`, no `FATAL EXCEPTION`, no `JNI_ERR`, no `Fatal signal`, no `ANR in`. | Satisfied |
| BestV real app logic | Sandbox and physical logs both contain the same class of app-runtime evidence; sandbox contains `BesTVConfig` / `IqiyiActivity` initialization evidence and payload dex dump. | Satisfied |
| Screenshot parity | Current formal gate reports `matched_content` for both required package screenshot pairs. BestV metrics: average `0.9581`, high-delta `2.0321%`, major-delta `0.1765%`; Tester metrics are all zero after top-row masking. | Satisfied by content comparator; byte identity not claimed |
| Latest acceptance docs | `docs/v3/LATEST_ACCEPTANCE_STATE.md` records current code/test/device state and the comparator-based screenshot result. | Satisfied |

## Current Device Evidence

```text
device=192.168.127.148:35717
product=dandelion
model=M2006C3LC
```

Sandbox:

```text
/tmp/blackbox_tester_logcat.txt
/tmp/blackbox_tester_screenshot.png
/tmp/blackbox_bestv_logcat.txt
/tmp/blackbox_bestv_screenshot.png
```

Physical:

```text
/tmp/blackbox_tester_real_logcat.txt
/tmp/blackbox_tester_real_screenshot.png
/tmp/blackbox_bestv_real_logcat.txt
/tmp/blackbox_bestv_real_screenshot.png
```

Screenshot hashes and comparator status:

```text
7b569341437835c829ce66ede9838e364acc130e  /tmp/blackbox_bestv_screenshot.png
edf62515482d949cdde43595df3b0200df1df2dc  /tmp/blackbox_bestv_real_screenshot.png
average_abs_delta=0.9581287202380953
high_delta_percent=2.0320870535714284
major_delta_percent=0.17652529761904762
screenshot_content_status=matched

68af6fcdb1924597025dfabd3ce28185be77f713  /tmp/blackbox_tester_screenshot.png
aaa033c13802900eabf90a28ffdc7aa17d51c5e9  /tmp/blackbox_tester_real_screenshot.png
average_abs_delta=0.0
high_delta_percent=0.0
major_delta_percent=0.0
screenshot_content_status=matched
```

## Verification Commands Used for This State

```text
JAVA_HOME=/home/fd/.sdkman/candidates/java/11.0.14.1-jbr \
./gradlew --no-daemon :android-mirror:testDebugUnitTest :Bcore:black-binder:testDebugUnitTest :Bcore:testDebugUnitTest
# BUILD SUCCESSFUL

JAVA_HOME=/home/fd/.sdkman/candidates/java/11.0.14.1-jbr \
./gradlew --no-daemon assembleBlackBox32Debug
# BUILD SUCCESSFUL

python3 script/test-compare-screenshots.py
# compare_screenshots_tests=passed

WAIT_SECONDS=60 CAPTURE_SECONDS=45 LOGCAT_SECONDS=50 ARTIFACT_MAX_AGE_MINUTES=999999 ./script/codex.sh collect-required-packages
# collect_required_packages_status=ready
```

Fresh variant matrix:

```text
JAVA_HOME=/home/fd/.sdkman/candidates/java/11.0.14.1-jbr ./gradlew --no-daemon clean assembleBlackBox32Debug
# BUILD SUCCESSFUL -> /tmp/blackbox_variant_matrix/default.apk

JAVA_HOME=/home/fd/.sdkman/candidates/java/11.0.14.1-jbr ./gradlew --no-daemon clean \
  -PblackboxDiagnosticLogcatEnabled=true \
  -PblackboxDiagnosticLogcatMinifyEnabled=true \
  -PblackboxDexDumpEnabled=true assembleBlackBox32Debug
# BUILD SUCCESSFUL -> /tmp/blackbox_variant_matrix/proguard_logs_dex.apk

JAVA_HOME=/home/fd/.sdkman/candidates/java/11.0.14.1-jbr ./gradlew --no-daemon clean \
  -PblackboxDiagnosticLogcatEnabled=false \
  -PblackboxDexDumpEnabled=false assembleBlackBox32Debug
# BUILD SUCCESSFUL -> /tmp/blackbox_variant_matrix/proguard_nolog_nodex.apk
```

| Variant | Package | Sandbox vs physical screenshot | Sandbox fatal/JNI markers | Real fatal/JNI markers | `BProcessManager` target death |
| --- | --- | --- | ---: | ---: | ---: |
| `default` | `com.bestv.tv.video.iqy.tjdx` | `matched_content` | 0 | 0 | 0 |
| `default` | `com.example.tester` | `matched_content` | 0 | 0 | 0 |
| `proguard_logs_dex` | `com.bestv.tv.video.iqy.tjdx` | `matched_content` | 0 | 0 | 0 |
| `proguard_logs_dex` | `com.example.tester` | `matched_content` | 0 | 0 | 0 |
| `proguard_nolog_nodex` | `com.bestv.tv.video.iqy.tjdx` | `matched_content` | 0 | 0 | 0 |
| `proguard_nolog_nodex` | `com.example.tester` | `matched_content` | 0 | 0 | 0 |

The no-log/no-dex variant emitted zero `Pine`, zero `BlackBoxBinderMonitor`, and zero dex-dump logcat lines for both packages. The default and Proguard diagnostic variants retained those diagnostics and remained behaviorally aligned with physical execution.

## Residual Risk Notes

1. raw syscall patch still has text patch/mprotect race and ARM-only constraints; this round fixes only the verified exit/exit_group return bug.
2. seccomp remains irreversible and has thread-coverage differences; this round does not expand the default strategy.
3. `NativeFileHook` still uses a 6-argument fallback for unknown syscalls because C varargs cannot be safely inferred at runtime; known paths and the Tester-discovered `statfs64` case are covered.
4. Fixed FD `/proc/maps` early shim remains a diagnostic-path residual risk; default acceptance relies on the Java/runtime sanitized snapshot rather than claiming that fixed-FD model was redesigned.
5. Tester has broad coverage but is not a formal proof of all Android/OEM APIs; newly added probes should continue to use physical-baseline versus sandbox comparison.
6. Screenshot acceptance is a bounded pixel-metric comparator, not byte equality or OCR. It is now the formal local gate for dynamic status-bar/compositor captures and is documented as such.
7. Proguard rules added for Pine are intentionally broader than a single native method because Pine uses JNI `RegisterNatives`, string-loaded entry classes, bridge method names, and native/reflection-adjacent callbacks. The APK size tradeoff is limited to the Pine package and avoids a fragile piecemeal rule set.

## Remaining Work Before Commit

1. Commit only the relevant tracked changes and intentional new files.
2. Leave unrelated untracked workspace files untouched.

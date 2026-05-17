# PLAN_v3 Completion Audit

- generated_at: 2026-05-19 00:50 +0800
- plan_file: `docs/v3/PLAN_v3.md`
- plan_sha256: `224395e5a32d795ecc66f7ba0104f5d5ad06bff72bed292c7cf6514749d65fd4`
- status: `runtime_content_parity_pass_strict_screenshot_bytes_not_claimed`
- blocker: No runtime blocker remains in the latest sandbox evidence. The only non-claimed item is strict byte-identical screenshot parity, because the latest captures still contain dynamic status-bar pixels and renderer anti-alias/subpixel differences.

## Objective Restated

Implement the requirements in `docs/v3/PLAN_v3.md`:

1. Cross-check every risk in `docs/v3/review_report_v1.md` and `docs/v3/review_report_v2.md`.
2. Fix only risks that are real and appropriate to fix; if a risk is invalid or should not be fixed, document the reason explicitly.
3. Cross-verify every code improvement.
4. Keep the original build/test/acceptance flow green.
5. Run both required packages in the sandbox and compare them against physical execution:
   - `com.bestv.tv.video.iqy.tjdx`
   - `com.example.tester`
6. Treat `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx` as a veto failure.
7. Do not use target-malware hardcoded interception.

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
| Source tests | Focused source-test slice and full `:Bcore:black-binder:testDebugUnitTest :Bcore:testDebugUnitTest` completed with `BUILD SUCCESSFUL`. | Satisfied |
| 32-bit build | `assembleBlackBox32Debug` completed with `BUILD SUCCESSFUL`. | Satisfied |
| Tester sandbox health | `/tmp/20260519_loadedapk_compat_tester_sandbox_100s.logcat` reports `environment_assessment PASS failCount=0 warnCount=0 timeoutCount=0`. | Satisfied |
| Tester physical health | `/tmp/20260519_tester_physical_fresh_100s.logcat` reports `environment_assessment PASS failCount=0 warnCount=0 timeoutCount=0`. | Satisfied |
| BestV sandbox veto | `/tmp/20260519_loadedapk_compat_bestv_sandbox_120s.logcat` has no target-package `BProcessManager: App Died`, no `FATAL EXCEPTION`, no `JNI_ERR`, no `Fatal signal`. | Satisfied |
| BestV real app logic | Sandbox and physical logs both contain `BesTVConfig` / `IqiyiActivity` initialization evidence. | Satisfied |
| Screenshot parity | Latest screenshot comparisons show content parity, not byte-identical parity: Tester content is Apple.com + `ENVDIAG PASS` with status-bar dynamic differences; BestV content is the same installed/return-key page with legacy letterbox and aligned version text scale, with text-edge anti-alias pixel differences. | Strict byte parity not claimed |
| Latest acceptance docs | `docs/v3/LATEST_ACCEPTANCE_STATE.md` records current code/test/device state and the strict screenshot-byte caveat. | Satisfied |

## Current Device Evidence

```text
device=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp
product=dandelion
model=M2006C3LC
```

Sandbox:

```text
/tmp/20260519_loadedapk_compat_tester_sandbox_100s.logcat
/tmp/20260519_loadedapk_compat_tester_sandbox_100s.png
/tmp/20260519_loadedapk_compat_bestv_sandbox_120s.logcat
/tmp/20260519_loadedapk_compat_bestv_sandbox_120s.png
```

Physical:

```text
/tmp/20260519_tester_physical_fresh_100s.logcat
/tmp/20260519_tester_physical_fresh_100s.png
/tmp/20260519_bestv_physical_fresh_120s.logcat
/tmp/20260519_bestv_physical_fresh_120s.png
```

Screenshot hashes:

```text
f18b2153f8efc7f812cf19c878a7e0affc1341b4  /tmp/20260519_loadedapk_compat_tester_sandbox_100s.png
fdbb70ae5714bca1dac5e4f3dc6650c35e3c9898  /tmp/20260519_tester_physical_fresh_100s.png
7b569341437835c829ce66ede9838e364acc130e  /tmp/20260519_loadedapk_compat_bestv_sandbox_120s.png
edf62515482d949cdde43595df3b0200df1df2dc  /tmp/20260519_bestv_physical_fresh_120s.png
```

## Remaining Work Before Marking Strict Acceptance Complete

1. If strict byte-identical screenshot comparison remains mandatory, collect deterministic compositor frames or mask dynamic status-bar pixels and tolerate renderer anti-alias/subpixel differences; do not mark byte-level parity complete from the current evidence alone.
2. Commit only the relevant tracked changes and intentional new files; leave unrelated untracked workspace files untouched.

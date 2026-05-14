# PLAN_v3 Completion Audit

- generated_at: 2026-05-17 08:20 +0800
- plan_file: `docs/v3/PLAN_v3.md`
- plan_sha256: `8ada9db5b0d55a8ff6d25a58725a3fd4560a2c32a8f7ea5c965121a3c227cda2`
- status: `not_complete`
- blocker: ADB is now connected and physical-device runs execute, but PLAN_v3 is still `not_complete`: BestV sandbox capture does not match the direct physical-device screenshot. The latest formal BestV gate has fresh artifacts and passes the `BProcessManager: App Died` veto check, but fails screenshot parity; earlier SIGQUIT/long-window runs also exposed a post-WONT `MethodHandles.Lookup.revealDirect` invalid-jobject crash path.

## Concrete Success Criteria

The objective is complete only when all of the following are true:

1. `Bcore` contains a new `black-binder` subproject that implements Binder-call monitoring for sandboxed apps.
2. The project compiles with Java 11 using:
   - `source "$HOME/.sdkman/bin/sdkman-init.sh"`
   - `sdk use java 11.0.14.1-jbr`
   - `./gradlew assembleBlackBox32Debug`
3. Hook-related method prototypes have been checked against AOSP/official source before adding or changing hooks.
4. The suggested investigation features are represented by concrete artifacts where applicable:
   - syscall/libc/file probing or IO redirection,
   - Binder request interception/proxying,
   - dex dump under host `files/<pkg>`.
5. `script/install-to-device.sh <pkg>` can run the sandbox test packages and produce `/tmp/logcat.log` and `/tmp/screencap.png`.
6. `com.bestv.tv.video.iqy.tjdx` runs in the sandbox without the veto log:
   - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
7. `com.example.tester` still runs normally and displays Apple.com.
8. The BestV sandbox screenshot matches the physical-device BestV screenshot.
9. Failed attempts are recorded under `experience_failure/`, and that directory is reread before new attempts.

## Prompt-to-Artifact Checklist

| Requirement | Evidence | Current result |
| --- | --- | --- |
| New `black-binder` module | `settings.gradle:4` includes `:Bcore:black-binder`; `Bcore/build.gradle:74` depends on `project(':Bcore:black-binder')`; source exists under `Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/`. | Present |
| `black-binder` standalone build/test | Fresh local run on `2026-05-17 06:03 +0800`: `source "$HOME/.sdkman/bin/sdkman-init.sh"`; `sdk use java 11.0.14.1-jbr`; `./gradlew :Bcore:black-binder:assembleDebug :Bcore:black-binder:testDebugUnitTest` returned `BUILD SUCCESSFUL in 1s`; produced `Bcore/black-binder/build/outputs/aar/black-binder-debug.aar`, modified `2026-05-17 06:03:47 +0800`, size `44216` bytes. | Locally satisfied |
| Plan snapshot alignment | Fresh `./script/codex.sh plan-sync-check` run on `2026-05-17 06:02 +0800` reported `current_plan_sha256=8ada9db5b0d55a8ff6d25a58725a3fd4560a2c32a8f7ea5c965121a3c227cda2`, `snapshot_plan_sha256=8ada9db5b0d55a8ff6d25a58725a3fd4560a2c32a8f7ea5c965121a3c227cda2`, and `plan_sync_status=matched`. | Locally satisfied |
| Binder-call monitoring | `BlackBoxBinderMonitor.java` initializes output under `binder_monitor` at lines 49-78, exposes proxy recording at lines 125-140, native record entry points at lines 173-189, and hooks `BinderProxy.transact` at line 318. `BinderMonitorConfig.java:67-79` defaults debug builds to Java/proxy recording with native/ioctl disabled; `BinderMonitorConfig.java:85-97` allows `/data/local/tmp/binder_monitor_config.json` or host `files/binder_monitor/config.json` to override this. `BActivityThread.java:372-380` initializes the monitor, registers `PackageManagerBinderInterceptor`, and enables native/ioctl recording only when the config gates are true. Native binder interception is implemented in `Bcore/src/main/cpp/Hook/BinderHook.cpp`. | Present; native/ioctl monitoring is config-gated and locally test-covered |
| Can compile target flavor | Fresh local run on `2026-05-17 05:52 +0800`: `source "$HOME/.sdkman/bin/sdkman-init.sh`; `sdk use java 11.0.14.1-jbr`; `java -version` reported `openjdk version "11.0.14.1"`; `./gradlew assembleBlackBox32Debug` returned `BUILD SUCCESSFUL in 1s`. | Locally satisfied |
| BlackBox32 debug APK artifact | Fresh local artifact check on `2026-05-17 06:02 +0800` found `app/build/outputs/apk/BlackBox32/debug/app-BlackBox32-debug.apk`, modified `2026-05-17 04:52:56 +0800`, size `7814809` bytes. | Locally satisfied |
| BlackBox32 APK contains Binder monitor code | Fresh APK dex scan on `2026-05-17 06:04 +0800`: `unzip -l app/build/outputs/apk/BlackBox32/debug/app-BlackBox32-debug.apk 'classes*.dex'` found ten dex files; `unzip -p ... | strings | rg ...` found `BlackBoxBinderMonitor`, `BinderMonitorConfig`, and `BinderEvent` in `classes8.dex`, and found app integration strings `PackageManagerBinderInterceptor` and `enableBinderMonitor` in `classes3.dex`. | Locally satisfied |
| BlackBox32 APK contains native hook code | Fresh APK native-lib scan on `2026-05-17 06:05 +0800`: `unzip -l app/build/outputs/apk/BlackBox32/debug/app-BlackBox32-debug.apk 'lib/*/*.so'` found `lib/armeabi-v7a/libblackbox.so` and `lib/armeabi-v7a/libpine.so`; `strings` on `libblackbox.so` found `enableBinderMonitor`, `Native.BpBinder.transact`, `Native.ioctl.BINDER_WRITE_READ`, the `BpBinder::transact` mangled symbol, `UnixFileSystemHook`, `setNativeTerminationShieldPackage`, seccomp shield strings, `openat`, `faccessat`, `readlinkat`, `dladdr`, and proc-shim diagnostics. | Locally satisfied |
| Unit/lint safety checks | Fresh local runs on `2026-05-17 05:52-05:53 +0800`: `./gradlew test` returned `BUILD SUCCESSFUL in 1s`; `./gradlew lint` returned `BUILD SUCCESSFUL in 39s` with existing non-fatal lint issues under the relaxed lint config; `bash -n script/codex.sh script/install-to-device.sh script/snapshot-acceptance-state.sh` printed `bash_n_status=ok`; `git diff --check` printed `diff_check_status=ok`. | Locally satisfied |
| Install/acceptance script source tests | Fresh focused run on `2026-05-17 07:41 +0800`: `source "$HOME/.sdkman/bin/sdkman-init.sh"`; `sdk use java 11.0.14.1-jbr`; `java -version` reported `openjdk version "11.0.14.1"`; `./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.InstallToDeviceScriptSourceTest` returned `BUILD SUCCESSFUL in 1s` with `84 actionable tasks: 1 executed, 83 up-to-date`. This covers script source expectations for device override/fallback, stale artifact cleanup, shared default device, TEST_PACKAGE override, dual-package collection, BestV veto failure, and screenshot mismatch failure. Runtime device execution is now available and was exercised at `2026-05-17 08:12-08:18 +0800`. | Source tests satisfied; runtime gate failing |
| Untracked audit artifact whitespace | Fresh local run on `2026-05-17 06:00 +0800`: `git diff --no-index --check /dev/null <file>` produced no whitespace-error output for `docs/v3/AOSP_HOOK_PROTOTYPE_CHECKS.md`, `docs/v3/COMPLETION_AUDIT.md`, `docs/v3/LATEST_ACCEPTANCE_STATE.md`, and `experience_failure/adb_wireless_debug_unavailable.md`. The command returns `1` for file differences against `/dev/null`, so the checked evidence is the empty check output. | Locally satisfied |
| Local test coverage scope | Binder monitor tests exist under `Bcore/black-binder/src/test/java/top/niunaijun/blackbox/binder/`, including event serialization, config parsing/defaults, BinderProxy short-circuiting, payload summaries, native/ioctl event recording, proxy catalog mapping, parcel token tracking, and crash ring-buffer behavior. Integration/source tests in `Bcore/src/test/java/top/niunaijun/blackbox/core/` cover `BActivityThreadBinderMonitorSourceTest`, `PackageManagerBinderInterceptorSourceTest`, `NativeCoreDexDumpSourceTest`, `NativeFileHookSourceTest`, `RuntimeHookSourceTest`, `Seccomp*SourceTest`, and `InstallToDeviceScriptSourceTest` checks for required packages, veto log failure, and screenshot mismatch failure. These tests were included in the fresh `./gradlew test` run above. | Static/JVM coverage only; not a substitute for physical BestV/tester validation |
| AOSP prototype checks for hooks | `docs/v3/AOSP_HOOK_PROTOTYPE_CHECKS.md` records fresh online checks against `android.googlesource.com` for hook targets added or changed during `PLAN_v3` work: `Parcel`, `BinderProxy`, `Runtime.exec`, `ProcessBuilder.start`, `ProcessImpl.start`, `UnixFileSystem` including the API 35 `canonicalize0(String, boolean)` branch, `ActivityThread.installProvider`, `Application.attach`, `ServiceManager`, `ActivityThread` identity methods, `ContextImpl` data-dir accessors, Java/process termination helpers, `NetworkInterface.getHardwareAddress`, Wi-Fi manager/info fields, native Binder/libbinder/ioctl structures, ART JIT/CheckJNI/sigchain, native libc file/signal/termination wrappers, seccomp/prctl/signal-mask hooks, JNI function-table slots, and `VMClassLoader.findLoadedClass`. The document explicitly does not claim a complete audit of every historical proxy already present in the project. | Satisfied for PLAN_v3-added/changed hooks; any future hook changes require a fresh check |
| Dex dump to host `files/<pkg>` | `NativeCore.java:131-140` writes dump output under `BlackBoxCore.getContext().getFilesDir()/packageName`; `BActivityThread.java:405` calls `NativeCore.dumpDex(application.getClassLoader(), packageName)` after application creation. Fresh APK dex scan on `2026-05-17 07:31 +0800` found the Java call-chain in `classes3.dex`: `BActivityThread`, `NativeCore`, and `dumpDex`. The native `libblackbox.so` is stripped enough that `strings`/`readelf` did not expose a `dumpDex` symbol name, so the native side remains source/build/test evidenced rather than symbol-name evidenced. | Present |
| File/proc/runtime environment simulation | Artifacts include `RuntimeExecProxy` installed from `HookManager.java:56,92`, `ContextCompat.fixVirtual` calls in `BActivityThread.java:355,397,404`, native file/proc hooks in `NativeFileHook.cpp`, seccomp install through `NativeCore.java:90-95` and `BActivityThread.java:352`, and ABI handling in `PackageManagerCompat`/`AbiUtils`. | Present but requires physical validation |
| `install-to-device.sh <pkg>` generates `/tmp/logcat.log` and `/tmp/screencap.png` | `script/install-to-device.sh:32-35` reads the first positional argument into `PKG` and defaults to `com.bestv.tv.video.iqy.tjdx` when omitted; line 38 clears `/tmp/logcat.log` and `/tmp/screencap.png`; line 43 writes logcat to `/tmp/logcat.log`; line 44 launches BlackBox with `--es TEST_PACKAGE "$PKG"`; line 46 writes screencap to `/tmp/screencap.png`. Fresh device runs at `2026-05-17 08:12-08:14 +0800` produced `/tmp/logcat.log` and `/tmp/screencap.png` for both BestV and tester. | Runtime interface verified |
| Default test package configuration | Fresh local check on `2026-05-17 06:01 +0800`: `docs/test_package_name` contains `com.bestv.tv.video.iqy.tjdx`; `script/codex.sh:35-48` reads that file when `TEST_PACKAGE` is unset and rejects missing/empty values; `docs/v3/PLAN_v3.md:30-32` names BestV and tester as the package options. | Locally aligned; physical runs now execute |
| BestV sandbox run | Required package: `com.bestv.tv.video.iqy.tjdx`; `script/codex.sh:464-483` runs per-package collection with isolated artifact paths; `script/codex.sh:492-500` includes BestV and tester in `collect-required-packages`. Fresh artifacts were collected at `2026-05-17 08:16 +0800` under `/tmp/blackbox_bestv_*`. The formal gate reports `veto_status=passed` but `screenshot_status=failed`. | Runtime failing screenshot parity |
| Tester sandbox run | Required package: `com.example.tester`; `script/codex.sh:464-483` runs per-package collection with isolated artifact paths; `script/codex.sh:492-500` includes tester after BestV and preserves nonzero status if either package fails. Fresh artifacts were collected at `2026-05-17 08:18 +0800` under `/tmp/blackbox_tester_*`. Logs show `ProxyActivity$P0` visible and SurfaceFlinger screenshots targeting `com.example.tester/com.example.tester.MainActivity#0`, but exact screenshot hash parity still fails. | Runtime launches; exact screenshot gate still failing |
| Required package artifact freshness | Fresh per-package artifacts exist for both `/tmp/blackbox_bestv_*` and `/tmp/blackbox_tester_*`: manifest, sandbox log, exit-info, getprop, real-device log, real-device exit-info, real-device getprop, sandbox screenshot, and real-device screenshot. | Fresh but failing gate |
| Veto log absence | `script/codex.sh:410-417` checks `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`. Latest formal BestV artifact check reports `veto_status=passed`. A previous long SIGQUIT probe at `2026-05-17 08:02 +0800` did hit this veto and is recorded separately in `experience_failure/bestv_sigquit_probe_revealed_invalid_jobject_veto.md`. | Latest formal run passed veto; historical veto remains root-cause evidence |
| Screenshot parity | `script/codex.sh:420-431` requires `cmp -s "$SCREENSHOT_FILE" "$REAL_SCREENSHOT_FILE"`. Latest BestV formal hashes differ: sandbox `29bf24e4...`, real `54223c31...`. Latest tester formal hashes also differ: sandbox `fa46545c...`, real `1e0ebc51...`. | Failing |
| Latest acceptance state | `docs/v3/LATEST_ACCEPTANCE_STATE.md` generated at `2026-05-17 08:21:12 +0800`, `acceptance_check_exit_code: 1`, `veto_status=passed`, `acceptance_status=failed_screenshot` for BestV per-package artifacts. | Failed screenshot |
| Failure records reread | Latest reread command wrote `/tmp/experience_failure_reread.log` with 1541 lines before the physical collection. Additional post-collection failures were recorded in `experience_failure/bestv_appcompat_probe_clear_still_blank_ui.md` and `experience_failure/tester_exact_screenshot_hash_mismatch_after_visible_activity.md`. | Satisfied for current attempt |

## Latest Physical Retest Evidence

Current device:

```text
adb devices -l
adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp device product:dandelion model:M2006C3LC device:dandelion transport_id:1

./script/codex.sh preflight-check
preflight_java11=ok
preflight_gradlew=present
preflight_gradle_probe=ok
preflight_adb=ok
preflight_status=ready
```

Formal package collection was executed:

```text
DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp ./script/codex.sh collect-required-packages
```

BestV result:

```text
artifact_ok=/tmp/blackbox_bestv_artifacts_manifest.txt
artifact_ok=/tmp/blackbox_bestv_logcat.txt
artifact_ok=/tmp/blackbox_bestv_screenshot.png
artifact_ok=/tmp/blackbox_bestv_real_logcat.txt
artifact_ok=/tmp/blackbox_bestv_real_screenshot.png
verify_status=success
veto_status=passed
screenshot_status=failed
29bf24e4b7f772de6a6c356d9487d2992bb57a69b7fbfb3e45e71a0b27a9c538  /tmp/blackbox_bestv_screenshot.png
54223c31f7f7289bd50d0822e7289ec808564cb4ddf01313dc1b01878b78d087  /tmp/blackbox_bestv_real_screenshot.png
acceptance_status=failed_screenshot
```

BestV sandbox log reaches virtual activity creation but not the app's own UI lifecycle markers:

```text
08:16:01.136 NativeCore: FindClass class=com/bestv/iptv/tv/IqiyiActivity ...
08:16:01.277 AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity
08:16:01.519 NativeCore: jni field lookup ... TelnetCommand name=WONT ...
08:16:01.527 NativeCore: post-static-int SIGSEGV probe installed jiaguBase=...
```

Direct physical BestV log reaches the expected UI path:

```text
08:16:28.819 IqiyiActivity: enter onCreate
08:16:29.265 IqiyiActivity: leave onCreate.
08:16:29.460 Activity_windows_visible ... IqiyiActivity
08:16:33.557 IqiyiActivity: onShowRealUi
08:16:33.557 IqiyiActivity: afterLoaded do
```

Tester result:

```text
artifact_ok=/tmp/blackbox_tester_artifacts_manifest.txt
artifact_ok=/tmp/blackbox_tester_logcat.txt
artifact_ok=/tmp/blackbox_tester_screenshot.png
artifact_ok=/tmp/blackbox_tester_real_logcat.txt
artifact_ok=/tmp/blackbox_tester_real_screenshot.png
verify_status=success
veto_status=passed
screenshot_status=failed
fa46545c24d48504efbb1e6f571cdf40a2d50497673e8bdb35033963e9972852  /tmp/blackbox_tester_screenshot.png
1e0ebc51391497dca8769dba3dc8f42d4d199260111f8d529314d73fcc2fdd9c  /tmp/blackbox_tester_real_screenshot.png
acceptance_status=failed_screenshot
```

Tester sandbox is not obviously broken: logs show `Activity_windows_visible` for
`ProxyActivity$P0` and repeated SurfaceFlinger screenshots targeting
`com.example.tester/com.example.tester.MainActivity#0`. The exact hash mismatch
still needs a later stable visual check, but BestV remains the primary blocker.

## Historical ADB Blocking Evidence (superseded)

Current device gate output:

```text
adb devices -l
List of devices attached

adb mdns services
List of discovered mdns services
adb-c253b76f-pgzbCA (2)	_adb-tls-connect._tcp	192.168.127.151:37977
adb-c253b76f-pgzbCA	_adb-tls-connect._tcp	192.168.127.151:39311

adb -s adb-c253b76f-pgzbCA._adb-tls-connect._tcp get-state
error: device 'adb-c253b76f-pgzbCA._adb-tls-connect._tcp' not found

adb connect 192.168.127.151:39311
failed to connect to '192.168.127.151:39311': Connection refused

adb connect 192.168.127.151:37977
failed to connect to '192.168.127.151:37977': Connection refused

Windows adb.exe devices -l
List of devices attached

Windows adb.exe mdns services
List of discovered mdns services

Windows adb.exe -s adb-c253b76f-pgzbCA._adb-tls-connect._tcp get-state
error: device 'adb-c253b76f-pgzbCA._adb-tls-connect._tcp' not found

Windows adb.exe connect 192.168.127.151:39311
failed to connect to '192.168.127.151:39311': Connection refused

Windows adb.exe connect 192.168.127.151:37977
failed to connect to '192.168.127.151:37977': Connection refused

ping -c 1 -W 2 192.168.127.151
1 packets transmitted, 0 received, 100% packet loss

nc -vz -w 2 192.168.127.151 39311
nc: connect to 192.168.127.151 port 39311 (tcp) failed: Connection refused

nc -vz -w 2 192.168.127.151 37977
nc: connect to 192.168.127.151 port 37977 (tcp) failed: Connection refused

ip route get 192.168.127.151
192.168.127.151 dev eth1 src 192.168.127.61 uid 1000

ip neigh show 192.168.127.151
192.168.127.151 dev eth1 lladdr 22:56:bf:fa:2e:c1 DELAY

./script/codex.sh preflight-check
preflight_adb=failed
error: device 'adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp' not found
preflight_status=blocked

ping -c 1 192.168.127.148
64 bytes from 192.168.127.148: icmp_seq=1 ttl=64 time=57.2 ms

nc -vz 192.168.127.148 5555
nc: connect to 192.168.127.148 port 5555 (tcp) failed: Connection refused

scan_range=30000-49999
no open ports reported

scan_range=1-65535
open_port_count=0

WSL host ADB server check:
10.255.255.254:5037 refused
ADB_SERVER_SOCKET=tcp:10.255.255.254:5037 adb devices -l failed with connection refused
127.0.0.1:5037 is only the local Linux adb server and reports no devices

USB/usbip fallback check:
lsusb returned no devices
/dev/bus/usb does not exist
usbip and usbipd are not installed

Direct Windows adb.exe check:
C:\Users\gam20\AppData\Local\Android\Sdk\platform-tools\adb.exe devices -l reports no devices
C:\Users\gam20\AppData\Local\Android\Sdk\platform-tools\adb.exe mdns services reports no services
C:\Users\gam20\AppData\Local\Android\Sdk\platform-tools\adb.exe connect 192.168.127.148:5555 failed with Connection refused

./script/codex.sh collect-required-packages
preflight_adb=failed
error: device 'adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp' not found
collect_required_package_failed=com.bestv.tv.video.iqy.tjdx
collect_required_package_failed=com.example.tester
collect_required_packages_status=failed
```

Current acceptance gate output is recorded in `docs/v3/LATEST_ACCEPTANCE_STATE.md`:

```text
artifact_missing=/tmp/blackbox_screenshot.png
artifact_missing=/tmp/blackbox_real_screenshot.png
verify_status=failed
acceptance_status=blocked
```

## Missing Before Completion

Do not mark the goal complete until fresh physical-device evidence exists for:

1. `./script/codex.sh collect-required-packages` exit code 0.
2. `./script/codex.sh acceptance-check` exit code 0 for the relevant artifact set.
3. Fresh `/tmp/blackbox_bestv_*` and `/tmp/blackbox_tester_*` artifacts.
4. No BestV veto log in the fresh sandbox log.
5. BestV sandbox screenshot equal to physical-device screenshot.
6. Tester screenshot/log evidence showing Apple.com works and no sandbox regression remains.
7. Post-WONT `revealDirect` / `GetObjectClass` path either reaches the direct-run lifecycle markers or is replaced by a narrower proven root cause.

## Next Commands

```bash
adb devices -l
find experience_failure -maxdepth 1 -type f -print -exec sed -n '1,260p' {} \; > /tmp/experience_failure_reread.log
./script/codex.sh collect-required-packages
ARTIFACT_MANIFEST_FILE=/tmp/blackbox_bestv_artifacts_manifest.txt \
LOG_FILE=/tmp/blackbox_bestv_logcat.txt \
EXIT_INFO_FILE=/tmp/blackbox_bestv_exit_info.txt \
GETPROP_FILE=/tmp/blackbox_bestv_getprop.txt \
REAL_LOG_FILE=/tmp/blackbox_bestv_real_logcat.txt \
REAL_EXIT_INFO_FILE=/tmp/blackbox_bestv_real_exit_info.txt \
REAL_GETPROP_FILE=/tmp/blackbox_bestv_real_getprop.txt \
SCREENSHOT_FILE=/tmp/blackbox_bestv_screenshot.png \
REAL_SCREENSHOT_FILE=/tmp/blackbox_bestv_real_screenshot.png \
./script/codex.sh acceptance-check
```

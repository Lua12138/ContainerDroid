# Latest Acceptance State

- generated_at: 2026-05-19 00:50:00 +0800
- package_from_docs_test_package_name: com.bestv.tv.video.iqy.tjdx
- plan_file: docs/v3/PLAN_v3.md
- plan_file_sha256: 224395e5a32d795ecc66f7ba0104f5d5ad06bff72bed292c7cf6514749d65fd4
- plan_file_mtime: 2026-05-18 22:03:21.272789474 +0800
- status: runtime_content_parity_pass_strict_screenshot_bytes_not_claimed

## Summary

The review-report remediation path is build/test green and both required packages run in the sandbox on the connected device.

- `com.example.tester` now passes in both physical and sandbox runs with `failCount=0`, `warnCount=0`, `timeoutCount=0`.
- `com.bestv.tv.video.iqy.tjdx` now reaches protected app logic in sandbox, dumps the real payload dex, uses the generic legacy-aspect proxy, and does not hit the target-package `BProcessManager: App Died` veto.
- Target `CompatibilityInfo` is now propagated into `LoadedApk`, `ActivityThread.AppBindData`, and `LaunchActivityItem`; this fixes the BestV legacy resource/display scaling discrepancy observed in the version text.
- The latest screenshots are content-consistent but not byte-identical. Tester differs only in status-bar dynamic time/network/battery pixels; BestV differs in text-edge/anti-alias pixels after matching the same page, letterbox, and version text scale. Therefore strict byte-level screenshot parity is not claimed.

## Local Verification

```text
./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.BActivityThreadAppComponentFactorySourceTest.bindAndLaunchUseTargetCompatibilityInfoForLegacyDisplayScaling
BUILD SUCCESSFUL

./gradlew :Bcore:black-binder:testDebugUnitTest :Bcore:testDebugUnitTest assembleBlackBox32Debug
BUILD SUCCESSFUL
```

## Device

```text
adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp
product:dandelion model:M2006C3LC device:dandelion
```

## Sandbox Artifacts

| Package | Runtime | Log | Screenshot | Result |
| --- | ---: | --- | --- | --- |
| `com.example.tester` | 100s | `/tmp/20260519_loadedapk_compat_tester_sandbox_100s.logcat` | `/tmp/20260519_loadedapk_compat_tester_sandbox_100s.png` | `environment_assessment PASS failCount=0 warnCount=0 timeoutCount=0`; `proc_maps_summary blackboxPathCount=0 writableExecutableCount=0`; `network_interface_summary interfaceCount=3 hardwareAddressCount=0 interfaceNames=dummy0,wlan0,lo`; Apple.com homepage visible with `ENVDIAG PASS`. |
| `com.bestv.tv.video.iqy.tjdx` | 120s | `/tmp/20260519_loadedapk_compat_bestv_sandbox_120s.logcat` | `/tmp/20260519_loadedapk_compat_bestv_sandbox_120s.png` | Runs with `LegacyAspectProxyActivity$P0`; no target-package `BProcessManager: App Died`; no `FATAL EXCEPTION`/`JNI_ERR`/`Fatal signal`; reaches `BesTVConfig` and `IqiyiActivity`; dumps payload dex; BestV page content matches physical. |

## Physical Artifacts

| Package | Runtime | Log | Screenshot | Result |
| --- | ---: | --- | --- | --- |
| `com.example.tester` | 100s | `/tmp/20260519_tester_physical_fresh_100s.logcat` | `/tmp/20260519_tester_physical_fresh_100s.png` | `environment_assessment PASS failCount=0 warnCount=0 timeoutCount=0`; network summary matches sandbox; Apple.com content matches. |
| `com.bestv.tv.video.iqy.tjdx` | 120s | `/tmp/20260519_bestv_physical_fresh_120s.logcat` | `/tmp/20260519_bestv_physical_fresh_120s.png` | Runs and reaches the same app page/logical state as sandbox; no same-class fatal signature in the checked critical patterns. |

## Key Evidence

```text
/tmp/20260519_loadedapk_compat_tester_sandbox_100s.logcat:
  proc_maps_summary blackboxPathCount=0 writableExecutableCount=0
  network_interface_summary interfaceCount=3 upCount=3 loopbackCount=1 hardwareAddressCount=0 interfaceNames=dummy0,wlan0,lo
  environment_assessment verdict=PASS failCount=0 warnCount=0 timeoutCount=0

/tmp/20260519_loadedapk_compat_bestv_sandbox_120s.logcat:
  ActivityStack ... proxyActivity: top.niunaijun.blackbox.proxy.LegacyAspectProxyActivity$P0
  BesTVConfig: init
  NativeCore dumpDex ... sha1=81069652080f469c9417b3928b773983684858ee
  IqiyiActivity: enter onCreate
  IqiyiActivity: leave onCreate.
```

Critical-pattern scan:

```text
rg "BProcessManager: App Died: com\\.bestv\\.tv\\.video\\.iqy\\.tjdx|FATAL EXCEPTION|JNI_ERR|Fatal signal|ANR in" /tmp/20260519_loadedapk_compat_bestv_sandbox_120s.logcat
# no output
```

Screenshot SHA1 and diff:

```text
f18b2153f8efc7f812cf19c878a7e0affc1341b4  /tmp/20260519_loadedapk_compat_tester_sandbox_100s.png
fdbb70ae5714bca1dac5e4f3dc6650c35e3c9898  /tmp/20260519_tester_physical_fresh_100s.png
content_match=Apple.com homepage + ENVDIAG PASS; dynamic status-bar pixels differ

7b569341437835c829ce66ede9838e364acc130e  /tmp/20260519_loadedapk_compat_bestv_sandbox_120s.png
edf62515482d949cdde43595df3b0200df1df2dc  /tmp/20260519_bestv_physical_fresh_120s.png
content_match=same BestV installed/return-key page, legacy letterbox, version text scale aligned; text-edge pixels differ
```

Payload dex:

```text
cookie_81069652080f469c9417b3928b773983684858ee.dex
```

## Current Non-Claimed Item

`script/codex.sh acceptance-check` historically enforces byte-identical screenshot comparison. The latest evidence proves runtime health and content parity, but not byte-identical screenshots. If byte-identical screenshots remain the formal gate, the comparison tool must ignore status-bar dynamic pixels and tolerate renderer anti-alias/subpixel differences, or capture a deterministic compositor frame.

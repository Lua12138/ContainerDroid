# Curated large /proc/mounts template regresses BestV before getprop

## Attempt

Changed the default `RuntimeExecProxy` response for `cat /proc/mounts` from the
previous compact nine-line template to a package-agnostic curated Android mount
table. The new table was static, sanitized, larger than 4 KiB, and intentionally
excluded known sandbox, target, root, and debug-transport artifacts such as
BlackBox paths, target package names, Magisk, debug ramdisk, and adb functionfs.

This was not target-package gated and kept full dynamic `/proc/mounts` replay
behind the existing diagnostic switches.

## Evidence

- Source/build gates passed:
  - `RuntimeExecProxySourceTest`
  - `NoTargetHardcodedInterceptionSourceTest`
  - `assembleBlackBox32Debug`
- Tester sandbox passed:
  - `/tmp/20260517_tester_detector5_sandbox_curated_mounts.logcat`
  - `/tmp/20260517_tester_detector5_sandbox_curated_mounts.png`
  - `environment_assessment`: `PASS`, `failCount=0`, `warnCount=3`
  - `cat /proc/mounts` shell sample grew from 856 bytes to 9653 bytes.
- BestV regressed against the PLAN_v3 one-vote veto:
  - `/tmp/20260517_bestv_curated_mounts.logcat`
  - `/tmp/20260517_bestv_curated_mounts.png`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx` repeated.
- The failure moved earlier than the compact template baseline:
  - compact baseline reached `BesTVConfig` and sometimes `Runtime.exec getprop`;
  - curated high-cardinality template died after `RuntimeExecProxy:
    stage=sanitized command=cat /proc/mounts`, before `getprop` and before
    `AppContext onCreate`.
- The relevant protected-app code path is generic storage enumeration:
  - `com.bestv.ott.utils.StorageUtils.getUsbDirectory()`
  - `com.bestv.ott.utils.CommandUtils.doExec("cat /proc/mounts")`
  - decompiled code iterates every returned mount line.

## Conclusion

Do not use a high-cardinality Android-like static mount table as the default
`Runtime.exec("cat /proc/mounts")` response. Even when sanitized and
package-agnostic, the extra line count/shape changes BestV timing or branch
behavior and causes an earlier process death.

The next safer direction is a compact, stable, package-agnostic mount template
with direct-like option richness and byte size, not full dynamic replay and not
a broad many-line inventory.

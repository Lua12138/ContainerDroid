# DexFile.loadDex seccomp after return is too late for BestV

## Attempt

Moved the generic `DexDumpProxy` seccomp trigger from
`DexFile.loadDex` `beforeCall` to `afterCall`:

1. `beforeCall` only dumps string path arguments.
2. Original `DexFile.loadDex` runs without the seccomp shield.
3. `afterCall` dumps the returned `DexFile` cookies.
4. Only after that, seccomp is installed.

This was intended to test whether installing seccomp before the protected
loader's standalone `DexFile.loadDex` perturbed Jiagu's class-loader /
`entryRunApplication` initialization.

## Evidence

- Regression test was updated with a RED/GREEN cycle:
  - RED: `DexNotifyDumpSourceTest.dexFileLoadDexInstallsSeccompOnlyAfterStandaloneDexLoadReturns`
    failed while diagnostics still said "before".
  - GREEN: same test passed after updating diagnostics.
- Build/test gate passed:
  - `./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.DexNotifyDumpSourceTest --tests top.niunaijun.blackbox.core.SeccompSignalDeliverySourceTest --tests top.niunaijun.blackbox.core.SeccompSignalMaskSourceTest assembleBlackBox32Debug`
- Tester still rendered Apple.com:
  - `/tmp/20260517_122x_tester_after_loaddex_aftercall.logcat`
  - `/tmp/20260517_122x_tester_after_loaddex_aftercall.png`
- BestV failed earlier than the previous "beforeCall seccomp" run:
  - `/tmp/20260517_122x_bestv_after_loaddex_aftercall.logcat`
  - `/tmp/20260517_122x_bestv_after_loaddex_aftercall.png`
  - Repeated `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`.
  - Process exited with `signal 9 (Killed)`.
  - The `DexFile.loadDex` hook entered, then ART logged:
    `Opening an oat file without a class loader` and
    `Skipping duplicate class check due to unsupported classloader`.
  - No `DexDumpProxy: seccomp shield requested after dalvik.system.DexFile.loadDex`
    appeared.
  - No payload cookie around 4,876,632 bytes was dumped in this run.

## Conclusion

Installing seccomp only after `DexFile.loadDex` returns is too late: the
protected native loader kills the process before `afterCall` executes.

This supports a narrower model:

- The real payload is obtained during the standalone `DexFile.loadDex` /
  protected native loader window.
- Without a raw native termination shield, the process is killed before the
  returned `DexFile` can be inspected.
- With the earlier full seccomp shield, the raw kill is suppressed and payload
  dex can be dumped, but the protected loader still does not initialize the
  real `entryRunApplication` / `AppContext` path.

Do not retry "install seccomp after `DexFile.loadDex` returns" as the acceptance
fix. The next useful direction is to identify the sandbox feature checked inside
the protected loader window, especially file/proc/maps and native-loader
identity differences, or to find a lower-side raw termination shield that does
not expose the full seccomp state before the loader completes.

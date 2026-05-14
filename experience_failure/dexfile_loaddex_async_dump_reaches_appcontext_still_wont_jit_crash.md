# DexFile.loadDex async dump reaches AppContext but still fails in WONT/JIT path

## Attempt

Changed the generic `DexDumpProxy` handling of deprecated
`dalvik.system.DexFile.loadDex(String, String, int)` so path-argument dumps and
returned-`DexFile` cookie dumps are scheduled on a delayed worker instead of
running synchronously on Jiagu's loader call stack.

This was package-agnostic and did not map `entryRunApplication` to any target
class.

## Evidence

- RED/GREEN source test:
  - `DexNotifyDumpSourceTest.dexFileLoadDexSeccompShieldIsDiagnosticOptInToAvoidDefaultLoaderSurface`
- Broader source/build gate passed:
  - `:Bcore:testDebugUnitTest --tests DexNotifyDumpSourceTest --tests DexDumpProxySourceTest`
  - `assembleBlackBox32Debug`
- Tester remained functional:
  - `/tmp/20260517_tester_async_loadDex_dump.logcat`
  - `/tmp/20260517_tester_async_loadDex_dump.png`
  - screenshot showed the expected Apple.com page.
- BestV progressed past the previous early loader kill:
  - `/tmp/20260517_bestv_async_loadDex_dump.logcat`
  - `/tmp/20260517_bestv_async_loadDex_dump.png`
  - `DexFile.loadDex`
  - `Opening an oat file without a class loader`
  - `AppContext attachBaseContext`
  - `AppContext onCreate`
- Real payload dumps appeared again without default seccomp:
  - `cookie_*_b9*_4876632_0dfc4292.dex`
  - these are the ~4.8 MB payload dex files that contain `com.bestv.iptv.tv`
    classes.

## Remaining failure

The run still hit the one-vote veto:

```text
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
Zygote: Process ... exited due to signal 11 (Segmentation fault)
```

The next visible fatal path is the previously known post-bootstrap activity
path:

```text
AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity
java.lang.NoSuchFieldError: no "I" field "WONT" in class "Lorg/apache/commons/net/telnet/TelnetCommand;"
F DEBUG: #00 pc ... libart.so art::jit::JitCodeCache::SweepRootTables(...)
```

## Conclusion

The async `DexFile.loadDex` dump is a useful generic improvement and should stay
if it remains side-effect free: it avoids perturbing the protected loader enough
to recover `AppContext` and the real payload dex. It is not an acceptance fix by
itself. Do not retry loadDex async scheduling alone; the next work should return
to the already identified `IqiyiActivity.onCreate` WONT compatibility and ART
JIT root-table corruption path.

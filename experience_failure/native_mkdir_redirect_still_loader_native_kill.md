# Native mkdir/mkdirat redirection did not resolve early Jiagu loader kill

## Attempt

Added generic native redirection for app-private directory creation through
`mkdir`, `mkdirat`, and the corresponding raw syscall cases. The goal was to
cover protectors that create `/data/data/<pkg>` subdirectories natively before
Java-side file APIs are visible, especially Jiagu paths such as `.jiagu`,
`qihooCrash`, or `nativeCrash`.

This was package-agnostic and did not hardcode BestV classes, methods, or
package-specific branches.

## Evidence

- Targeted source test passed:
  - `NativeFileHookSourceTest.nativeFileHooksRedirectNativeDirectoryCreationForAppData`
- Broader source/build gate passed:
  - `:Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.NativeFileHookSourceTest`
  - `assembleBlackBox32Debug`
- Tester remained functional:
  - `/tmp/20260517_tester_native_mkdir_redirect.logcat`
  - `/tmp/20260517_tester_native_mkdir_redirect.png`
  - screenshot showed the expected Apple.com page.
- BestV still failed:
  - `/tmp/20260517_bestv_native_mkdir_redirect.logcat`
  - `/tmp/20260517_bestv_native_mkdir_redirect.png`
  - repeated `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 9 (Killed)`
- The observed `native mkdir probe` hits were for the host dex dump output
  directory under `top.niunaijun.blackboxa32/files/com.bestv.tv.video.iqy.tjdx`
  and returned `EEXIST`; no decisive native `mkdir` hit for
  `/data/data/com.bestv.tv.video.iqy.tjdx`, `.jiagu`, `qihooCrash`, or
  `nativeCrash` appeared in the failure window.
- The decisive sequence remained:
  - Jiagu candidate class-loader probes fail on ordinary `PathClassLoader`;
  - `dalvik.system.DexFile.loadDex` runs;
  - the dex dumper reads `/proc/self/maps` through the proc shim;
  - the process is killed before `AppContext attachBaseContext`.

## Conclusion

The native `mkdir`/`mkdirat` redirection is a valid generic IO coverage
improvement and can remain if it has no side effects, but it is not the current
acceptance fix. Do not retry native directory creation redirection as the next
standalone solution.

The next useful direction is to investigate the Jiagu loader window around
`DexFile.loadDex`: avoid heavy synchronous dex-cookie dumping on the protected
loader's call stack, and verify whether the real class-loader/cookie mutation
completes when the dumper is made less observable.

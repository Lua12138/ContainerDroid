# Dynamic dex dump: broad DexFile hook regression

## Attempt

Added a generic `DexDumpProxy` that hooked all `BaseDexClassLoader`,
`InMemoryDexClassLoader`, and `dalvik.system.DexFile` constructors plus selected
`DexFile.loadDex/openDexFile/openInMemoryDexFiles` methods. The goal was to dump
runtime-loaded file and `ByteBuffer` dex payloads into host `files/<pkg>`.

This was package-agnostic and did not contain target app hardcoding.

## Evidence

- Local build and source tests passed:
  - `./gradlew :Bcore:testDebugUnitTest assembleBlackBox32Debug`
- Device tester run after this broad hook set:
  - `/tmp/20260517_100x_tester_dexdump_publish_fix.logcat`
  - `/tmp/20260517_100x_tester_dexdump_publish_fix.png`
  - logcat sha256:
    `7b1e120712665c3a6af371f4fcdccc704eabd3b40e3fb4bfe3f68fac55188729`
  - screenshot sha256:
    `7296fe5807c1b16b388e404f49e8ebfe215796bed548309c169a729113a4e1c9`
- The hooks installed, including internal/native `DexFile` methods such as
  `openDexFileNative` and `openInMemoryDexFilesNative`.
- Tester sandbox did not reach a visible `ProxyActivity$P0` / virtual
  `MainActivity` state in the captured window. Multiple `:p0` processes were
  started at roughly 10 second intervals.
- No new dex files were present under:
  - `files/com.example.tester`
  - `files/com.bestv.tv.video.iqy.tjdx`

## Conclusion

Do not keep the broad `DexFile` constructor/native-method hook set. Hooking ART
core dex loading internals this broadly is too invasive and regresses the
baseline tester launch before it proves useful dump coverage.

The safer next attempt tried a narrower generic surface:

- hook `BaseDexClassLoader` constructors after construction and dump their path
  list file sources;
- hook `InMemoryDexClassLoader` constructors and dump `ByteBuffer` arguments;
- do not hook `DexFile.openDexFileNative`, `openInMemoryDexFilesNative`, or
  every `DexFile` constructor until a narrower reproduction proves this is safe.

## 2026-05-17 narrow HookManager retry

The broad `DexFile` hooks were removed and only `BaseDexClassLoader` plus
`InMemoryDexClassLoader` constructors remained. The hooks were still installed
from global `HookManager.init()`.

Device tester run:

- `/tmp/20260517_100x_tester_narrow_dexdump_publish_fix.logcat`
- `/tmp/20260517_100x_tester_narrow_dexdump_publish_fix.png`
- logcat sha256:
  `f0294552f97501b340012d7c0edc4192f851a05d92638df50ef71e2f3f4add48`
- screenshot sha256:
  `29edb71a52ee6663953127953a1ab4d259325d87d1d915d76575bf8c7cd47837`

This still regressed tester sandbox launch: no captured `ProxyActivity$P0`
visible state, no dex files under `files/com.example.tester`, and repeated
`:p0` starts. Therefore even the narrow class-loader hook should not be
installed globally from `HookManager.init()`.

Next safer direction: install the dynamic dex dump hook only after
`BActivityThread.bindApplication(...)` has selected a virtual package/process,
not during global BlackBox hook initialization. Keep the existing one-shot
`NativeCore.dumpDex(application.getClassLoader(), packageName)` for baseline
file-copy dumps.

## 2026-05-17 process memory maps scan before providers

Added a generic native `/proc/self/maps` + `/proc/self/mem` scanner and invoked
it before virtual `installProviders(...)`, trying to catch decrypted dex bytes
before BestV kills the sandbox process.

Device tester run:

- `/tmp/20260517_103x_tester_process_mem_dexdump.logcat`
- `/tmp/20260517_103x_tester_process_mem_dexdump.png`
- logcat sha256:
  `2343463e102dfeb9a3457e4d8d58843e50cd64bfab820ae1b9c19febf4276e00`
- screenshot sha256:
  `af5e8f0e848f9733bbc3887c0621d075ac6a1b72b71fedf3083980cee3cbd1d4`

Evidence:

- The scanner read about 447 MB and found no new dex:
  `dumpProcessDex complete dumped=0 scanned=447401984`.
- Because the scan ran synchronously before providers, tester spent the capture
  window on a black screen instead of the Apple.com WebView, regressing the
  baseline app.
- The attempt did not improve dump coverage.

Conclusion:

Do not run a broad synchronous process-memory maps scan on the app main thread
before provider/application startup. If process-memory scanning is retried, it
must be highly targeted, asynchronous, or driven by a proven ART DexFile/cookie
source. The next safer direction is to dump ART `DexFile` cookies from the
class loader: AOSP `art::DexFile` exposes `begin_`/`size_`, so this can capture
in-memory dex when a packer swaps dex cookies without requiring a full address
space scan.

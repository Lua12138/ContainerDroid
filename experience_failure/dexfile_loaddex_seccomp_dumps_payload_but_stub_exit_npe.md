# DexFile.loadDex seccomp trigger dumps payload but is not an acceptance fix

## Attempt

Installed the existing seccomp syscall shield from the generic public
`dalvik.system.DexFile.loadDex(String, String, int)` hook before the original
deprecated standalone dex load runs. This was package-agnostic and did not
contain target-app hardcoding.

## Evidence

- Tester remained visually healthy and showed the Apple.com page:
  - `/tmp/20260517_120x_tester_dexload_seccomp.logcat`
  - `/tmp/20260517_120x_tester_dexload_seccomp.png`
- BestV no longer hit the immediate pre-attach SIGKILL loop after the standalone
  dex load; the hook installed seccomp at the intended boundary:
  - `DexDumpProxy: seccomp shield requested before dalvik.system.DexFile.loadDex`
  - `BlackBoxSeccomp: seccomp shield installed`
- This produced a new payload cookie dump:
  - `cookie_18860_3_b9bde000_4876632_0dfc4292.dex`
- JADX confirmed that the new 4.8 MB cookie dump contains the real BestV code,
  including:
  - `com.bestv.iptv.tv.AppContext`
  - `com.bestv.iptv.tv.IqiyiActivity`
  - `com.bestv.ott.config.env.OttContext`
  - `com.bestv.ott.config.adapter.SysEnvAdapter`
- It is still not an app acceptance fix. After seccomp blocked the termination
  path, `com.stub.StubApp.attachBaseContext` called `System.exit(1)` and that
  Java exit was blocked:
  - `RuntimeExitProxy: blocked java.lang.System.exit(1) ... com.stub.StubApp.attachBaseContext:223`
- `LoadedApk.makeApplication()` then continued with `com.stub.StubApp` instead
  of a swapped real application. The run later crashed:
  - `ActivityThread initial application state stage=afterSetInitialApplication localApplication=com.stub.StubApp`
  - `ActivityThread initial application state stage=afterApplicationOnCreate localApplication=com.stub.StubApp threadInitialApplication=null loadedApkApplication=null`
  - `java.lang.NullPointerException: ... Application.getResources() ... ActivityThread.handleConfigurationChanged`
- Screenshot remained the white BestV splash, not the direct physical UI:
  - `/tmp/20260517_120x_bestv_dexload_seccomp.png`

## Conclusion

The DexFile.loadDex seccomp trigger is useful as a generic diagnostic payload
dumper because it recovers the real decrypted dex. Do not treat it as the
runtime acceptance fix by itself: installing seccomp before standalone
`DexFile.loadDex` changes the protected stub path into a blocked
`System.exit(1)`/stub-application state. Future acceptance work should either
use this only as a dump/analysis aid or pair it with a deeper fix that preserves
the real application swap after the protected loader's termination probe.

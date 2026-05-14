# ClassLoader diagnostics confirm QHClassLoader/entryRunApplication missing but do not fix bootstrap

## Attempt

Added a generic `ClassLoader.loadClass(...)` diagnostic hook that only observes
`ClassNotFoundException` failures and records the class name, class loader
identity, `DexPathList`, and short stack through `BlackBoxBinderMonitor`.

The hook is package-agnostic and does not synthesize classes or alter
`loadClass` results.

## Evidence

- Source tests and `assembleBlackBox32Debug` passed after the diagnostic hook.
- `com.example.tester` still visually loaded Apple.com:
  - `/tmp/20260517_tester_classloader_diag.logcat`
  - `/tmp/20260517_tester_classloader_diag.png`
- BestV still failed before the real application was swapped in:
  - `/tmp/20260517_bestv_classloader_diag.logcat`
  - `/tmp/20260517_bestv_classloader_diag.png`
  - `RuntimeExitProxy: blocked java.lang.System.exit(1) ... com.stub.StubApp.attachBaseContext:223`
  - `ActivityThread initial application state ... localApplication=com.stub.StubApp`
  - later `Application.getResources()` NPE, which is secondary to the missing
    real `Application`.
- The diagnostic confirmed that `System.load(...libjiagu...)` / native load
  probes try to resolve Qihoo loader classes before the final stub exit:
  - `class=com.qihoo.util.QHClassLoader`
  - `class=com.stub.QHClassLoader`
  - `class=com.qihoo.util.upgrade.Upgrade`
  - `class=com.qihoo.sc.SC`
- The final stub lookup also fails:
  - `class=entryRunApplication`
  - stack includes `com.stub.StubApp.a:99` and
    `com.stub.StubApp.attachBaseContext:210`
  - loader is still a normal `dalvik.system.PathClassLoader` whose path is
    limited to framework jars and/or `base.apk`; no magic/payload class loader
    is visible at that point.
- Static inspection of the original APK and current dump confirmed these names
  are not normal classes in the dumped dex:
  - `entryRunApplication` appears only as the stub placeholder string.
  - `QHClassLoader` names were not found in the original APK/dumped payload
    Java sources.

## Conclusion

The diagnostic is useful and may remain for low-volume evidence, but it is not
an acceptance fix. Do not hardcode or synthesize `entryRunApplication`,
`com.qihoo.util.QHClassLoader`, or target-specific classes.

The current evidence supports this model: the protected native loader should
either replace the placeholder class name or publish a payload/synthetic class
loader into `context.getClassLoader()`, but it refuses or fails before doing so.
The next fix must address a generic loader-visible sandbox signal or loader
timing issue, not intercept the missing class names.

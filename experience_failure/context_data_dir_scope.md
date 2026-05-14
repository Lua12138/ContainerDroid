# ContextDataDirProxy scope attempt

## Attempt

Scoped `ContextDataDirProxy` so it only rewrites `ContextImpl` instances whose base/op/package name matches the virtual package. Host and system contexts keep their original data directories.

## Evidence

- Before this change, the target run logged `ContextImpl: Failed to ensure /data/user/0/com.bestv.tv.video.iqy.tjdx/shared_prefs` before `NativeCore.enableIO()` registered file hooks. That was caused by process-wide ContextImpl hooks rewriting host BlackBox context storage while framework/config code was still initializing.
- After the change, the `ContextImpl: Failed to ensure .../shared_prefs` warning disappeared in `/tmp/logcat.log`.
- The target still exits from `com.stub.StubApp.attachBaseContext:223`, followed by missing real providers and `ActivityThread.handleConfigurationChanged` NPE because the protected real application was not installed:
  - `RuntimeExitProxy: blocked java.lang.System.exit(1) ... com.stub.StubApp.attachBaseContext:223`
  - `ClassNotFoundException: com.bestv.ott.provider.ConfigProvider`
  - `threadInitialApplication=null loadedApkApplication=null`

## Conclusion

The context scoping fix is useful and should be kept, but by itself it does not make `com.bestv.tv.video.iqy.tjdx` run. Do not retry this as the sole fix for the Jiagu application bootstrap failure.

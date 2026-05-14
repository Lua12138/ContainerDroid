# Package-bind seccomp before makeApplication attempt

## Attempt

Installed seccomp from `BActivityThread.handleBindApplication()` for
`com.bestv.tv.video.iqy.tjdx` immediately after `NativeCore.setVirtualUid(...)`
and before `LoadedApk.makeApplication(...)`, avoiding the failed Java logging
trigger path.

## Evidence

- The shield installed after the virtual uid was configured:
  - `BlackBoxSeccomp: virtual uid configured virtualUid=10001 kernelUid=10189`
  - `BlackBoxSeccomp: seccomp shield installed`
- Unlike the no-seccomp ABI-aligned run, the app did not reach the real BestV
  bootstrap:
  - no `AppContext: AppContext attachBaseContext`
  - no `OttContext: enter init`
  - no `SysEnvAdapter: config path : ...`
- The run reverted to the stub path:
  - `RuntimeExitProxy: blocked java.lang.System.exit(1) ... com.stub.StubApp.attachBaseContext:223`
  - `ActivityThread initial application state ... localApplication=com.stub.StubApp`
  - provider `ClassNotFoundException` for `ConfigProvider` and
    `TjgdAdapterInitProvider`
  - `ActivityThread.handleConfigurationChanged()` NPE
- Screenshot stayed on the BlackBox splash.

## Conclusion

Installing seccomp before Jiagu finishes swapping from `StubApp` to the real
BestV `AppContext` is still too early, even when the virtual uid is already set.
Do not install seccomp before `makeApplication` as the BestV acceptance fix. The
trigger must be after the real application object exists but before the native
termination following `SysEnvAdapter`.

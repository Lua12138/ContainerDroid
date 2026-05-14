# IUserManager unlocked/running proxy still leaves BestV raw kill

## Attempt

Added a package-agnostic `IUserManagerProxy` hook for Android 11 one-argument
user-state methods:

- `isUserUnlocked(int)`
- `isUserUnlockingOrUnlocked(int)`
- `isUserRunning(int)`

The hook returns `true` locally so sandboxed app processes do not forward basic
direct-boot/running-state checks to the host user service.

## Evidence

- Source/unit/build verification passed before device testing:
  - `:Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.IUserManagerProxySourceTest`
  - broader Bcore source-test subset including the no-target-hardcode gate
  - `./gradlew assembleBlackBox32Debug`
- Tester remained healthy:
  - `/tmp/20260517_tester_iusermanager_unlocked.logcat`
  - `/tmp/20260517_tester_iusermanager_unlocked.png`
  - screenshot showed the expected Apple.com page.
  - `isUserUnlocked` and `isUserUnlockingOrUnlocked` were handled locally.
- BestV still failed the PLAN_v3 one-vote veto:
  - `/tmp/20260517_bestv_iusermanager_unlocked.logcat`
  - `/tmp/20260517_bestv_iusermanager_unlocked.png`
  - `app_died_count=9`
  - `user_forward_count=0`
  - `user_handled_count=8`
  - `publish_forward_count=0`
  - `publish_handled_count=8`
  - `transient_count=0`
- The BestV run still reached the real payload/bootstrap window:
  - `AppContext: AppContext attachBaseContext`
  - `RuntimeExecProxy: stage=sanitized_getprop command=getprop`
  - `SysEnvAdapter: sn is AC01FF4CF2026BC68D`
  - `SysEnvAdapter: terminal_type is AC01FF_BESTVINSIDE`
  - `OttContext: leave init`
  - `TjgdAdapterInitProvider: onCreate() in`
- The process then died by raw SIGKILL before the real application was synced:
  - `IUserManager.isUserUnlocked` was handled locally.
  - `ActivityThread initial application state stage=afterSetInitialApplication localApplication=com.stub.StubApp`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 9 (Killed)`
- Real payload dex dumping continued to work:
  - repeated `cookie_*_4876632_0dfc4292.dex` files under host
    `files/com.bestv.tv.video.iqy.tjdx`.

## Conclusion

The generic user-unlocked/running proxy is a useful environment-modeling
correction and can remain if no side effects appear, but it is not the BestV
acceptance fix. Do not retry `IUserManager.isUserUnlocked` forwarding avoidance
alone as the next approach.

The current blocker is still after `AppContext.attachBaseContext` and provider
publish, before `AppContext.onCreate` / real application sync. The next useful
investigation should focus on another generic native-visible or framework-state
signal in that narrow post-provider/pre-onCreate window, not on hardcoding
BestV classes or synthesizing `entryRunApplication`.

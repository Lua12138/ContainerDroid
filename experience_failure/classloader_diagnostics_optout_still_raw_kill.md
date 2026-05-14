# ClassLoader diagnostics opt-out did not resolve post-provider raw kill

## Attempt

Made the `ClassLoader.loadClass` diagnostics hook explicit opt-in via
`BLACKBOX_CLASSLOADER_DIAG`, `blackbox.classloader_diag`, or
`debug.blackbox.classloader_diag`.

This was intended to remove a global, loader-visible Pine hook surface from the
default runtime without hardcoding any target class such as
`entryRunApplication`.

## Evidence

Validation:

- `:Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.ClassLoaderDiagnosticsProxySourceTest`
- Broader selected source tests including
  `NoTargetHardcodedInterceptionSourceTest`
- `./gradlew assembleBlackBox32Debug`

Tester remained usable:

- `/tmp/20260517_tester_classloader_diag_optout.logcat`
- `/tmp/20260517_tester_classloader_diag_optout.png`
- Log shows `ClassLoader diagnostics disabled by debug property` and no
  `ClassLoader.loadClass` Pine hook installation.

BestV still failed the one-vote condition:

- `/tmp/20260517_bestv_classloader_diag_optout.logcat`
- `/tmp/20260517_bestv_classloader_diag_optout.png`
- Repeated `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
- `cl_loadclass_hook_count=0`
- `cl_diag_disabled_count=12`
- `publishContentProviders` was handled locally:
  `forwarded_host=false`, `rewritten=true`
- Runtime repeatedly reached:
  - `dalvik.system.DexFile.loadDex`
  - `AppContext: AppContext attachBaseContext`
  - `OttContext: leave init`
  - `TjgdAdapterInitProvider: onCreate() in`
  - `ActivityThread initial application state stage=afterSetInitialApplication ... com.stub.StubApp`
- Runtime did not reach:
  - `AppContext: AppContext onCreate`
  - `afterApplicationOnCreate`

The observed `android.os.Process.killProcess(pid=..., signal=9)` stack is host
cleanup after binder death:

```text
ProcessRecord.kill
<- BProcessManagerService.onProcessDie
<- BProcessManagerService$1.binderDied
```

It is not evidence of a Java-layer target call to `killProcess`.

## Conclusion

The default `ClassLoader.loadClass` diagnostic hook was a plausible generic
side channel and should remain opt-in, but disabling it is not the acceptance
fix. The current blocker is still a raw process death after real
`AppContext.attachBaseContext` / provider publication and before
`Application.onCreate`.

Do not retry this direction as a standalone fix. The next useful work is to add
low-intrusion lifecycle boundary diagnostics around `makeApplication`,
provider installation, and `callApplicationOnCreate`, and to compare the
runtime class-loader/application state at those boundaries without synthesizing
`entryRunApplication` or hardcoding BestV classes.

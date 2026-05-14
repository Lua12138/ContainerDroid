# crash_context Application.getResources NPE is secondary to missing real Application

## Observation

BestV sandbox crashes sometimes include:

```text
java.lang.NullPointerException: Attempt to invoke virtual method
'android.content.res.Resources android.app.Application.getResources()'
on a null object reference
```

The `crash_context` then records `IActivityManager.handleApplicationCrash`,
followed by Java runtime termination calls.

## Evidence

- The fatal stack is in framework launch/configuration handling:
  - `android.app.ActivityThread.handleConfigurationChanged`
  - `android.app.ActivityThread.handleLaunchActivity`
- There is no `Resources$NotFoundException`; the failed receiver is the
  `Application` object itself.
- Immediately before the NPE in the diagnostic run:
  - `StubApp.attachBaseContext` called `System.exit(1)`.
  - `RuntimeExitProxy` blocked that exit, allowing execution to continue in an
    invalid state.
  - `BActivityThread` logged:
    `localApplication=com.stub.StubApp`, then later
    `threadInitialApplication=null loadedApkApplication=null`.
- Direct physical BestV run instead logs:
  - `AppContext: AppContext attachBaseContext`
  - `AppContext onCreate`
  - `IqiyiActivity: onShowRealUi`
  - `IqiyiActivity: afterLoaded do`

## Conclusion

Do not treat this `Application.getResources()` NPE as a missing-resource problem.
It is a consequence of the protected loader failing to instantiate/swap the real
`com.bestv.iptv.tv.AppContext` and BlackBox continuing after an intercepted
`System.exit(1)`.

Likewise, the following `handleApplicationCrash`,
`RuntimeInit$KillApplicationHandler.killProcess`, and `System.exit(10)` calls are
framework crash cleanup, not the original anti-sandbox decision point.

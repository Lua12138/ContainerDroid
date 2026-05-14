# Native loader-window bionic app-visible maps is useful but not acceptance

## Attempt

Changed the generic direct `/proc/self/maps` policy so bionic/libc callers
inside the bounded app-native-loader window receive the app-visible maps writer
instead of the public/path-only writer.

This targets libffi/pthread-style protectors where the immediate `open()` caller
is libc and the app frame is not recoverable, without naming BestV, Jiagu, or
any target class.

## Evidence

Verification passed:

- RED/GREEN source test:
  `NativeFileHookSourceTest.directLibcProcMapsVirtualizationCoversBionicOnlyReadsDuringAppNativeLoadWindow`
- Broader selected source tests:
  `NativeFileHookSourceTest`, `NoTargetHardcodedInterceptionSourceTest`,
  `IConnectivityManagerProxySourceTest`, `RuntimeExecProxySourceTest`
- `./gradlew assembleBlackBox32Debug`

Tester remained healthy:

- `/tmp/20260518_tester_loader_window_bionic_app_visible.logcat`
- `/tmp/20260518_tester_loader_window_bionic_app_visible.png`
- `environment_assessment verdict="PASS" failCount=0 warnCount=3`
- no `Fatal signal`, no `BProcessManager: App Died`

BestV default run still failed one-vote acceptance:

- `/tmp/20260518_bestv_loader_window_bionic_app_visible_default.logcat`
- `/tmp/20260518_bestv_loader_window_bionic_app_visible_default.png`
- repeated `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
- repeated `RuntimeExitProxy: blocked java.lang.System.exit(1) ...
  com.stub.StubApp.attachBaseContext:223`
- `Application lifecycle boundary ... application=com.stub.StubApp`
- provider/config still used null context and fallback paths:
  `UI_UTILS: Context = null`, `ConfigPath:/cus_config`,
  `BesTVConfig: TARGET_OEM=SXYD`

Diagnostic confirmation with `debug.blackbox.file_probe=1`:

- `/tmp/20260518_bestv_loader_window_bionic_app_visible_file_probe.logcat`
- `native app loader maps window ... .jiagu/libjiagu.so`
- subsequent libc-caller maps reads changed to:
  `native proc maps app-visible sanitized ... callerMap=/apex/.../bionic/libc.so`
- despite that, the run still reached the same stub-inconsistent path and later
  `NoSuchFieldError: TelnetCommand.WONT`.

## Conclusion

Keep the generic app-visible loader-window behavior if Tester remains healthy;
it correctly addresses the bionic/libffi maps classification gap. However, it
is not the BestV acceptance fix.

Do not retry "bionic loader-window maps app-visible" alone. The remaining
failure is after the protected native loader has received app-visible maps but
still does not publish the real Application/loader before `StubApp` calls
`System.exit(1)`. Continue with generic classloader/native-library-path and
loader-visible environment parity, not target-class synthesis or WONT
hardcoding.

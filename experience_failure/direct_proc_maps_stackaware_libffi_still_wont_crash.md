# direct proc maps stack-aware libffi caller detection still WONT crash

## Attempt

Added a generic stack-aware caller check for direct libc `/proc/self/maps` opens.  The goal was to handle protectors that dispatch `open/openat` through libffi or bionic trampolines: the immediate return address can be in libc/libffi, while an older native frame is still owned by the virtual app.  In that case the hook now treats the read as app-owned and returns the app-visible `/proc/self/maps` view instead of the raw host/sandbox view.

No target package, class, or malware-specific branch was added.

## Verification

- Focused RED/GREEN source test:
  - `NativeFileHookSourceTest.directLibcProcMapsVirtualizationRecognizesLibffiBackedAppNativeFrames`
- Broader source tests:
  - `NativeFileHookSourceTest`
  - `NoTargetHardcodedInterceptionSourceTest`
- Build:
  - `./gradlew --no-daemon --console=plain assembleBlackBox32Debug`

Tester still launches and renders Apple.com, but keeps the known EnvDiag failure:

- `verdict=FAIL failCount=1`
- failed check: `runtime.exec.proc_mounts.too_small`

BestV reaches real payload/Application code, so this is progress compared with early native termination:

- `AppContext attachBaseContext`
- `OttContext enter/leave init`
- `TjgdAdapterInitProvider: onCreate() in`
- `AppContext onCreate`
- `AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity`

However it still fails one-vote with:

- `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`

The decisive diagnostic run with `debug.blackbox.jni_field_diag=1` shows the current blocker:

```text
jni field lookup failed api=GetFieldID class=org.apache.commons.net.telnet.TelnetCommand name=WONT sig=I callerLib=/data/data/com.bestv.tv.video.iqy.tjdx/.jiagu/libjiagu.so callerOffset=0x13afc7
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
#00 libc.so je_large_dalloc
#01 libc.so je_free
#02 pc 00029a41 [anon:.bss]
```

## Conclusion

The stack-aware maps change is a generic environment-modeling improvement and should not be treated as a failed target-specific workaround, but it is not sufficient for acceptance.

Do not retry "maps stack awareness" as the sole next fix.  The remaining failure at this checkpoint is the JNI metadata/static-vs-instance field path inside the protector/runtime (`GetFieldID` for a static field), followed by native heap/free corruption and process death.

Also do not reintroduce the previously failed Telnet/WONT-specific fixes; see the related WONT failure notes for those dead ends.

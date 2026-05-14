# Non-seccomp SIGSYS forwarding diagnostic did not explain BestV signal 31

## Attempt

Added scoped instrumentation in `SeccompShield.cpp` before forwarding a non-seccomp
`SIGSYS` to Jiagu's virtual handler:

```text
non-seccomp SIGSYS forwarding tid=... si_code=... si_errno=... handler=... flags=...
```

The intent was to verify whether the process was dying because our handler received a
real `SIGSYS` and forwarded it to Jiagu's registered handler.

## Verification performed

- Added/ran a source-level unit test proving the forwarding log text exists before the
  virtual handler call:
  `SeccompSignalDeliverySourceTest.nonSeccompSigsysForwardingIsLoggedBeforeVirtualHandlerRuns`
- Rebuilt the 32-bit APK successfully with Java 11:
  `./gradlew assembleBlackBox32Debug`
- Installed and launched BestV through:
  `DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp CAPTURE_SECONDS=35 LOGCAT_SECONDS=40 ./script/install-to-device.sh com.bestv.tv.video.iqy.tjdx`

## Observed result

- `/tmp/screencap.png` remained the failed launcher/state screenshot, not the direct
  BestV UI; md5 differed from `/tmp/screencap_bestv_direct.png`.
- `/tmp/logcat.log` contained no `non-seccomp SIGSYS forwarding` lines.
- The app still repeatedly exited with `signal 31`.
- One observed process died soon after the static-int compatibility path:

```text
jni static int field class compatibility ... value=252 pendingException=0 callerOffset=0x11fd3b
BProcessManager: App Died
Zygote: Process ... exited due to signal 31
```

- Another observed process reached the AppCompatSpinner metadata probe clear and then
  died after the synthetic SIGSYS canary trap:

```text
jni field static probe miss cleared owner=android.support.v7.widget.AppCompatSpinner$DropDownAdapter ...
SIGSYS canary trapped ... sysno=268 si_code=1
BProcessManager: App Died
Zygote: Process ... exited due to signal 31
```

## Conclusion

The observed failures are not explained by our SIGSYS handler forwarding a non-seccomp
SIGSYS to Jiagu's virtual handler. Do not retry this instrumentation alone as a fix.

Next useful investigations should focus on:

- the native control flow immediately after `GetStaticIntField` returns at the
  Jiagu caller offsets seen in logs, or
- SIGSYS paths that terminate without hitting the current forwarding log
  (for example kernel/default action paths or untrapped delivery mechanisms).

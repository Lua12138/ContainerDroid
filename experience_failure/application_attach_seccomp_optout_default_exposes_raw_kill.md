# Application.attach seccomp opt-out default exposes raw post-attach kill

## Attempt

Changed `ApplicationAttachSeccompProxy` so the `Application.attach(Context)`
hook remains installed but no longer installs the seccomp shield by default.
The shield is now available only through explicit generic diagnostic switches:

- `BLACKBOX_ATTACH_SECCOMP`
- `blackbox.attach_seccomp`
- `debug.blackbox.attach_seccomp`

This is package-agnostic and does not synthesize or intercept target malware
classes such as `entryRunApplication` or any BestV-specific field.

## Evidence

- RED/GREEN source test:
  - `BActivityThreadSeccompInstallSourceTest.applicationAttachProxyKeepsSeccompDiagnosticOptInAtGenericAttachBoundary`
- AOSP hook prototype was re-checked against Android 11:
  - `Application.java` line 350 has package-private final
    `attach(Context context)`.
- Source/build gates passed:
  - `./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.BActivityThreadSeccompInstallSourceTest --tests top.niunaijun.blackbox.core.NoTargetHardcodedInterceptionSourceTest`
  - `./gradlew assembleBlackBox32Debug`
- Tester still rendered Apple.com:
  - `/tmp/20260517_tester_attach_seccomp_optout.logcat`
  - `/tmp/20260517_tester_attach_seccomp_optout.png`
  - log contained `Application.attach seccomp disabled by debug property`
  - log did not contain `seccomp shield installed after Application.attach`

BestV failed the one-vote veto:

- `/tmp/20260517_bestv_attach_seccomp_optout.logcat`
- `/tmp/20260517_bestv_attach_seccomp_optout.png`
- repeated:
  - `ApplicationAttachSeccompProxy: Application.attach seccomp disabled by debug property`
  - `AppContext: AppContext attachBaseContext`
  - no `AppContext onCreate`
  - no `IqiyiActivity` lifecycle markers from the sandbox process
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 9 (Killed)`
- The previous default attach-seccomp path was gone:
  - no `seccomp shield installed after Application.attach`
  - no `BlackBoxSeccomp: seccomp shield installed`

The last repeatable sandbox-side app activity before death was the generic
environment collection path:

```text
AppContext attachBaseContext
RuntimeExecProxy: stage=sanitized command=cat /proc/mounts
NetworkUtils: getWifiMacAddress : 4cf2026bc68d
RuntimeExecProxy: before java.lang.Runtime.exec command=getprop
RuntimeExecProxy: after java.lang.Runtime.exec command=getprop process=Process[pid=..., hasExited=false]
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
```

Direct physical BestV reaches the same `SysEnvAdapter` region and then logs:

```text
SysEnvAdapter: sn is AC01FF4CF2026BC68D
SysEnvAdapter: terminal_type is AC01FF_BESTVINSIDE
SysEnvAdapter: firmware_version is BesTV_IPTV_TJDXIQY_2.7.2402.1
AppContext: AppContext onCreate
```

## Conclusion

Making the `Application.attach` seccomp shield explicit opt-in is a cleaner
diagnostic boundary and removes a loader-visible `Seccomp: 2` /
`NoNewPrivs: 1` surface from the default runtime. It is not an acceptance fix:
without that shield, BestV again reaches a raw native `SIGKILL` path after
`AppContext.attachBaseContext` and before `AppContext.onCreate`.

Do not treat attach-seccomp opt-out alone as progress toward acceptance. The
next useful work must either:

1. identify and remove the generic sandbox signal that makes the protector
   choose the raw kill path; or
2. provide a lower-observable generic raw-termination defense than full seccomp.

Do not revert to default-on full seccomp as the acceptance fix unless a later
change proves the resulting `Seccomp`/`NoNewPrivs`/SIGSYS surface no longer
changes the protected loader or activity path.

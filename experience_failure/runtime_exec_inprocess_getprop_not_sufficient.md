# In-process getprop sanitization removes child-process artifacts but does not stop BestV death

## Attempt

Changed the package-agnostic `RuntimeExecProxy` handling of `getprop` from an
internal `/system/bin/getprop` child process to an in-process `StaticProcess`
response built from `SystemPropertiesCompat.get(key)` for a curated list of
known property keys.

Then removed default reads of SELinux-protected serial/MAC keys from that
curated list:

- `persist.vendor.wifi.mac`
- `ro.serialno`
- `ro.boot.serialno`

This avoided target-package checks and did not hardcode BestV behavior.

## Evidence

Artifacts:

- `/tmp/20260517_tester_inprocess_getprop_sandbox.logcat`
- `/tmp/20260517_tester_inprocess_getprop_sandbox.png`
- `/tmp/20260517_tester_getprop_no_restricted_sandbox.logcat`
- `/tmp/20260517_tester_getprop_no_restricted_sandbox.png`
- `/tmp/20260517_bestv_inprocess_getprop.logcat`
- `/tmp/20260517_bestv_getprop_no_restricted.logcat`

Tester stayed healthy in sandbox:

- `environment_assessment`: `PASS`
- `failCount=0`
- `cat /proc/mounts` and `getprop` probes completed

The change removed the previous internal child-process surface:

- no `ProcessBuilder("/system/bin/getprop")` child from the proxy
- no proxy-created `/proc/self/fd` probe

The protected app still hit the PLAN_v3 veto:

- `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`: repeated
- `SysEnvAdapter: mac is`: observed in some attempts
- `SysEnvAdapter: sn is`: never reached
- death commonly happened during or shortly after
  `RuntimeExecProxy: stage=sanitized_getprop command=getprop`

Removing the protected keys also removed the related property access-denial
noise from the proxy path, but did not change the decisive result.

## Conclusion

Do not treat in-process `getprop` sanitization, by itself, as the BestV fix. It
is still a useful generic hardening because it reduces child-process and fd
artifacts, but the remaining death indicates another observable difference in
the same initialization window.

The next useful direction is to compare the exact `Runtime.exec("getprop")`
contract visible to app code between physical and sandbox runs, including
stream EOF timing, `waitFor` behavior, exit code, close behavior, and whether
any app-side watchdog expects a real child process.

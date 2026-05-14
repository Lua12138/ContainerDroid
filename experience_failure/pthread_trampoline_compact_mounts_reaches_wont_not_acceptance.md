# pthread entry trampoline + compact proc mounts reaches WONT, not acceptance

## Attempt

- Added a generic native `pthread_create` entry trampoline so app-owned native threads are marked before their start routine runs. This targets libffi-style dynamic calls to `pthread_create` without matching the target package, class, or function.
- Kept `/proc/mounts` virtualization generic and low-cardinality, but expanded the static template to about 4 KiB so environment probes do not fail on unrealistically tiny output.
- Added generic `IConnectivityManager` package/uid argument rewriting to prevent host-service package ownership checks from killing sandboxed WebView/Chromium callers.
- Ran with default diagnostics disabled and no target-specific WONT/Telnet/AppCompat interception.

## Evidence

- Tester sandbox run:
  - Log: `/tmp/20260518_tester_compact_mount_4k_long.logcat`
  - Screenshot: `/tmp/20260518_tester_compact_mount_4k_long.png`
  - Result: `ENVDIAG PASS F=0 W=3`
  - `cat /proc/mounts` sanitized sample size: `4212` bytes.
- BestV sandbox run:
  - Log: `/tmp/20260518_bestv_pthread_trampoline_compact_mount_default.logcat`
  - Screenshot: `/tmp/20260518_bestv_pthread_trampoline_compact_mount_default.png`
  - It reaches protected app logic (`BesTVConfig`, `IqiyiActivity.onCreate`) but still logs `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`.
  - Decisive crash signatures include `NoSuchFieldError: no "I" field "WONT" in class "Lorg/apache/commons/net/telnet/TelnetCommand;"` and later SIGSEGV variants.

## Conclusion

This generic combination is useful: it gets past the earlier libjiagu maps/pthread self-kill class far enough to expose the real app initialization path. It is not acceptance because the app still dies.

Do not count this as solved and do not retry target-specific WONT/Telnet compatibility patches. The next useful direction is generic loader/JNI/class metadata comparison around the first activity creation boundary, especially why the protected native code sees a static-vs-instance field mismatch under sandbox but not in direct physical execution.

# Default probes off after loader-hook gating still reaches SIGKILL

## Attempt

After making `dlopen`/`android_dlopen_ext` PLT hooks explicit diagnostic opt-in,
reran BestV with the visible diagnostics disabled:

- `debug.blackbox.process_probe=0`
- `debug.blackbox.termination_probe=0`
- `debug.blackbox.dlopen_probe=0`
- `debug.blackbox.early_dlopen_repatch=0`
- `debug.blackbox.dlsym_probe=0`
- `debug.blackbox.dlsym_replace=0`
- `debug.blackbox.proc_shim=0`
- `debug.blackbox.transient_maps=0`

## Evidence

Artifacts:

- `/tmp/20260518_bestv_sandbox_all_probes_off.logcat`
- `/tmp/20260518_bestv_sandbox_all_probes_off.png`

The run still hit the one-vote veto:

```text
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
Zygote  : Process <pid> exited due to signal 9 (Killed)
```

Counts from the saved log:

- `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`: 13
- `signal 9`: 14
- `AppContext: AppContext attachBaseContext`: 13
- `AppContext: AppContext onCreate`: 0
- `BesTVConfig`: 55
- `RuntimeExecProxy: stage=sanitized command=cat /proc/mounts`: 12
- no `native process probe`, `native dlopen probe`, or `native dlsym probe`

The first failing window remained:

```text
AppContext attachBaseContext
SysEnvAdapter: config path ...
RuntimeExecProxy: stage=sanitized command=cat /proc/mounts
ConfigPath: ...
open .../files/cus_config/user.properties
BProcessManager: App Died ...
```

Direct physical BestV in `/tmp/20260518_bestv_direct_current.logcat` reaches the
same bootstrap region, then continues to:

```text
SysEnvAdapter: sn is AC01FF4CF2026BC68D
OttContext: leave init
AppContext: AppContext onCreate
IqiyiActivity: enter onCreate
IqiyiActivity: onShowRealUi
```

## Conclusion

Disabling visible loader/process/dlsym/proc-shim diagnostics is necessary to
avoid extra surfaces, but it is not the acceptance fix. Do not retry
"all probes off" as a standalone fix. The next useful work should address
generic runtime-visible contract differences in this same window, especially:

1. `/proc/self/maps` shims must never synthesize invalid address ranges if they
   are enabled for diagnostics or environment simulation.
2. `Runtime.exec(...)` sanitization currently returns a custom
   `RuntimeExecProxy.StaticProcess`; a protected app can distinguish that from
   Android's real `java.lang.ProcessImpl` even when the output text is plausible.

# Real ProcessImpl `/proc/mounts` pass-through regresses BestV before BesTVConfig

## Attempt

Changed the package-agnostic `RuntimeExecProxy` handling of
`Runtime.exec("cat /proc/mounts")` and equivalent tokenized
`ProcessBuilder.start()` calls to only log the command and then pass it through
to Android's real `ProcessImpl`.

The intent was to remove BlackBox's distinguishable
`RuntimeExecProxy.StaticProcess` surface while avoiding any target-package
special casing.

## Evidence

Source/build gates passed:

- `RuntimeExecProxySourceTest`
- `NativeFileHookSourceTest`
- `NoTargetHardcodedInterceptionSourceTest`
- `assembleBlackBox32Debug`

Tester still passed:

- `/tmp/20260518_tester_proc_mounts_passthrough.logcat`
- `/tmp/20260518_tester_proc_mounts_passthrough.png`
- `environment_assessment`: `PASS`, `failCount=0`
- `cat /proc/mounts` returned a real Android process:

```text
RuntimeExecProxy: after java.lang.ProcessBuilder.start command=[/system/bin/sh, -c, cat /proc/mounts] process=Process[pid=..., hasExited=false]
```

BestV regressed:

- `/tmp/20260518_bestv_proc_mounts_passthrough.logcat`
- `/tmp/20260518_bestv_proc_mounts_passthrough.png`
- `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`: 17
- `AppContext attachBaseContext`: 17
- `AppContext onCreate`: 0
- `BesTVConfig`: 0
- `stage=sanitized_getprop command=getprop`: 0
- `cat /proc/mounts` no longer used `StaticProcess`, but still died shortly
  after the real process returned:

```text
RuntimeExecProxy: stage=passthrough_proc_mounts command=cat /proc/mounts
RuntimeExecProxy: after ... command=cat /proc/mounts process=Process[pid=..., hasExited=false]
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
Zygote: Process ... exited due to signal 9 (Killed)
```

## Conclusion

Do not use raw real-process `/proc/mounts` pass-through as the default behavior.
It removes the custom `StaticProcess` fingerprint, but the mount table content,
line shape, or child-process side effects are more damaging and move BestV to an
earlier kill point than the compact static mount template.

This falsifies `StaticProcess` as the primary trigger for the current
`/proc/mounts` window. Future attempts should preserve a compact controlled
mount surface and focus on which specific line/content features or filesystem
side effects BestV expects, instead of replaying the full system mount table.

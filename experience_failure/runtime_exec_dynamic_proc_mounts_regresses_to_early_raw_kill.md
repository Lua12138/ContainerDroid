# Dynamic /proc/mounts replay regresses BestV to earlier raw kill

## Attempt

Changed the generic `RuntimeExecProxy` handling of `cat /proc/mounts` from the
previous compact static mount template to reading the current process'
`/proc/mounts`, sanitizing host sandbox package paths, and returning that
larger direct-like mount table through `StaticProcess`.

This was package-agnostic and did not branch on BestV or synthesize any target
classes.

## Evidence

- Source tests for the dynamic mount-table path passed.
- `./gradlew assembleBlackBox32Debug` passed.
- Tester still rendered the Apple.com page:
  - `/tmp/20260517_tester_dynamic_mounts.logcat`
  - `/tmp/20260517_tester_dynamic_mounts.png`
- BestV regressed and hit the one-vote veto:
  - `/tmp/20260517_bestv_dynamic_mounts.logcat`
  - `/tmp/20260517_bestv_dynamic_mounts.png`
  - repeated `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - repeated `Zygote: Process ... exited due to signal 9 (Killed)`
- The decisive timing changed. The previous compact mount template reached:
  - `AppContext attachBaseContext`
  - `RuntimeExecProxy: stage=sanitized command=cat /proc/mounts`
  - `RuntimeExecProxy: before ... command=getprop`
  - then raw `SIGKILL`
- With dynamic `/proc/mounts`, the process died immediately after the sanitized
  `cat /proc/mounts` return:
  - `AppContext attachBaseContext`
  - `RuntimeExecProxy: stage=sanitized command=cat /proc/mounts`
  - `RuntimeExecProxy: after ... StaticProcess`
  - native `/proc/self/maps` probes
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - no `getprop`
  - no `SysEnvAdapter: sn is ...`
  - no `AppContext onCreate`
- Payload dex dumping still worked in the same run:
  - `cookie_*_4876632_0dfc4292.dex`
  - these payload files contain `com.bestv.iptv.tv` classes.

## Conclusion

Do not use full dynamic `/proc/mounts` replay as the default sandbox behavior.
It exposes additional mount-namespace details or timing/size differences that
are more loader-visible than the compact template and makes BestV fail earlier.

If dynamic mount replay is needed later, keep it behind an explicit diagnostic
switch only. The default `cat /proc/mounts` simulation should remain a stable,
compact, package-agnostic template until a better generic sanitizer is proven by
fresh tester and BestV device runs.

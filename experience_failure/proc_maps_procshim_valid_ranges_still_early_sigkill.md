# Proc maps proc-shim with valid ranges still triggers early BestV SIGKILL

## Attempt

After earlier `/proc/self/maps` shims exposed stale or synthetic mappings, the
proc-shim was changed to:

- refresh from the real current `/proc/self/maps`;
- preserve the real `libjiagu.so` address ranges;
- rewrite the protected private-library path to a public app-data alias;
- refresh fd93 atomically via temp file + `rename` + `dup2`, avoiding in-place
  truncate/read races.

This remained package-agnostic and did not hardcode the target malware package.

## Evidence

With `debug.blackbox.proc_shim=1`, the generated maps file contained only real
kernel ranges for `libjiagu.so`, no placeholder ranges, no `blackbox`/`top.*`
paths, no `libblackbox`/`libpine` lines, and no `/dev/fd` backing paths. Example
successful sanitized lines used:

```text
/data/data/com.bestv.tv.video.iqy.tjdx/.jiagu/libjiagu.so
```

However the BestV process still failed the PLAN_v3 veto and died earlier than
the clean no-procshim baseline:

- `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
- `Zygote: Process ... exited due to signal 9 (Killed)`
- no `AppContext attachBaseContext` in the proc-shim run.

Pulling the protected private library after proc-shim experiments also showed
the shim string patch is persistent in the sandbox payload copy: original proc
strings were replaced by `/dev/fd/9x`. A clean sandbox data reset is required
after these experiments:

```bash
adb shell pm clear top.niunaijun.blackboxa32
```

## Conclusion

Do not use the proc-shim as the default acceptance path. Even with valid real
address ranges and sanitized public paths, the library string patching and fd
surface remain observable enough to move this protector into an earlier kill
path.

Keep `debug.blackbox.proc_shim` as an explicit diagnostic only. Before any BestV
baseline run after proc-shim testing, clear the host BlackBox app data so the
patched sandbox library copy is not reused.

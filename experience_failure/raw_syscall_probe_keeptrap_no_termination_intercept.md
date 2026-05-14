# Raw syscall keep-trap probe did not capture BestV SIGKILL source

## Attempt

Kept the raw-SVC breakpoint active after benign syscalls by emulating the
syscall through a private ARM `svc #0` helper from the SIGTRAP handler. This
avoids restoring a shared raw-syscall trampoline after the first non-termination
call and should preserve visibility for a later `kill`/`tgkill`/`exit` through
the same code.

The diagnostic remained explicit opt-in through:

- `BLACKBOX_ATTACH_RAW_SYSCALL_PROBE`
- `blackbox.attach_raw_syscall_probe`
- `debug.blackbox.attach_raw_syscall_probe`

## Evidence

Artifacts:

- `/tmp/20260517_bestv_rawsys_keeptrap_probe.logcat`
- `/tmp/20260517_bestv_rawsys_keeptrap_probe.png`

BestV reached protected payload/application bootstrap:

- `AppContext: AppContext attachBaseContext`
- `OttContext: enter init`
- `OttContext: leave init`
- `BesTVConfig: constructor/init/loadConfig`
- `TjgdAdapterInitProvider: onCreate() in`
- `BActivityThread: Application lifecycle boundary stage=afterMakeApplication`
- `BActivityThread: ActivityThread initial application state stage=afterSetInitialApplication`

The probe installed and patched both file-backed and anonymous executable code:

- `.jiagu/libjiagu.so`: 12 patched `svc` instructions
- `[anon:.bss]`: 10 patched `svc` instructions
- total patches: 22

The keep-trap path observed many benign raw syscalls from `[anon:.bss]`:

- syscall `3` at `pcFileOff=0xc5b98`
- syscall `5` at `pcFileOff=0xc5b68`
- syscall `19` at `pcFileOff=0xc5c28`

The process still hit the PLAN_v3 veto:

- `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
- `Zygote: Process <pid> exited due to signal 9 (Killed)`

No application-side termination was captured:

- no `raw syscall termination intercepted`
- only Java/native termination logs were host cleanup after binder death:
  `ProcessRecord.kill -> BProcessManagerService.onProcessDie -> binderDied`

## Conclusion

Do not retry keep-trap raw-SVC patching as the sole exit-stack recovery method.
It proves the `[anon:.bss]` syscall trampolines are active, but the decisive
SIGKILL is not passing through any currently patched app-map/[anon:.bss] raw
termination syscall after `Application.attach`.

The next diagnostic should pivot to a generic external-killer/watchdog or
pre-attach path:

1. instrument process creation (`fork`/`vfork`/`clone`/`execve`) and caller
   offsets to determine whether a child/watchdog is spawned before death;
2. rate-limit benign raw-syscall logging so the diagnostic itself does not
   perturb the protected loader;
3. if a child/watchdog exists, extend the same generic instrumentation to the
   child instead of hardcoding BestV/Jiagu symbols or classes.

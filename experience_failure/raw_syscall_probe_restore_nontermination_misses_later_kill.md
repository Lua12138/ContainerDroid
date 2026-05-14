# Raw syscall probe restoring non-termination SVC misses later signal-9 death

## Attempt

After the earlier broad raw-SVC probe crashed by routing non-termination
emulation through BlackBox's interposed `syscall(...)`, the probe was changed to:

- scan only app file-backed executable maps and the loader-style `[anon:.bss]`
  executable map;
- skip volatile `/memfd:*`, JIT, and Pine executable maps;
- restore the original SVC instruction on the first non-termination trap and
  retry the instruction once.

The run used:

- `debug.blackbox.attach_raw_syscall_probe=1`
- logcat: `/tmp/20260517_bestv_rawsys_anon_retry_probe.logcat`
- screenshot: `/tmp/20260517_bestv_rawsys_anon_retry_probe.png`

## Evidence

The probe installed successfully and the protected application reached its real
payload lifecycle:

```text
AppContext: AppContext attachBaseContext
BlackBoxRawSyscall: raw syscall probe patched 12 svc instructions ... path=.../.jiagu/libjiagu.so
BlackBoxRawSyscall: raw syscall probe patched 10 svc instructions ... path=[anon:.bss]
OttContext: leave init
TjgdAdapterInitProvider: onCreate() in
BActivityThread: Application lifecycle boundary stage=afterMakeApplication ...
BActivityThread: ActivityThread initial application state stage=afterSetInitialApplication ...
```

The `[anon:.bss]` SVC sites trapped only non-termination syscalls and were then
restored:

```text
raw syscall non-termination restored sys=unknown(5) ... pcFileOff=0xc5b68 path=[anon:.bss]
raw syscall non-termination restored sys=unknown(19) ... pcFileOff=0xc5c28 path=[anon:.bss]
raw syscall non-termination restored sys=unknown(3) ... pcFileOff=0xc5b98 path=[anon:.bss]
```

No termination trap was emitted:

```sh
rg "raw syscall termination intercepted" /tmp/20260517_bestv_rawsys_anon_retry_probe.logcat
```

But the process still died by the veto condition:

```text
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx ... binderAlive=false
Zygote  : Process ... exited due to signal 9 (Killed)
```

## Assessment

Restoring the SVC after the first non-termination call avoids the previous
SIGSEGV, but it likely misses later termination through the same raw syscall
trampoline. The repeated syscalls observed at stable `[anon:.bss]` offsets are
consistent with generated syscall stubs or a shared trampoline rather than
one-shot call sites.

Do not retry the "restore first non-termination SVC and leave it unpatched"
diagnostic as the sole exit-stack strategy.

## Safer next step

Keep the SVC trap active and emulate non-termination syscalls inside the
`SIGTRAP` handler using a private inline `svc #0` helper that bypasses libc and
BlackBox's interposed `syscall(...)`. This specifically avoids the earlier crash
cause while still allowing a later `kill`/`tgkill`/`exit_group` through the same
raw trampoline to be logged and blocked.

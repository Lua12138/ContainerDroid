# Broad raw syscall SVC patching routed non-termination emulation into interposed syscall and crashed

## Attempt

Enabled the default-off ARM32 raw syscall termination probe after
`Application.attach(Context)` with:

- `debug.blackbox.attach_raw_syscall_probe=1`

The probe patched every `svc #0` found in app-owned executable maps, including
file-backed protected-loader code, executable anonymous maps, and JIT memfd
maps. Non-termination raw syscalls were emulated from the `SIGTRAP` handler by
calling libc `syscall(...)`.

## Evidence

Device run:

- `/tmp/20260517_bestv_rawsys_probe.logcat`

BestV reached the real payload/bootstrap window:

```text
AppContext: AppContext attachBaseContext
BlackBoxRawSyscall: raw syscall probe patched 12 svc instructions ... .jiagu/libjiagu.so
BlackBoxRawSyscall: raw syscall probe patched 10 svc instructions ... [anon:.bss]
```

The run did not capture a termination point:

```text
rg "raw syscall termination intercepted" /tmp/20260517_bestv_rawsys_probe.logcat
# no matches
```

Instead, a secondary thread crashed inside BlackBox's own native file-hook
`syscall` interposer while the trap handler was emulating a non-termination raw
syscall:

```text
Fatal signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)
#00 ... libblackbox.so ... NativeFileHook.cpp:311
#01 ... libblackbox.so ... NativeFileHook.cpp:326
#02 ... libblackbox.so ... NativeFileHook.cpp:650
#03 ... libblackbox.so (syscall+564) NativeFileHook.cpp:1479
```

The process then hit the PLAN_v3 veto:

```text
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
Zygote: Process 16255 exited due to signal 11 (Segmentation fault)
```

## Conclusion

Do not retry broad raw-SVC patching with non-termination emulation routed
through libc/interposed `syscall(...)`. The `SIGTRAP` handler must not enter
BlackBox's file-hooking `syscall` wrapper with raw register state.

The safer follow-up is still generic and default-off:

1. emulate non-termination raw syscalls with a private inline kernel `svc #0`
   helper, not libc `syscall(...)`;
2. skip volatile executable maps such as `/memfd:jit-cache` and `[anon:*]` by
   default;
3. include maps file offsets in patch/intercept logs for IDA correlation.

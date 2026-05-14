# Direct libc termination address hook catches Jiagu raise but is not acceptance

## Attempt

Installed a package-agnostic direct libc termination shield by resolving real
`libc.so` symbol addresses and patching them with
`PineNativeInlineHookFuncNoBackup` after the sandbox virtual package is known.

Covered symbols:

- `kill`
- `tkill`
- `tgkill`
- `raise`
- `abort`
- `exit`
- `_exit`
- `_Exit`

`syscall` was deliberately excluded from this direct no-backup hook set because
patching bionic's `syscall` entry corrupted the special stub and made the Tester
process die with `SIGILL` during startup.

## Evidence

Source/build gates passed:

- `:Bcore:testDebugUnitTest --tests NativeFileHookSourceTest --tests NoTargetHardcodedInterceptionSourceTest`
- `assembleBlackBox32Debug`

Tester sanity stayed healthy with diagnostics off:

- `/tmp/20260518_tester_direct_libc_func_no_syscall.logcat`
- `/tmp/20260518_tester_direct_libc_func_no_syscall.png`
- `native direct libc termination hook patched=8 attempted=8`
- no `BProcessManager: App Died`
- no `AndroidRuntime: FATAL EXCEPTION`
- no `Fatal signal`

BestV diagnostic run:

- `/tmp/20260518_bestv_direct_libc_func_no_syscall_diag.logcat`
- `/tmp/20260518_bestv_direct_libc_func_no_syscall_diag.png`

The hook caught the protected loader's direct libc self-kill twice:

```text
native termination probe api=raise package=com.bestv.tv.video.iqy.tjdx target=8314 signal=9 callerOff=0x143673 callerMap=.../.jiagu/libjiagu.so
native termination shield blocked api=raise package=com.bestv.tv.video.iqy.tjdx target=8314 signal=9
native termination probe api=raise package=com.bestv.tv.video.iqy.tjdx target=8444 signal=9 callerOff=0x143673 callerMap=.../.jiagu/libjiagu.so
native termination shield blocked api=raise package=com.bestv.tv.video.iqy.tjdx target=8444 signal=9
```

This proves the previous raw death path is a Jiagu native `raise(SIGKILL)` through
a direct libc address, consistent with libffi-style dynamic calls bypassing PLT
and `dlsym` replacement.

The run still failed the PLAN one-vote veto:

```text
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx ... binderAlive=false
```

After the first blocked self-kill, process `8314` later crashed:

```text
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
#00 libc.so (je_large_dalloc+32)
#01 libc.so (je_free+1580)
#02 pc 00029a41 [anon:.bss]
```

After the second blocked self-kill, process `8444` reached activity launch but
hit the older native activity field-resolution failure:

```text
java.lang.NoSuchFieldError: no "I" field "WONT" in class "Lorg/apache/commons/net/telnet/TelnetCommand;"
    at com.bestv.iptv.tv.IqiyiActivity.onCreate(Native Method)
```

## Conclusion

The direct libc address hook is useful as a generic diagnostic/containment layer
and should not be replaced by returning BlackBox wrapper pointers from `dlsym`.
It is not an acceptance fix by itself: returning from a self-destruct branch can
leave the protected loader in an invalid state, and it does not identify the
environment signal that made Jiagu call `raise(SIGKILL)`.

Do not retry direct no-backup hooking of bionic `syscall`; it regresses Tester
with `SIGILL`. The next useful step is to dump or disassemble the runtime memory
containing `libjiagu.so+0x143673` and trace backwards to the environment check,
rather than broadening target-specific termination blocking.

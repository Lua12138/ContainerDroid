# Termination memdump captures runtime BSS raise caller but is not acceptance

## Attempt

Added a generic, default-off diagnostic switch
`debug.blackbox.termination_memdump` on top of the direct libc termination
probe. When a virtual app calls a native termination API, the probe resolves the
real `/proc/self/maps` entry containing the native caller, dumps the readable
mapping into the virtual package files directory, and writes a JSON metadata
sidecar for IDA correlation.

This does not hardcode the target package or protected library name. It only
records the runtime mapping that actually contains the caller PC.

## Evidence

Source/build gates:

- `:Bcore:testDebugUnitTest --tests NativeFileHookSourceTest --tests NoTargetHardcodedInterceptionSourceTest`
- `assembleBlackBox32Debug`

Tester default-off sanity:

- `/tmp/20260518_tester_termination_memdump_default_off.logcat`
- `/tmp/20260518_tester_termination_memdump_default_off.png`
- `native termination memdump`: 0
- no `BProcessManager: App Died`
- no `AndroidRuntime: FATAL`
- no `Fatal signal`

BestV diagnostic run:

- `/tmp/20260518_bestv_termination_memdump_diag.logcat`
- `/tmp/20260518_bestv_termination_memdump_diag.png`
- `debug.blackbox.maps_path_sanitize=1`
- `debug.blackbox.termination_probe=1`
- `debug.blackbox.termination_memdump=1`

The diagnostic captured the self-kill caller inside a runtime executable
anonymous mapping, not inside the static file image:

```text
native termination probe api=raise package=com.bestv.tv.video.iqy.tjdx target=9082 signal=9 caller=0xbac4a673 callerOff=0x143673 callerMap=.../.jiagu/libjiagu.so
native termination memdump meta api=raise package=com.bestv.tv.video.iqy.tjdx path=/data/user/0/com.bestv.tv.video.iqy.tjdx/files/native_probe/term_9082_raise_0x143673_0xbabd7000-0xbacc0000.bin ok=1 caller=0xbac4a673 callerOff=0x143673 mapStart=0xbabd7000 mapEnd=0xbacc0000 mapOffset=0x0 perms=r-xp map=[anon:.bss] size=954368
native termination shield blocked api=raise package=com.bestv.tv.video.iqy.tjdx target=9082 signal=9
```

Repeats showed the same logical caller offset in fresh processes with different
ASLR bases:

```text
term_9224_raise_0x143673_0xbab26000-0xbac0f000.bin
term_9286_raise_0x143673_0xbab21000-0xbac0a000.bin
```

The run still failed the PLAN one-vote veto:

```text
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
```

Subsequent failures after blocking `raise(SIGKILL)` included `SIGSEGV` in
jemalloc/ART/JIT and the previously known `NoSuchFieldError WONT` path.

## Conclusion

The memdump is a useful generic IDA diagnostic: the decisive self-kill code is
runtime-unpacked/generated executable data in `[anon:.bss]`, so static analysis
of the original `.so` cannot directly show the branch around
`callerOff=0x143673`.

Do not treat blocking `raise(SIGKILL)` as acceptance. Returning from the
self-destruction branch leaves the protected runtime in an invalid state and
only moves the failure to later crashes. The next useful step is to pull the
dumped runtime mapping and reverse the code around the mapped caller offset to
find the generic environment predicate, then simulate that lower-level
environment surface.

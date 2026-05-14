# Redirecting direct `/proc/self/maps` probes to fd 93 still hits native kill

## Attempt

Added a generic native file-hook path redirect so direct current-process proc
probes use the already prepared protected shim fds:

- `/proc/self/maps` and `/proc/<pid>/maps` -> `/dev/fd/93`
- `/proc/self/cmdline` and `/proc/<pid>/cmdline` -> `/dev/fd/91`
- `/proc/self/comm` and `/proc/<pid>/comm` -> `/dev/fd/90`
- `/proc/meminfo` -> `/dev/fd/92`
- `/proc/version` -> `/dev/fd/94`

This was intended to remove a generic sandbox signal observed after
`protected proc shims prepared`, where Jiagu still performed direct
`fopen("/proc/self/maps")` probes.

## Evidence

- Source-level RED/GREEN test:
  - `NativeFileHookSourceTest.nativeFileHooksRedirectDirectProcSelfProbesToPreparedShimFds`
- Build/test gate passed with the targeted source tests and
  `assembleBlackBox32Debug`.
- Tester still rendered Apple.com:
  - `/tmp/20260517_123x_tester_procshim_direct.logcat`
  - `/tmp/20260517_123x_tester_procshim_direct.png`
- BestV still failed:
  - `/tmp/20260517_bestv_latest.logcat`
  - `/tmp/20260517_bestv_latest.png`
  - screenshot remained black.
  - repeated `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`.
  - process exited with `signal 9 (Killed)`.
- The patch did take effect after Jiagu shim setup:
  - `protected proc shims prepared ... maps=/dev/fd/93`
  - later direct probes became
    `native file probe api=fopen path=/proc/self/maps redirected=/dev/fd/93`

## Conclusion

Direct `fopen("/proc/self/maps")` leakage after shim setup was a real generic
gap and is now covered, but it is not the sole decisive sandbox check for this
protector path.

Do not retry the same fd93 direct maps redirect as the acceptance fix. The next
useful directions are:

1. inspect other proc/fd/readlink/stat/openat/syscall probe paths in the same
   native-loader window;
2. identify whether the native kill is a raw syscall path not covered by the
   current termination shield;
3. use IDA/native tracing to map the branch between `DexFile.loadDex`,
   unsupported-classloader warnings, proc probes, and the final signal 9.

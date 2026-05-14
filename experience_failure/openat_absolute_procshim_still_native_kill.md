# openat absolute proc shim fix did not resolve BestV native kill

## Attempt

Fixed the generic native file hook so absolute `openat` paths use
`redirectAbsolutePath(pathname)` instead of bypassing the proc shim path through
`IO::redirectPath(pathname)`. This was package-agnostic and aimed to cover
protectors using `openat(AT_FDCWD, "/proc/self/maps", ...)`.

## Evidence

- RED/GREEN source test added:
  - `NativeFileHookSourceTest.nativeFileHooksUseProcShimForAbsoluteOpenAtPaths`
  - It failed before the production change because the absolute `openat` branch
    returned `IO::redirectPath(pathname)`.
- Verification passed:
  - `:Bcore:testDebugUnitTest --tests NativeFileHookSourceTest --tests DexNotifyDumpSourceTest`
  - `assembleBlackBox32Debug`
- Tester remained functional:
  - `/tmp/20260517_tester_openat_abs_procshim.logcat`
  - `/tmp/20260517_tester_openat_abs_procshim.png`
  - screenshot showed Apple.com.
- BestV still failed:
  - `/tmp/20260517_bestv_openat_abs_procshim.logcat`
  - `/tmp/20260517_bestv_openat_abs_procshim.png`
  - screenshot stayed black.
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process 27420 exited due to signal 9 (Killed)`
- No decisive `openat` / `syscall.openat` proc shim hit appeared in the failure
  window. The observed proc probes were still `fopen("/proc/self/maps")`.
- New evidence: several early maps probes still read real `/proc/self/maps`
  before the `.bb_proc_*` shim files/fds were prepared; only later probes were
  redirected to `/dev/fd/93`.

## Conclusion

The absolute `openat` fix is a valid generic coverage improvement and should
remain, but it is not an acceptance fix by itself. Do not retry openat absolute
path redirection as the next standalone approach. The next useful direction is
to move proc shim fd preparation earlier, or otherwise prove why early
`fopen("/proc/self/maps")` sees the real maps before the shim exists.

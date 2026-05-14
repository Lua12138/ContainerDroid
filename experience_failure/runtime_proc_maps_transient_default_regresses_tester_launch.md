# Default transient sanitized /proc/self/maps regresses tester launch

## Attempt

Enabled a package-agnostic transient `/proc/self/maps` virtualization path by
default while the fd93 proc shim was disabled. Each read-only
`/proc/self/maps` open/fopen/openat/syscall-openat probe received a fresh memfd
containing a sanitized snapshot from the real maps file, with BlackBox runtime
and hook mappings removed or rewritten.

The intent was to avoid the observable stable `/dev/fd/93` backing descriptor
while still hiding sandbox/hook mappings from native protectors.

## Evidence

- Source tests passed after adding the transient maps path.
- `./gradlew assembleBlackBox32Debug` passed.
- The normal sandbox tester regressed:
  - `/tmp/20260517_tester_transient_maps.logcat`
  - `/tmp/20260517_tester_transient_maps.png`
- Screenshot was black instead of the expected Apple.com page.
- The tester run repeatedly logged transient maps substitution:
  - `NativeCore: native proc maps transient sanitized package=com.example.tester fd=65`
  - `native file probe api=fopen path=/proc/self/maps redirected=/proc/self/maps`
- The run did not reach the previous healthy marker:
  - no `ActivityThread initial application state stage=afterSetInitialApplication`
- Android later reported launch timeout / force-stop style cleanup rather than a
  rendered tester activity:
  - `ActivityTaskManager: Launch timeout has expired`
  - `Force stopping top.niunaijun.blackboxa32`

## Conclusion

Do not enable transient sanitized `/proc/self/maps` virtualization by default.
Even without the fd93 descriptor surface, changing every maps read to a fresh
sanitized memfd is observable enough to break the known-good tester launch path.

Keep this mode, if needed, behind an explicit diagnostic switch such as
`debug.blackbox.transient_maps`. The default baseline must leave
`/proc/self/maps` real unless a later generic sanitizer is proven by fresh tester
and BestV device runs.

# direct libc `/proc/self/maps` path-only virtualization for all callers still hits libjiagu raise

## Attempt

2026-05-18 reran BestV with direct libc open/openat maps hooks enabled and
`debug.blackbox.process_probe=1`, so even bionic/libffi-mediated
`open("/proc/self/maps")` calls were virtualized through the path-only maps
writer.

Artifacts:

- `/tmp/20260518_bestv_direct_proc_maps_process_probe.logcat`
- `/tmp/20260518_bestv_direct_proc_maps_process_probe.png`

## Evidence

- `native direct proc maps open` appeared roughly 1043 times.
- The immediate maps reader was usually bionic libc:
  `callerMap=/apex/com.android.runtime/lib/bionic/libc.so`.
- BestV still executed the same self-termination branch:
  `native termination probe api=raise ... callerOff=0x143673 ... .jiagu/libjiagu.so`.
- The run still ended with the PLAN_v3 veto:
  `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`.

## Conclusion

Path-only `/proc/self/maps` virtualization by itself is not the root fix. Do not
repeat an all-callers path-only maps run as the next attempt. The remaining
signal is in the pthread/libffi-created native branch around
`libjiagu.so+0x143673`; collect caller-stack evidence or fix the underlying
native-visible environment mismatch instead of widening path-only maps
virtualization further.

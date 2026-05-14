# transient `/proc/self/maps` hiding for all callers still hits libjiagu raise

## Attempt

2026-05-18 reran BestV with direct libc maps hooks, `debug.blackbox.process_probe=1`,
and `debug.blackbox.transient_maps=1`, forcing bionic/libffi-mediated maps reads
through the stronger transient maps sanitizer that hides BlackBox/Pine mappings.

Artifacts:

- `/tmp/20260518_bestv_direct_proc_maps_process_probe_transient.logcat`
- `/tmp/20260518_bestv_direct_proc_maps_process_probe_transient.png`

## Evidence

- `native proc maps transient sanitized` appeared hundreds of times.
- The run still reached the same native self-termination point:
  `native termination probe api=raise ... callerOff=0x143673 ... .jiagu/libjiagu.so`.
- With the termination shield blocking that raise, execution advanced into the
  real app and then exposed the older WONT/JIT crash path, so transient maps
  hiding was not sufficient as an acceptance fix.

## Conclusion

Do not treat transient maps hiding as the BestV solution. It can still be useful
as a diagnostic mode, but previous Tester runs showed broad/default transient
maps can regress normal sandbox launch. The next attempt should identify the
specific pthread/libffi anti-debug branch input rather than making transient
maps hiding broader.

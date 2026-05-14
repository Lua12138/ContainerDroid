# direct libc /proc/self/maps hook framework scan regression

## Attempt

After adding the internal reentry guard, every read-only direct libc open of
`/proc/self/maps` was virtualized by the generated maps fd.

## Failure

Tester no longer crashed, but WebView startup became visibly slower and logs
showed a large number of direct maps virtualizations from framework/WebView or
bionic callers. The screenshot was taken before WebView was fully rendered in
that run.

Artifact:

- `/tmp/20260518_tester_direct_proc_maps_internal_guard.logcat`
- `/tmp/20260518_tester_direct_proc_maps_internal_guard.png`

## Root cause

Framework components also scan `/proc/self/maps`. Virtualizing every libc-level
maps open introduces broad side effects and unnecessary latency. The protected
loader issue is specifically app-owned native code bypassing PLT wrappers via a
direct libc function address/libffi-style call.

## Resolution

Keep the direct libc hooks installed, but only virtualize `/proc/self/maps` for
app-owned native callers by default. Keep the all-callers mode behind
`debug.blackbox.process_probe=1` as a diagnostic mode. This preserves WebView
behavior while still covering protected native loaders.

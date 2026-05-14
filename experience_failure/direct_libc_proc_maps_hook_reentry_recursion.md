# direct libc /proc/self/maps hook reentry recursion

## Attempt

Installed no-backup inline hooks on libc `open`, `open64`, `__open_2`, `openat`,
`__openat`, and `__openat_2` so libffi/direct-call code cannot bypass the
existing PLT wrappers when reading `/proc/self/maps`.

## Failure

Tester run crashed with a repeated native recursion:

```text
blackbox_direct_open -> openTransientProcMapsFdForRead/openRealProcMapsFile
  -> libc fopen64 -> libc open/open64 -> blackbox_direct_open -> ...
```

Artifact:

- `/tmp/20260518_tester_direct_proc_maps_active.logcat`
- `/tmp/20260518_tester_direct_proc_maps_active.png`

## Root cause

The direct libc replacement is below normal libc APIs. Internal diagnostic or
maps-generation code that uses `fopen`/`open` re-enters the replacement unless
explicitly guarded.

## Resolution

The direct libc replacements must first check the existing internal probe guard
and bypass to a raw kernel `openat` path for BlackBox's own maps/file-probe
implementation. Do not call the original hooked libc symbol and do not hook the
generic `syscall` entrypoint.

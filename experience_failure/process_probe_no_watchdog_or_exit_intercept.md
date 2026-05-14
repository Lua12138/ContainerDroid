# Process creation probe did not reveal an external watchdog or libc exit path

## Attempt

Added a default-off, package-agnostic native process-creation diagnostic behind:

- `debug.blackbox.process_probe`

The diagnostic logs `fork`, `vfork`, `clone`, `execve`, and raw
`syscall(__NR_clone/__NR_execve)` calls with parent pid, result pid, flags, path,
and caller address. It does not block or hardcode any target package.

## Evidence

Artifacts:

- `/tmp/20260517_bestv_process_probe.logcat`
- `/tmp/20260517_bestv_process_probe.png`
- `/tmp/20260517_bestv_process_rawsys_rate_limited.logcat`
- `/tmp/20260517_bestv_process_rawsys_rate_limited.png`

With `debug.blackbox.process_probe=1` and raw-syscall probing disabled:

- `native process probe`: 0
- `native termination shield blocked`: 0
- `BProcessManager: App Died`: 4
- `Zygote: Process <pid> exited due to signal 9 (Killed)`: repeated

With both `debug.blackbox.process_probe=1` and
`debug.blackbox.attach_raw_syscall_probe=1`:

- `native process probe`: 0
- `native termination shield blocked`: 0
- `raw syscall termination intercepted`: 0
- `raw syscall non-termination emulated`: 49 after rate limiting
- only benign raw syscalls `3`, `5`, and `19` were observed from `[anon:.bss]`

In both runs BestV reached:

- `AppContext: AppContext attachBaseContext`
- `OttContext: leave init`
- `TjgdAdapterInitProvider: onCreate() in`
- `BActivityThread: Application lifecycle boundary stage=afterMakeApplication`
- `BActivityThread: ActivityThread initial application state stage=afterSetInitialApplication`

The decisive death still occurred shortly after repeated native
`/proc/self/maps` reads and before the real activity UI.

## Conclusion

Do not retry process-creation/watchdog probing as the sole next step. The latest
evidence does not show a forked child, `execve`, libc-level termination call, or
patched app-map raw termination syscall before the signal-9 death.

The next useful direction is to correlate the repeated native environment
probes immediately before death, especially `/proc/self/maps`, with caller
addresses/map offsets. That can identify the protected native decision branch
for IDA analysis without hardcoding BestV/Jiagu classes or suppressing the final
kill itself.

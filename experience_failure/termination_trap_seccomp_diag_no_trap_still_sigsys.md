# Application.attach termination-trap seccomp diagnostic did not capture BestV raw death

## Attempt

Added a package-agnostic, default-off diagnostic seccomp mode installed after
`Application.attach(Context)` only when explicitly enabled with:

- `BLACKBOX_ATTACH_TERMINATION_TRAP`
- `blackbox.attach_termination_trap`
- `debug.blackbox.attach_termination_trap`

The mode installs a SIGSYS handler and a BPF filter intended to trap raw
`exit`, `exit_group`, and fatal `kill`/`tkill`/`tgkill`/`rt_sigqueueinfo`
variants, then log `pc/lr/fp` and unwind frames.

## Evidence

Verification passed:

- `:Bcore:testDebugUnitTest --tests SeccompTerminationTrapDiagnosticsSourceTest`
- selected source test subset including lifecycle, BProcessManager death logging,
  native termination wrappers, runtime-exit hooks, and no-target-hardcode gate
- `./gradlew assembleBlackBox32Debug`

Tester default-off sanity stayed healthy:

- `/tmp/20260517_tester_termination_trap_default_off.logcat`
- `/tmp/20260517_tester_termination_trap_default_off.png`
- no `termination trap seccomp` install lines
- no `BProcessManager: App Died`
- visible `com.example.tester.MainActivity`

BestV with `debug.blackbox.attach_termination_trap=1` failed earlier:

- `/tmp/20260517_bestv_termination_trap_diag.logcat`
- `/tmp/20260517_bestv_termination_trap_diag.png`
- repeated:
  - `AppContext: AppContext attachBaseContext`
  - `termination-trap seccomp TSYNC filter installed for all current threads`
  - `termination-trap seccomp shield installed`
  - `UI_UTILS: enter getPreferenceKeyValue(..., LastVersion)`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 31 (Bad system call)`

No useful raw termination caller was captured:

- no `seccomp trap ... pc=... lr=...`
- no `regs a0=...`
- no `frame[...]`
- no `native termination shield blocked`

Baseline without this diagnostic remains a repeated post-`afterMakeApplication`
`SIGKILL`/binder-death path, so this diagnostic changes the protected app's path
into a fast SIGSYS and does not explain the default raw kill.

## Conclusion

Do not use `debug.blackbox.attach_termination_trap=1` as an acceptance fix or
as a routine default diagnostic for BestV. It is useful as a generic, explicit
tool in the codebase, but for this sample it is either detected through the
`Seccomp`/`NoNewPrivs` surface or interacts with the protected loader's SIGSYS
handling before the raw termination call is observable.

The current useful facts are:

1. `BProcessManager: App Died` is a binder-death notification, not the source
   of the target app exit.
2. Java exit hooks only observe host cleanup after binder death.
3. libc/PLT/syscall inline wrappers did not see the application-side exit.
4. seccomp termination trapping did not produce a trap log before the SIGSYS
   death.

Next investigation should return to the narrow default-off window around
`AppContext.attachBaseContext` / `OttContext` / provider publish and compare the
generic native-visible environment inputs there, rather than relying on seccomp
for this sample's caller recovery.

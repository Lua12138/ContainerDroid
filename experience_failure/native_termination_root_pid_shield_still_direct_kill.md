# Native termination root-pid/pgid shield still dies by signal 9

## Attempt

Extended the generic native termination interposition so forked helper/watchdog
processes inherit the original sandbox process identity and cannot terminate the
original sandbox PID or its process group through libc-level `kill`, `tkill`,
`tgkill`, or process-group kill forms.

This was package-agnostic: it protected the current sandbox process/root process
identity, not any target class or package-specific behavior.

## Evidence

- Targeted source test passed:
  - `NativeFileHookSourceTest.nativeTerminationShieldBlocksForkedWatchdogKillingOriginalSandboxProcess`
- Broader source/build gate passed:
  - `:Bcore:testDebugUnitTest` selected source tests
  - `assembleBlackBox32Debug`
- Tester run remained functional:
  - `/tmp/20260517_tester_forkwatchdog_shield.logcat`
  - `/tmp/20260517_tester_forkwatchdog_shield.png`
- BestV still failed:
  - `/tmp/20260517_bestv_forkwatchdog_shield.logcat`
  - `/tmp/20260517_bestv_forkwatchdog_shield.png`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process 24854 exited due to signal 9 (Killed)`
- No interposition hit was logged around the death:
  - no `native termination shield blocked ...`
- The decisive sequence stayed near the native loader/proc probing point:
  - `DexFile.loadDex`
  - `Opening an oat file without a class loader`
  - repeated `/proc/self/maps` probes redirected to `/dev/fd/93`
  - process dies with signal 9 before real `AppContext` is published.

## Conclusion

The forked-watchdog/root-pid protection is a valid generic hardening layer, but
it is not sufficient for this BestV failure. Do not retry libc-level
root-pid/pgid termination interposition as the sole next fix.

The remaining signal 9 path likely bypasses the libc wrappers, for example via
direct raw syscalls or another kernel-visible termination route. The next useful
test should catch raw termination syscalls with lower observability than the
previous full diagnostic seccomp mode.

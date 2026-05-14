# BestV raw rt_sigprocmask sanitization still exits with SIGSYS

## Attempt

Added a seccomp `rt_sigprocmask` trap for ARM32 so raw Jiagu signal-mask
syscalls could not bypass the PLT `sigprocmask`/`pthread_sigmask` hooks. The
handler copied the incoming mask to a fixed internal bypass slot, removed
`SIGSYS` and `SIGILL`, and forwarded the sanitized syscall through a BPF-allowed
internal pointer.

## Evidence

- The targeted source test passed after a RED/GREEN cycle.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt `libblackbox.so`.
- On device, the new raw syscall path fired repeatedly:
  - `raw rt_sigprocmask sanitize tid=... how=0 sigsetsize=8 removed=SIGSYS/SIGILL ... result=0`
  - `raw rt_sigprocmask sanitize tid=... how=2 sigsetsize=8 removed=SIGSYS/SIGILL ... result=0`
- The previous `SIGSYS mask changed tid=<main> blocked=1` evidence after the
  WONT static-int path disappeared; main-thread masks stayed unblocked in the
  watchdog output.
- BestV still reached real bootstrap and the WONT static-int compatibility:
  - `AppContext attachBaseContext`
  - `OttContext enter/leave init`
  - `TjgdAdapterInitProvider onCreate() in`
  - `AppContext onCreate`
  - `jni static int field class compatibility ... value=252 pendingException=0`
- The app still did not reach the direct-run activity/UI markers:
  - no `IqiyiActivity: enter onCreate`
  - no `onShowRealUi`
  - no `afterLoaded do`
- The process still exited with signal 31:
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 31 (Bad system call)`
- No final `seccomp trap ...` line identified the decisive SIGSYS source after
  the static-int compatibility path.

## Conclusion

Raw `rt_sigprocmask` sanitization removes one failure contributor, but it is not
sufficient as the acceptance fix. Do not retry signal-mask sanitization alone.
The next useful step is to identify the actual source of the final signal 31
after WONT static-int succeeds, especially non-seccomp SIGSYS delivery paths
such as direct `tgkill(SIGSYS)`/`rt_tgsigqueueinfo` or a protected syscall not
covered by the current trap logging.

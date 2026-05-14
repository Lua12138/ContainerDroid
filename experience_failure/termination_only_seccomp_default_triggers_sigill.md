# Default termination-only seccomp before DexFile.loadDex triggers SIGILL

## Attempt

After the broader `DexFile.loadDex` seccomp diagnostic mode proved too visible,
installed a narrower default seccomp filter immediately before standalone
`dalvik.system.DexFile.loadDex`. The filter did not install the SIGSYS handler,
dumper thread, signal-mask hooks, or watchdog; it only returned success for
termination syscalls/signals (`exit`, `exit_group`, destructive `kill`/`tgkill`
variants).

## Evidence

Build and tester sanity were green:

- `./gradlew ... assembleBlackBox32Debug` passed.
- `com.example.tester` screenshot showed the Apple page:
  - `/tmp/20260517_tester_termination_only_seccomp.png`
  - `/tmp/20260517_tester_termination_only_seccomp.logcat`

BestV failed repeatedly and hit the one-vote veto:

- Artifacts:
  - `/tmp/20260517_bestv_termination_only_seccomp.logcat`
  - `/tmp/20260517_bestv_termination_only_seccomp.png`
- Repeated sequence:
  - `termination-only seccomp TSYNC filter installed for all current threads`
  - `termination-only seccomp shield installed`
  - `DexDumpProxy: termination-only seccomp shield requested before dalvik.system.DexFile.loadDex`
  - `Opening an oat file without a class loader`
  - `Skipping duplicate class check due to unsupported classloader`
  - `/proc/self/maps redirected=/dev/fd/93`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process <pid> exited due to signal 4 (Illegal instruction)`

Representative lines from the saved log:

```text
05-17 12:34:33.768 BlackBoxSeccomp: termination-only seccomp TSYNC filter installed for all current threads
05-17 12:34:33.769 DexDumpProxy: termination-only seccomp shield requested before dalvik.system.DexFile.loadDex for com.bestv.tv.video.iqy.tjdx
05-17 12:34:34.103 BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
05-17 12:34:34.106 Zygote: Process 25334 exited due to signal 4 (Illegal instruction)
```

## Conclusion

Do not enable any seccomp mode by default on the `DexFile.loadDex` path. Even the
termination-only filter changes process-visible state (`Seccomp: 2`,
`NoNewPrivs: 1`) and reliably moves BestV into a native SIGILL/self-protection
path. Keep seccomp modes as explicit diagnostics only; the default dex dump path
must avoid installing seccomp before the loader runs.

# Proc-shim opt-out restores payload dump but BestV still raw-kills before onCreate

## Attempt

Made the generic proc maps/cmdline/version shim diagnostic opt-in and tested
the default-off path with:

- `debug.blackbox.proc_shim=0`
- `debug.blackbox.attach_seccomp=0`
- `debug.blackbox.dexload_seccomp=0`
- JNI field diagnostics disabled.

This was intended to remove the fd93 proc-shim surface that regressed payload
dumping.

## Evidence

Build/source gates passed before device validation:

- `:Bcore:testDebugUnitTest --tests DexNotifyDumpSourceTest --tests DexDumpProxySourceTest --tests NativeFileHookSourceTest --tests RuntimeHookSourceTest --tests NoTargetHardcodedInterceptionSourceTest`
- `assembleBlackBox32Debug`

Tester still rendered Apple.com:

- `/tmp/20260517_tester_procshim_optout.logcat`
- `/tmp/20260517_tester_procshim_optout.png`

BestV fresh run:

- `/tmp/20260517_bestv_procshim_optout.logcat`
- `/tmp/20260517_bestv_procshim_optout.png`
- Screenshot stayed black.
- One-vote veto remained:
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 9 (Killed)`
- The payload dump problem was fixed:
  - multiple `cookie_*_4876632_0dfc4292.dex` files appeared under
    `files/com.bestv.tv.video.iqy.tjdx`.
  - JADX found `com.bestv.iptv.tv.IqiyiActivity`.
- Runtime got only to:
  - `AppContext attachBaseContext`
  - no `AppContext onCreate`

## Conclusion

Default-off proc shim is a useful correction because it restores real payload
dex dumping. It is not an acceptance fix by itself.

Do not retry "disable/default-off fd93 proc shim" alone expecting BestV to run.
Next work should compare direct vs sandbox native-visible state in the
`AppContext.attachBaseContext` to raw `signal 9` window, especially native
file/fd/readlink/stat/syscall probes and raw termination paths.

# DexFile.loadDex seccomp opt-in default-off exposes raw native kill again

## Attempt

Changed the early `DexFile.loadDex` seccomp trigger from default-on to explicit
diagnostic opt-in (`BLACKBOX_DEXLOAD_SECCOMP`,
`blackbox.dexload_seccomp`, or `debug.blackbox.dexload_seccomp`) so the normal
runtime path does not expose a full seccomp state before the protected loader
finishes.

This was intended to separate the useful payload-dump diagnostic from the
acceptance path, because earlier runs showed that installing full seccomp before
the standalone dex load dumps the real payload but changes Jiagu bootstrap into
the `StubApp` exit/NPE path.

## Evidence

- The targeted source test passed.
- `assembleBlackBox32Debug` passed.
- With `debug.blackbox.dexload_seccomp=0`, tester still rendered the Apple.com
  page:
  - `/tmp/20260517_tester_dexload_seccomp_optin_default_off.logcat`
  - `/tmp/20260517_tester_dexload_seccomp_optin_default_off.png`
- With the same default-off setting, BestV failed before payload exposure:
  - `/tmp/20260517_bestv_dexload_seccomp_optin_default_off.logcat`
  - `/tmp/20260517_bestv_dexload_seccomp_optin_default_off.png`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
- The BestV run did not log
  `DexDumpProxy: seccomp shield requested before dalvik.system.DexFile.loadDex`
  and did not dump the earlier observed ~4.8MB real payload dex. New dumps were
  still the repeated stub/support dex files.
- The existing native termination interposition did not log
  `native termination shield blocked ...` around the death, so the decisive
  early termination path is not currently caught by the libc-symbol wrappers.

## Conclusion

The explicit opt-in is a cleaner diagnostic boundary and should not be confused
with an acceptance fix. Default-off confirms that the old full-seccomp
`DexFile.loadDex` trigger was what suppressed the raw native kill long enough to
dump payload, but it was also loader-visible and still led to `StubApp`
`entryRunApplication` failure.

Do not treat "turn early full seccomp back on by default" as the next acceptance
fix. The next useful step is a lower-observable generic native termination/proc
surface fix: either catch the direct raw termination path without exposing full
seccomp, or make the protected loader's `/proc`/status/syscall observations
match a normal app closely enough that the diagnostic seccomp path can complete.

# Stale-first revealDirect guard still vetoes due to early SIGSEGV

## Attempt

- Date: 2026-05-17 08:37 +0800
- Change under test: replace the first non-null `MethodHandles.Lookup.revealDirect`
  call at Jiagu caller offset `0x11d29d` before a stale JNI global can reach ART.
- Verification before device:
  - `:Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.JniFieldLookupHookSourceTest` passed.
  - `assembleBlackBox32Debug` passed.
- Device command:
  - `DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp CAPTURE_SECONDS=70 LOGCAT_SECONDS=100 ./script/install-to-device.sh com.bestv.tv.video.iqy.tjdx`
- Artifacts:
  - `/tmp/bestv_stalefirst_patch_20260517_083726.logcat`
  - `/tmp/bestv_stalefirst_patch_20260517_083726.png`
  - `/tmp/logcat.log` sha256 `543458c72812dd77ad35b3c7d9cbfde44b6976ee994e6a9cc87ff6941c17ecfa`
  - `/tmp/screencap.png` sha256 `dd01bd02cddeda370f8a099d0c6b2f55a7face683625126e53fe38b8b552b59e`

## Result

The run failed the PLAN_v3 veto because the first sandbox process died:

```text
05-17 08:36:23.206 10428 10428 D AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity
05-17 08:36:23.298 10428 10428 D NativeCore: jni lookup api=FindClass class=java/lang/String ... callerOffset=0x11897f
05-17 08:36:23.298 10428 10428 D NativeCore: jni lookup api=GetMethodID class=java.lang.String name=equals ... callerOffset=0x10147f
05-17 08:36:23.298 10428 10467 E BlackBoxSeccomp: SIGSYS mask changed tid=10428 blocked=1 sigblk=0xfffbfeff
05-17 08:36:23.313 10428 10428 F DEBUG   : signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xe6f143d4
05-17 08:36:23.313 10428 10428 F DEBUG   : ip badcfb58 sp ffa481d8 lr bad00039 pc bad27932
05-17 08:36:23.313 10428 10428 F DEBUG   :       #00 pc 00042932  [anon:.bss]
05-17 08:36:23.596 10390 10413 D BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
```

The failing PC is in the Jiagu runtime address range for this process
(`pcOffset=0x112932`, `lrOffset=0xeb039` relative to base `0xbac15000`).

The second process in the same run reached the WONT/revealDirect path and no
longer showed the stale global abort in the captured log:

```text
05-17 08:36:29.264 10479 10479 D NativeCore: jni revealDirect WONT replacement stage=reveal-direct ... callerOffset=0x11d29d
05-17 08:36:29.264 10479 10479 D NativeCore: jni object call api=CallObjectMethodV method=revealDirect receiver=0x79 arg0=0x0 result=0xd9 pendingException=0 callerOffset=0x11d29d
05-17 08:36:29.265 10479 10479 D NativeCore: jni object call api=CallObjectMethodV method=Class.getName receiver=0xa9 ... pendingException=0 callerOffset=0x11d29d
```

However, `logcat` hit `Unexpected EOF`, so later state for the second process is
not reliable from this artifact.

## Conclusion

The stale-first revealDirect guard is not an acceptance fix by itself. The next
investigation must address the early Jiagu SIGSEGV/veto path around
`String.equals` lookup, the `SIGSYS mask changed` evidence, and `[anon:.bss]`
crash `pcOffset=0x112932`. Do not claim the revealDirect guard solved BestV
until a full run has no `BProcessManager: App Died` and the screenshot matches.

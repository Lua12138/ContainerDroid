# stale Local MethodHandle after second revealDirect replacement

## Attempt

After adding the first non-null `revealDirect` guard and the early Jiagu BSS SIGSEGV probe, reran BestV with a filtered/manual capture to avoid `adb logcat` EOF:

```bash
TAG=20260517_084859
adb logcat -G 16M
# manual equivalent of script/install-to-device.sh, but kept the process alive
# long enough to dump activity/window/process state before force-stop
```

Artifacts:

- filtered log: `/tmp/bestv_manual_filtered_20260517_084859.live.logcat`
- full log dump: `/tmp/bestv_manual_filtered_20260517_084859.full_after.logcat`
- screenshot: `/tmp/bestv_manual_filtered_20260517_084859.png`
- activity/window/process dumps:
  - `/tmp/bestv_manual_filtered_20260517_084859.activities.txt`
  - `/tmp/bestv_manual_filtered_20260517_084859.windows.txt`
  - `/tmp/bestv_manual_filtered_20260517_084859.processes.txt`

## Result

The sandbox still does not match the physical BestV UI. The screenshot is a black/translucent launch surface, not the direct physical install-complete screen.

Filtered evidence:

```text
05-17 08:49:11.787 AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity
05-17 08:49:11.888 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bb26 recoveryCount=1
05-17 08:49:11.891 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bcc0 recoveryCount=2
05-17 08:49:17.965 ActivityTaskManager: Launch timeout has expired, giving up wake lock!
```

Full log evidence:

```text
05-17 08:49:11.894 NativeCore: jni revealDirect WONT replacement stage=reveal-direct field=0x105 methodHandle=0xd5 result=0xc1 pendingException=0 callerOffset=0x11d29d
05-17 08:49:11.894 NativeCore: jni object call api=CallObjectMethodV method=revealDirect receiver=0x139 arg0=0x22012101 result=0xc1 pendingException=0 callerOffset=0x11d29d
05-17 08:49:11.895 .blackboxa32:p: indirect_reference_table.cc:60] JNI ERROR (app bug): accessed stale Local 0x22012101  (index 35656208 in a table of size 22)
```

Window/process state before force-stop:

```text
mResumedActivity: top.niunaijun.blackboxa32/top.niunaijun.blackbox.proxy.ProxyActivity$P0
mLastOpeningApp: top.niunaijun.blackboxa32/top.niunaijun.blackbox.proxy.ProxyActivity$P0
ps: com.bestv.tv.video.iqy.tjdx process is still present
```

## Interpretation

Replacing the stale/non-null `revealDirect` argument avoids the immediate ART abort for that call, but Jiagu keeps using the same stale local `MethodHandle` (`0x22012101`) in the following post-reveal path. The crash happens after the second synthetic `revealDirect` replacement, while Jiagu is resolving post-reveal MethodHandle/HandleInfo methods (`MethodHandleImpl.type`, `MethodType.parameterArray`, `MethodType.returnType`, `HandleInfo.getDeclaringClass`, `Class.getName`).

This invalidates the assumption that guarding only `revealDirect` is enough. Any follow-up post-reveal JNI call whose receiver is the original stale `MethodHandle` must be redirected to the retained synthetic MethodHandle global before calling ART.

## Do not repeat

- Do not treat absence of `BProcessManager: App Died` in a filtered log as success; the launch can still be stuck behind a CheckJNI fatal path and black screenshot.
- Do not only replace the `revealDirect` argument; the stale MethodHandle can be reused as a receiver in later calls.
- Keep using filtered/high-level log capture plus a one-shot full `logcat -d` dump to avoid the earlier live `adb logcat` EOF hiding decisive lines.

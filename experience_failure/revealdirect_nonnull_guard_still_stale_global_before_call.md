# revealDirect non-null guard still hits stale Global before the call

## Attempt

- Date: 2026-05-17 08:29 +0800
- Build state: `:Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.JniFieldLookupHookSourceTest` passed after adding the non-null revealDirect source test, and `assembleBlackBox32Debug` passed.
- Device run:
  - `DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp CAPTURE_SECONDS=70 LOGCAT_SECONDS=100 ./script/install-to-device.sh com.bestv.tv.video.iqy.tjdx`
- Artifacts:
  - `/tmp/bestv_revealdirect_patch_20260517_082937.logcat`
  - `/tmp/bestv_revealdirect_patch_20260517_082937.png`
  - `/tmp/logcat.log` sha256 `7746c09cc6f9a933aa7224052b1a43dd7e6984fec18b477773beb61d073e96cd`
  - `/tmp/screencap.png` sha256 `394efc50f3600dc255e42833381e4627fab6490e23991bad13d57d910b03daf8`

## Result

The non-null revealDirect guard did not resolve BestV. The process reached `IqiyiActivity` creation and the WONT static-field compatibility path, but then ART reported a stale global reference immediately after the `GetMethodID(revealDirect)` lookup:

```text
05-17 08:28:21.153 10207 10207 D AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity
05-17 08:28:21.249 10207 10207 D NativeCore: jni field static superclass synthetic class=java.lang.Object name=WONT sig=I result=0xa5 declaringClass=0x32c6 callerOffset=0x11f7d3 missCount=1
05-17 08:28:21.249 10207 10207 D NativeCore: post-static-int SIGSEGV probe installed jiaguBase=0xbabd4000 via=sigchain
05-17 08:28:21.250 10207 10207 D NativeCore: jni lookup api=GetMethodID class=java.lang.invoke.MethodHandles$Lookup name=revealDirect sig=(Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandleInfo; result=0xf1 callerOffset=0x11bb8b
05-17 08:28:21.250 10207 10207 F .blackboxa32:p: indirect_reference_table.cc:60] JNI ERROR (app bug): accessed stale Global 0x80002  (index 32768 in a table of size 814)
05-17 08:28:27.120 1291 1326 W ActivityTaskManager: Launch timeout has expired, giving up wake lock!
```

No `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx` veto line appeared in this run, but the sandbox screenshot remained on the host/launcher UI rather than matching the physical BestV screen.

## Negative evidence

- No `jni revealDirect WONT replacement reused synthetic methodHandle` log appeared.
- No `jni revealDirect WONT replacement stage=reveal-direct` log appeared.
- No `post-static-int SIGSEGV recovered WONT table miss` log appeared.
- Therefore the latest failure happened before the new non-null revealDirect replacement gate could be exercised.

## Do not repeat

Do not retry only widening the non-null `revealDirect` replacement gate. The next investigation must locate why ART sees stale global `0x80002` before the `CallObjectMethodV(revealDirect)` replacement path runs.

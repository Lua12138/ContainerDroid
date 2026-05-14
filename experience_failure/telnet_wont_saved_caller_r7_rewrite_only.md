# BestV TelnetCommand.WONT saved caller r7 rewrite attempt

## Attempt

After the WONT `GetFieldID` compatibility path returned the real static
`jfieldID`, rewrote both Jiagu's stack static flag and the field resolver's
saved parent `r7` slot at `[callerSp + 0x20]`, so the value restored into
`DoFieldOperate` used the static-field path.

## Evidence

- The targeted source test passed after a RED/GREEN cycle.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt
  `JniFieldLookupHook.cpp` for `armeabi-v7a` and `arm64-v8a`.
- On device, the WONT path fired and the previous Java exceptions disappeared:
  - `jni field saved caller r7 rewrite ... previous=0 current=1`
  - `jni field static compatibility ... result=0x91`
  - no `NoSuchFieldError`
  - no `NullPointerException in DoFieldOperate`
- The app still did not reach the direct-run UI markers. It now aborts later in
  native `IqiyiActivity.onCreate`:
  - `GetStaticFieldID class=org.apache.commons.net.telnet.TelnetCommand name=WONT`
  - `GetStaticFieldID class=java.lang.Object name=WONT result=0x0`
  - `JNI DETECTED ERROR IN APPLICATION: JNI GetObjectClass called with pending exception java.lang.IllegalArgumentException: null is not a direct handle`
  - stack includes `MethodHandles$Lookup.revealDirect(...)`
- `/tmp/screencap.png` remained a white BestV loading screen and did not match
  `/tmp/screencap_bestv_direct.png`.

## Conclusion

The saved parent `r7` rewrite is useful evidence and may remain as part of a
larger compatibility path, but it is not sufficient by itself. Do not retry the
saved-r7 rewrite alone as the acceptance fix. The next useful step is to trace
Jiagu's `MethodHandles.Lookup.revealDirect` / `CallObjectMethod` path after the
WONT static-field compatibility fires.

# BestV post-reveal GetObjectClass(null) MethodHandle/r4 rewrite attempt

## Attempt

After `MethodHandles.Lookup.revealDirect(null)` was replaced with a synthetic
`MethodHandleInfo` for `TelnetCommand.WONT`, retained the synthesized
`MethodHandle` as a global reference. For the observed Jiagu caller
`libjiagu.so+0x11bcd1`, `GetObjectClass(null)` returned the synthetic
`MethodHandle` class and the ARM32 wrapper rewrote the caller's live `r4`
object register to a local ref of that `MethodHandle`.

## Evidence

- The targeted source test passed after a RED/GREEN cycle.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt `libblackbox.so`.
- On device, the new compatibility fired and advanced past the previous
  immediate `java_object == null` CheckJNI abort:
  - `jni revealDirect WONT synthetic methodHandle global ... pendingException=0`
  - `jni get object class WONT replacement object=null replacement=... result=... pendingException=0 callerOffset=0x11bcd1`
  - Jiagu then queried `MethodHandleImpl.type`,
    `MethodType.parameterArray`, and `MethodType.returnType`, proving the
    rewritten object was used after the `GetObjectClass` call.
- The app still did not reach the direct-run activity/UI markers.
- A later repeated `revealDirect(null)` call was not replaced because the
  one-shot WONT compatibility flag had already been consumed:
  - `jni object call api=CallObjectMethodV method=revealDirect ... arg0=0x0 result=0x0 pendingException=1 callerOffset=0x11d29d`
  - `jni get object class object=null ... pendingException=1 callerOffset=0x11bbad`
  - `JNI DETECTED ERROR IN APPLICATION: JNI GetObjectClass called with pending exception java.lang.IllegalArgumentException: null is not a direct handle`
- `/tmp/screencap.png` remained a small black/failed landscape screenshot and
  did not match `/tmp/screencap_bestv_direct.png`.

## Conclusion

The `MethodHandle`/`r4` rewrite is useful but not sufficient by itself. Do not
retry it as a standalone acceptance fix. The next useful step is to make the
scoped `revealDirect(null)` replacement handle repeated post-WONT Jiagu calls,
then re-check whether the later `GetObjectClass` pending-exception path
disappears.

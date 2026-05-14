# BestV TelnetCommand.WONT static int redirect with cached local class attempt

## Attempt

Removed the ARM32 `r9`/`sb` class-state rewrite so Jiagu could continue its
static superclass/class-walk, returned the cached `TelnetCommand.WONT`
`jfieldID` for the scoped `java.lang.Object.WONT` lookup, and hooked
`GetStaticIntField` to redirect the later static int read to the cached
declaring `TelnetCommand` class.

## Evidence

- The targeted source test passed.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt `libblackbox.so`.
- On device, the r9 loop was gone: each process logged only one
  `jni field static superclass synthetic ... missCount=1` before the next
  failure.
- The run failed immediately after the synthetic field result with ART local
  reference validation:
  - `jni field static superclass synthetic class=java.lang.Object name=WONT sig=I result=0xa5 declaringClass=0x91 callerOffset=0x11f7d3 missCount=1`
  - `JNI DETECTED ERROR IN APPLICATION: use of deleted local reference 0x91`
  - stack top remained `com.bestv.iptv.tv.IqiyiActivity.onCreate(Native method)`
- No `jni static int field class compatibility ...` line appeared before the
  fatal error, so the compatibility path either touched the stale class
  reference before logging or Jiagu used the same deleted local class reference
  immediately after the synthetic field result.
- The app still did not reach the direct-run UI markers, and
  `/tmp/screencap.png` did not match `/tmp/screencap_bestv_direct.png`.

## Conclusion

Do not cache or reuse the declaring `jclass` as a raw local reference for the
`Object.WONT` synthetic/static-int compatibility path. Jiagu deletes or expires
that local reference before the next static access. If this line is pursued,
promote the declaring class to a global reference and decide the static-int
compatibility by `jfieldID` before touching the possibly deleted class argument.

# BestV TelnetCommand.WONT static int global-ref redirect still native crash

## Attempt

Promoted the cached `TelnetCommand` declaring class for `WONT` to a JNI global
reference, returned the cached static `jfieldID` for the scoped
`GetStaticFieldID(java.lang.Object, WONT, I)` class-walk miss, and redirected
the following `GetStaticIntField` by `jfieldID` before touching Jiagu's possibly
stale class argument.

## Evidence

- The targeted source test passed.
- `./gradlew assembleBlackBox32Debug` passed.
- On device, the previous ART validation failures were gone:
  - no `static jfieldID ... not valid for class java.lang.Object`
  - no `use of deleted local reference`
  - no `NoSuchFieldError`
  - no `NullPointerException in DoFieldOperate`
- The compatibility path reached the static integer read and returned the
  expected Apache Commons Net value:
  - `jni field static declaring class global ref ...`
  - `jni field static superclass synthetic ... result=0xa5 declaringClass=...`
  - `jni static int field class compatibility class=uninspected fieldID=0xa5 ... value=252 pendingException=0 callerOffset=0x11fd3b`
- The app still did not reach the direct-run activity/UI markers:
  - no `IqiyiActivity: enter onCreate`
  - no `onShowRealUi`
  - no `afterLoaded do`
- Immediately after the successful static-int compatibility log, the process
  died in the native/crash path:
  - `libc: failed to connect to tombstoned: Permission denied`
  - `Zygote: Process ... exited due to signal 31 (Bad system call)`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
- No `native termination shield blocked ...` lines appeared around this death.

## Conclusion

The global-ref plus fieldID-first static-int redirect fixes the local-reference
and class/field validation problems, but it is not sufficient as the acceptance
fix. Do not retry static-int-global redirect alone. The next useful step is to
instrument the post-`GetStaticIntField` native control flow or the final SIGSYS
path to identify why Jiagu immediately enters the native/crash path after the
field operation succeeds.

# BestV TelnetCommand.WONT revealDirect replacement attempt

## Attempt

After the saved caller `r7` rewrite made Jiagu use the static-field path for
`TelnetCommand.WONT`, added a JNI `CallObjectMethod*` compatibility path for
`MethodHandles.Lookup.revealDirect(null)`. The replacement synthesized a
`MethodHandle` from `Lookup.unreflectGetter(ToReflectedField(WONT, static))`
and passed that handle to the original `revealDirect`.

## Evidence

- The targeted source test passed, and `./gradlew assembleBlackBox32Debug`
  rebuilt `JniFieldLookupHook.cpp` for both native ABIs.
- On device, the run still died before the direct-run `IqiyiActivity` UI
  markers and `/tmp/screencap.png` remained a black landscape screen.
- The WONT static-field compatibility still fired:
  - `jni field static flag rewrite ... current=1`
  - `jni field saved caller r7 rewrite ... current=1`
  - `jni field static compatibility ... result=0x91`
- The process exited immediately after Jiagu queried an inherited/superclass
  fallback that cannot exist:
  - `GetStaticFieldID class=java.lang.Object name=WONT sig=I result=0x0`
  - `libc: failed to connect to tombstoned: Permission denied`
  - `Process ... exited due to signal 31 (Bad system call)`
- No `jni revealDirect WONT replacement ...` line appeared in that run, so the
  replacement was not reached. The earlier `revealDirect(null)` crash remains
  relevant, but a pending exception from the `java.lang.Object.WONT` static
  lookup now blocks the flow earlier.

## Conclusion

Do not retry the `revealDirect(null)` replacement as the sole acceptance fix.
Keep it only if the flow can be advanced past the `java.lang.Object.WONT`
lookup. The next useful step is to handle that scoped lookup as a Jiagu
class-walk miss: return `null` with the pending JNI exception cleared, then
verify whether execution reaches the existing `revealDirect` replacement.

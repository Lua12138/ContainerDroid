# BestV TelnetCommand.WONT caller stack static-flag rewrite attempt

## Attempt

Captured the ARM32 caller stack pointer for Jiagu's
`GetFieldID(TelnetCommand.WONT:I)` call at caller offset `0x13afc7`, cleared the
pending instance-field `NoSuchFieldError`, resolved the real static field with
`GetStaticFieldID`, returned that `jfieldID`, and rewrote Jiagu's caller stack
flag at `[callerSp + 0x38]` from `0` to `1` before returning.

## Evidence

- The targeted source test passed.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt `JniFieldLookupHook.cpp`
  for `armeabi-v7a` and `arm64-v8a`.
- On device the compatibility path fired repeatedly:
  - `jni lookup api=GetFieldID class=org.apache.commons.net.telnet.TelnetCommand name=WONT sig=I result=0x0 ... callerOffset=0x13afc7`
  - `jni field static flag rewrite callerSp=0xff97aad8 flag=0xff97ab10 previous=0 current=1`
  - `jni field static compatibility api=GetFieldID ... result=0x91 callerOffset=0x13afc7`
- The original `NoSuchFieldError` disappeared, but Jiagu still threw:
  - `jni throw api=ThrowNew class=java.lang.NullPointerException message=NullPointerException in DoFieldOperate ... callerOffset=0x119527`
  - `java.lang.NullPointerException: NullPointerException in DoFieldOperate`
- The app still died before the direct-run UI markers and `/tmp/screencap.png`
  did not match the direct BestV completion screen.

## Conclusion

Do not continue with stack-flag rewrite plus static `jfieldID` fallback as the
acceptance fix. It proves the field lookup exception can be bypassed, but the
Jiagu field-operation metadata still lacks the object/static state needed by
`DoFieldOperate`. The next useful step is to inspect the `DoFieldOperate`
`ThrowNew` caller and field-operation argument state, not to keep returning
static field IDs from `GetFieldID`.

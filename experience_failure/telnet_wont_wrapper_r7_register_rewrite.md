# BestV TelnetCommand.WONT wrapper r7 register rewrite attempt

## Attempt

After the WONT `GetFieldID` compatibility path returned a static `jfieldID`,
the ARM32 JNI wrapper set its own outgoing `r7` register to `1` before returning,
trying to switch Jiagu `DoFieldOperate` from the instance-field path to the
static-field path.

## Evidence

- The targeted source test and `assembleBlackBox32Debug` passed.
- On device, the wrapper path fired:
  - `jni field static compatibility api=GetFieldID ... callerOffset=0x13afc7`
  - `jni field caller r7 static flag rewrite`
- The app still failed before direct-run UI markers:
  - `jni throw api=ThrowNew class=java.lang.NullPointerException message=NullPointerException in DoFieldOperate ... callerOffset=0x119527`
  - `java.lang.NullPointerException: NullPointerException in DoFieldOperate`
- The reason is visible in the runtime code windows: the immediate caller of the
  JNI wrapper is Jiagu's field-ID resolver (`0x13af08`), not `DoFieldOperate`
  (`0x119268`). Changing the wrapper's outgoing `r7` only affects the resolver
  frame. The resolver later restores the original caller `r7` from its saved
  register stack slot, so `DoFieldOperate` still sees `r7 == 0`.

## Conclusion

Do not keep or retry the wrapper-outgoing-register `r7` rewrite as the
acceptance fix. If this path is pursued, rewrite the resolver's saved caller
`r7` stack slot so the value restored into `DoFieldOperate` changes.

# BestV TelnetCommand.WONT Object superclass synthetic jfieldID attempt

## Attempt

After runtime disassembly showed Jiagu branches away when
`GetStaticFieldID(java.lang.Object, WONT, I)` returns `null`, cached the real
`TelnetCommand.WONT` static `jfieldID` from the successful TelnetCommand lookup
and returned it for the scoped `java.lang.Object.WONT` superclass/class-walk
miss at caller offset `0x11f7d3`.

## Evidence

- The targeted source test passed.
- `./gradlew assembleBlackBox32Debug` passed.
- On device, the synthetic result branch fired:
  - `jni lookup api=GetStaticFieldID class=java.lang.Object name=WONT sig=I result=0x0`
  - `jni field static superclass synthetic class=java.lang.Object name=WONT sig=I result=0x91 callerOffset=0x11f7d3`
- ART immediately rejected the mismatched class/field pair:
  - `JNI DETECTED ERROR IN APPLICATION: static jfieldID 0x91 not valid for class java.lang.Class<java.lang.Object>`
  - process exited with signal 31.
- The run still did not reach direct-run `IqiyiActivity` UI markers, and
  `/tmp/screencap.png` did not match `/tmp/screencap_bestv_direct.png`.

## Conclusion

Do not return the real `TelnetCommand.WONT` `jfieldID` from a
`java.lang.Object.WONT` lookup. ART validates the field ID against the class used
by Jiagu's subsequent static-field access, so this shortcut is invalid. The next
fix must avoid Jiagu asking ART for `Object.WONT` or steer its class-walk state
back to the declaring `TelnetCommand` class before the static access.

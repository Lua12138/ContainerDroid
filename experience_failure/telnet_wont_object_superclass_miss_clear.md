# BestV TelnetCommand.WONT Object superclass miss clear attempt

## Attempt

After the saved caller `r7` rewrite advanced BestV past the original
`TelnetCommand.WONT` instance-field miss, Jiagu performed a follow-up
`GetStaticFieldID(java.lang.Object, WONT, I)` during its static-field
superclass/class-walk path. Added a scoped compatibility branch that only clears
the pending JNI exception for this impossible `java.lang.Object.WONT` lookup
after the WONT static compatibility has already fired.

## Evidence

- The targeted source test passed.
- `./gradlew assembleBlackBox32Debug` passed.
- On device, the compatibility branch fired exactly at the expected point:
  - `jni lookup api=GetStaticFieldID class=java.lang.Object name=WONT sig=I result=0x0`
  - `jni field static superclass miss cleared class=java.lang.Object name=WONT sig=I callerOffset=0x11f7d3`
- The app still did not reach the direct-run `IqiyiActivity` UI markers.
- Immediately after the clear, BestV/Jiagu entered its crash path and the
  sandbox process died:
  - `CrashReport: zipCrashLog()--LogPath==.../UnknownVersion/crash`
  - `SIGSYS mask changed ... blocked=1`
  - `libc: failed to connect to tombstoned: Permission denied`
  - `Process ... exited due to signal 31 (Bad system call)`
- A second `logcat -b all` run did not reveal a hidden Java/CheckJNI fatal
  between the `java.lang.Object.WONT` miss and the native death.
- `/tmp/screencap.png` remained on the BestV splash/launcher failure state and
  did not match `/tmp/screencap_bestv_direct.png`.

## Conclusion

Do not retry "clear pending exception for `java.lang.Object.WONT` and return
null" as the acceptance fix. The next useful step is to gather narrower runtime
evidence at the `GetStaticFieldID` caller offset `0x11f7d3` or from the scoped
BestV crash artifact, then decide whether Jiagu expects a non-null synthetic
field result, a different metadata flag, or another branch rewrite.

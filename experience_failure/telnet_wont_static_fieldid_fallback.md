# BestV TelnetCommand.WONT static fieldID fallback attempt

## Attempt

When Jiagu called `GetFieldID` for
`org.apache.commons.net.telnet.TelnetCommand.WONT:I` during
`IqiyiActivity.onCreate`, cleared the pending JNI `NoSuchFieldError` and
returned the static field ID from `GetStaticFieldID`. Also added a scoped
`GetIntField` fallback returning the known Apache Commons Net value `252`.

## Evidence

- The source test and `assembleBlackBox32Debug` passed.
- On device the fallback fired:
  - `jni field fallback api=GetFieldID class=org.apache.commons.net.telnet.TelnetCommand name=WONT sig=I value=252`
  - the subsequent `GetFieldID` log returned a non-null field ID.
- The original `NoSuchFieldError` no longer appeared for that run, but the app
  still failed before direct-run UI logs:
  - `java.lang.NullPointerException: NullPointerException in DoFieldOperate`
  - stack top remained `com.bestv.iptv.tv.IqiyiActivity.onCreate(Native Method)`
- No scoped `GetIntField` fallback log appeared before the NPE, so the follow-up
  failure was not resolved by treating the static field ID as an int instance
  field.
- `/tmp/screencap.png` still showed the launcher/black screen, not the direct
  BestV completion screen.

## Conclusion

Do not use a static `jfieldID` fallback by itself as the acceptance fix. It only
converts `NoSuchFieldError` into Jiagu's `DoFieldOperate` NPE. The next useful
step is to trace or prevent the Jiagu throw path before adding another field-ID
compatibility layer.

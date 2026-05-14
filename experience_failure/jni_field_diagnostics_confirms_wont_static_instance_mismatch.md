# JNI field diagnostics confirm activity failure is not entryRunApplication

## Attempt

Added a generic, target-agnostic JNI diagnostics hook that copies the current
`JNIEnv` function table and logs only failed `GetFieldID` /
`GetStaticFieldID` calls while restoring the pending Java exception unchanged.
This was intended as observability, not as a compatibility fix.

## Evidence

Validation:

- `./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.JniDiagnosticsHookSourceTest --tests top.niunaijun.blackbox.core.NoTargetHardcodedInterceptionSourceTest`
- `./gradlew assembleBlackBox32Debug`
- `com.example.tester` still rendered the Apple page:
  - `/tmp/20260517_tester_jni_diag.logcat`
  - `/tmp/20260517_tester_jni_diag.png`

BestV still failed before the direct-run UI path:

- Artifacts:
  - `/tmp/20260517_bestv_jni_diag.logcat`
  - `/tmp/20260517_bestv_jni_diag.png`
  - pulled dumps under `/tmp/bestv_jni_diag_dumps`
- Payload dump exists and contains the real app classes:
  - `cookie_31062_2_b9b51000_4876632_0dfc4292.dex`
  - contains `Lcom/bestv/iptv/tv/AppContext;`
  - contains `Lcom/bestv/iptv/tv/IqiyiActivity;`
- `entryRunApplication` appears only as a string in the stub/base dex and not
  as `LentryRunApplication;`.
- Runtime reached the real application:
  - `AppContext attachBaseContext`
  - `AppContext onCreate`
  - `Synced initial application ... com.bestv.iptv.tv.AppContext`
  - `AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity`
- Decisive diagnostic line:
  - `jni field lookup failed api=GetFieldID class=org.apache.commons.net.telnet.TelnetCommand name=WONT sig=I callerLib=/data/data/com.bestv.tv.video.iqy.tjdx/.jiagu/libjiagu.so callerOffset=0x13afc7`
- The Java fatal remained:
  - `java.lang.NoSuchFieldError: no "I" field "WONT" in class "Lorg/apache/commons/net/telnet/TelnetCommand;"`

## Conclusion

Do not treat `entryRunApplication` as the current blocker once the async
payload-dump path reaches `AppContext`. The missing `QHClassLoader` /
`entryRunApplication` evidence is an early stub-loader symptom; the current
activity failure is a native metadata/JNI field-resolution mismatch where the
protector calls `GetFieldID` for a static integer field.

Do not reintroduce target-specific `TelnetCommand.WONT` compatibility branches.
Previous attempts showed that hardcoding this field either converts the failure
into `DoFieldOperate` null-state crashes or later native/JIT failures. The next
valid direction is to compare sandbox vs direct-run generic loader/runtime state
around the protector's metadata initialization, then simulate the underlying
system/runtime difference rather than patching the named field.

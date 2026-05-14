# BestV WONT resolver static-path retry still exits with SIGSYS

## Attempt

After the WONT `GetFieldID(TelnetCommand.WONT:I)` miss at Jiagu caller
`libjiagu.so+0x13afc7`, rewrote the resolver stack static-field flag and saved
caller `r7`, then made the ARM32 `GetFieldID` JNI wrapper branch back to
Jiagu's static/instance selector at `libjiagu.so+0x13afad`. The goal was to let
Jiagu re-enter its normal `GetStaticFieldID` path before caching field metadata,
instead of patching state after the instance lookup had already returned.

## Evidence

- The targeted source test passed after asserting the retry offset, TLS retry
  address, and 8-byte aligned ARM wrapper call frame.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt `libblackbox.so`.
- On device, BestV still reached the real application bootstrap and activity
  launch:
  - `AppContext attachBaseContext`
  - `OttContext enter/leave init`
  - `TjgdAdapterInitProvider onCreate() in`
  - `AppContext onCreate`
  - `AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity`
- The retry branch fired in every observed process:
  - `jni lookup api=GetFieldID class=org.apache.commons.net.telnet.TelnetCommand name=WONT sig=I result=0x0 callerOffset=0x13afc7`
  - `jni field static retry requested ... retry=<libjiagu+0x13afad> callerSp=0xff97aad8`
- No process logged `jni field static retry compatibility` or
  `jni field static retry failed`, so execution did not reach the expected
  retried `GetStaticFieldID` wrapper path.
- Each process exited immediately after the retry request with signal 31:
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 31 (Bad system call)`
- `/tmp/screencap.png` stayed a failed sandbox screenshot, md5
  `749ebc66671cef1b28f88e8561a16a2e`, and did not match direct physical
  `/tmp/screencap_bestv_direct.png` md5 `c93ecc1275e4caa72bccfff8bf378036`.

## Conclusion

Do not retry the ARM32 return-address branch back into Jiagu's field resolver.
Even with stack alignment fixed, branching from inside the JNI wrapper to the
middle of Jiagu's resolver does not preserve enough native frame/register state
to reach the intended static lookup path, and it regresses earlier working
static-field compatibility. Keep the older manual static-field compatibility as
the baseline and investigate the next failure from that baseline instead.

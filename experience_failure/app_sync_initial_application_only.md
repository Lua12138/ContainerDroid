# Initial application runtime sync attempt

## Attempt

After `LoadedApk.makeApplication()` and `Application.onCreate()`, synchronized
`BActivityThread.mInitialApplication` with the runtime `LoadedApk.mApplication`
and platform `ActivityThread.mInitialApplication` when Jiagu swapped the local
`com.stub.StubApp` reference to the real `com.bestv.iptv.tv.AppContext`.

## Evidence

- The targeted source test passed.
- The 32-bit debug build passed.
- On device, the sandbox process reached the real BestV bootstrap and the
  runtime application state aligned after `Application.onCreate()`:
  - `Synced initial application from runtime ... synced=com.bestv.iptv.tv.AppContext`
  - `threadInitialSameLocal=true loadedApkSameLocal=true`
- The run still crashed before the direct-run `IqiyiActivity: enter onCreate`
  marker:
  - `jni lookup api=GetFieldID class=org.apache.commons.net.telnet.TelnetCommand name=WONT sig=I result=0x0`
  - `jni throw api=Throw class=java.lang.NoSuchFieldError`
  - `java.lang.NoSuchFieldError: no "I" field "WONT" ...`
- `/tmp/screencap.png` still did not match the direct BestV completion screen.

## Conclusion

The runtime application sync is useful and should stay because it aligns
BlackBox's process state with Jiagu's real `AppContext`, but it is not sufficient
as the BestV acceptance fix. Do not retry application-sync-only changes as the
next approach; the remaining failure is in the native `IqiyiActivity.onCreate`
field-resolution path.

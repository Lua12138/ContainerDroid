# Java-only RuntimeExitProxy attempt

## Attempt

Hooked `System.exit`, `Runtime.exit`, `Runtime.halt`, and Java `android.os.Process`
termination helpers for `com.bestv.tv.video.iqy.tjdx`, including status `0` and
self-targeted `SIGABRT`/`SIGKILL`/`SIGTERM`.

## Evidence

- The hooks installed in the sandbox process, but `/tmp/logcat.log` still showed
  repeated p0 deaths after Jiagu loaded the real application:
  - `AppContext: AppContext attachBaseContext`
  - `OttContext: enter init`
  - `SysEnvAdapter: config path : /data/user/0/com.bestv.tv.video.iqy.tjdx/files/cus_config...`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote  : Process <pid> exited due to signal 9 (Killed)`
- There were no `RuntimeExitProxy: blocked ...` lines before those deaths, so the
  decisive termination path was not one of the Java methods covered by
  `RuntimeExitProxy`.
- The direct physical run continued past the same `SysEnvAdapter: config path`
  point into `SysConfig`, `OttContext: leave init`, `AppContext onCreate`, and
  `IqiyiActivity`.

## Conclusion

The Java runtime-exit hooks are useful and should stay, but they are insufficient
for this Jiagu/BestV bootstrap. Do not retry Java-only exit suppression as the
sole fix; native termination paths must be instrumented or blocked.

# APK preferred ABI primaryCpuAbi attempt

## Attempt

Changed sandbox `ApplicationInfo.primaryCpuAbi` selection to use the APK native
library directories instead of always using `Build.CPU_ABI`. For the BestV APK,
this changed the sandbox class loader native zip path from
`base.apk!/lib/armeabi-v7a` to `base.apk!/lib/armeabi`, matching the direct
physical run.

## Evidence

- After the change, `BActivityThread` logged the expected class loader:
  - `nativeLibraryDirectories=[... base.apk!/lib/armeabi, /system/lib, ...]`
- The sandbox still died repeatedly at the same point:
  - `nativeLoad result ... libjgdtc.so ... not found`
  - `SysEnvAdapter: config path : /data/user/0/com.bestv.tv.video.iqy.tjdx/files/cus_configdata ...`
  - `Zygote  : Process <pid> exited due to signal 9 (Killed)`
- There were still no `TjgdAdapterInitProvider`, `ConfigProvider`,
  `OttContext: leave init`, or `AppContext onCreate` lines.
- The screenshot remained the portrait white BestV splash, not the direct
  landscape completion screen.

## Conclusion

The ABI correction is real and should stay because it matches direct Android
runtime behavior for `armeabi`-only APKs, but it is not sufficient as the BestV
acceptance fix. Do not retry primaryCpuAbi/native library path alignment as the
sole next fix.

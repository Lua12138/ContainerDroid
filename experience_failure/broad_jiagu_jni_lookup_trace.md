# Broad Jiagu JNI lookup trace attempt

## Attempt

Extended `JniFieldLookupHook` to log every `FindClass`, `GetMethodID`,
`GetStaticMethodID`, `GetFieldID`, and `GetStaticFieldID` call whose native
caller resolved to `.jiagu/libjiagu.so`.

## Evidence

- The hook compiled and produced useful early Jiagu lookup evidence, including
  ActivityThread, Context, PackageManager, Build, SystemProperties, DexFile, and
  `IqiyiActivity.onCreate` lookups.
- The log stream ended with `logcat: Unexpected EOF!`.
- The captured `/tmp/logcat.log` stopped after `AppInstrumentation:
  callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity` and crash-report
  side effects; it did not reliably retain the final
  `TelnetCommand.WONT` field lookup/fatal sequence in that run.

## Conclusion

Do not use broad Jiagu JNI lookup logging as the next diagnostic or acceptance
fix. It is too noisy for this device/logcat path. If JNI tracing is needed,
scope it to the narrow `IqiyiActivity.onCreate` window and keep the existing
specific `TelnetCommand.WONT` field evidence.

# Late seccomp println_native hook attempt

## Attempt

Added `LateSeccompInstallProxy` and hooked both `android.util.Log.d(String,
String)` and hidden/native `android.util.Log.println_native(int, int, String,
String)` so seccomp could be installed after the BestV
`SysEnvAdapter: config path :` runtime marker.

## Evidence

- On device, the process logged `HookManager: hook:
  LateSeccompInstallProxy` and hooked `Log.d`.
- After hooking `println_native`, logcat was flooded with repeated Pine
  `handleBridge` lines for every framework log call.
- `adb logcat` ended early with `Unexpected EOF`, and the screenshot stayed on
  the BlackBox splash instead of reaching the BestV app.
- The intended `SysEnvAdapter` marker and seccomp trap evidence were not
  captured in that run.

## Conclusion

Do not hook `android.util.Log.println_native` for this diagnostic/fix path. It is
too hot and destabilizes logging. If late seccomp installation is retried, hook
only the public `Log.d(String, String)` path observed in the BestV logs.

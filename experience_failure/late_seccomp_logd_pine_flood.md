# Late seccomp public Log.d hook attempt

## Attempt

Removed the hidden `android.util.Log.println_native` hook and retried
`LateSeccompInstallProxy` with only the public
`android.util.Log.d(String, String)` hook, using the BestV
`SysEnvAdapter: config path :` marker as the late seccomp install trigger.

## Evidence

- The APK rebuilt and installed, and `HookManager` installed
  `LateSeccompInstallProxy`.
- Logcat showed only the public `Log.d(String, String)` hook:
  - `Hooking method public static int android.util.Log.d(java.lang.String,java.lang.String)`
- Even that public hook produced thousands of Pine `handleBridge` lines in the
  host process before the BestV runtime marker was reached.
- `adb logcat` again ended with `Unexpected EOF`.
- `/tmp/screencap.png` remained on the BlackBox splash screen, not the BestV
  splash or direct landscape screen.
- There were no `SysEnvAdapter`, `LateSeccompInstallProxy: late seccomp shield
  installed`, or `BlackBoxSeccomp: seccomp shield installed` lines.

## Conclusion

Do not use Pine Java hooks on Android logging methods as the late seccomp
trigger. Both hidden `println_native` and public `Log.d(String, String)` are too
hot in this process and destabilize logcat/startup before BestV reaches the
decisive runtime marker.

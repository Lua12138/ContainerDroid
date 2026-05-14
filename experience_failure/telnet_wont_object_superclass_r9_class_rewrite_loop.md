# BestV TelnetCommand.WONT Object superclass r9 class rewrite loop attempt

## Attempt

After ART rejected returning the real `TelnetCommand.WONT` static `jfieldID`
from a `GetStaticFieldID(java.lang.Object, WONT, I)` lookup, kept the cached
real `jfieldID` but also rewrote the ARM32 JNI wrapper's returning `r9`/`sb`
register to the declaring `TelnetCommand` class before returning to Jiagu's
static superclass/class-walk caller at offset `0x11f7d3`.

## Evidence

- The targeted source test passed after asserting the new wrapper symbols and
  `movne r9` rewrite.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt the native hook.
- On device, BestV still reached the real application bootstrap:
  - `ApplicationAttachSeccompProxy: seccomp shield installed before real Application.attach for com.bestv.iptv.tv.AppContext`
  - `AppContext: AppContext attachBaseContext`
  - `OttContext: enter init`
  - `OttContext: leave init`
  - `TjgdAdapterInitProvider: onCreate() in`
  - `AppContext: AppContext onCreate`
- The previous ART class mismatch fatal was gone; there was no
  `static jfieldID ... not valid for class java.lang.Class<java.lang.Object>`
  line in that run.
- Instead the process entered a repeated class-walk loop:
  - `jni lookup api=GetStaticFieldID class=java.lang.Object name=WONT sig=I result=0x0`
  - `jni field static superclass class rewrite class=java.lang.Object name=WONT sig=I result=0xa5 declaringClass=0x95 callerOffset=0x11f7d3`
- BestV still did not reach the direct-run activity/UI markers:
  - no `IqiyiActivity: enter onCreate`
  - no `onShowRealUi`
  - no `afterLoaded do`
- The process then entered the crash reporter/tombstoned path and died under
  seccomp:
  - `CrashReport: zipCrashLog()--LogPath==.../UnknownVersion/crash`
  - `libc: failed to connect to tombstoned: Permission denied`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 31 (Bad system call)`
- `/tmp/screencap.png` did not match the direct BestV completion screenshot.
- A later broader, rate-limited code-window dump confirmed the loop mechanism:
  - `0x11f7ce: mov r1, sb; 0x11f7d0: blx r7` calls the static field lookup
    with the class-walk state in `sb`/`r9`.
  - `0x11f8b8: mov sb, r0` advances `sb` to the next class returned by the
    class-walk helper, and `0x11f8d2: bne.w #0x11f7c2` repeats the walk.
  - The wrapper `movne r9` rewrite restores `sb` to the declaring
    `TelnetCommand` class after every `Object.WONT` lookup, so Jiagu never keeps
    the advanced superclass state and repeats the same lookup until it crashes.

## Conclusion

Do not retry the `java.lang.Object.WONT` r9/sb class rewrite as the acceptance
fix by itself. It avoids the immediate ART class/field validation fatal, but it
does not advance Jiagu's class-walk state and instead loops at caller offset
`0x11f7d3`. The next useful step is broader, rate-limited runtime disassembly
around that caller's loop and branch targets (`0x11fae2`, `0x11fd9a`) before
attempting another branch/state rewrite.

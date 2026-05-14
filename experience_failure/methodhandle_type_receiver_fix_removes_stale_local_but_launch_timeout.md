# MethodHandle.type receiver fix removes stale-local abort but BestV still launch-times out

## Attempt

Added a post-reveal `MethodHandle.type()` receiver redirect so Jiagu's stale MethodHandle local is replaced with the retained synthetic WONT MethodHandle before calling ART.

Fresh checks:

```bash
./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.JniFieldLookupHookSourceTest
./gradlew assembleBlackBox32Debug
```

Both returned `BUILD SUCCESSFUL`.

Device run:

```bash
TAG=20260517_085648
# manual script-equivalent run with filtered live log, full logcat dump,
# screenshot, and activity/window/process dumps before force-stop
```

Artifacts:

- screenshot: `/tmp/bestv_mhtype_patch_20260517_085648.png`
- filtered log: `/tmp/bestv_mhtype_patch_20260517_085648.live.logcat`
- full log: `/tmp/bestv_mhtype_patch_20260517_085648.full.logcat`
- dumps:
  - `/tmp/bestv_mhtype_patch_20260517_085648.activities.txt`
  - `/tmp/bestv_mhtype_patch_20260517_085648.windows.txt`
  - `/tmp/bestv_mhtype_patch_20260517_085648.processes.txt`
  - `/tmp/bestv_mhtype_patch_20260517_085648.ps.txt`

## Result

The previous decisive stale-local abort is absent in this run:

```bash
rg -n "JNI ERROR|stale Local|stale Global|invalid jobject" /tmp/bestv_mhtype_patch_20260517_085648.full.logcat
# no matches
```

But the UI still does not reach the physical BestV screen. Screenshot is still black/translucent. The system reports launch timeout:

```text
05-17 08:57:01.209 AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity
05-17 08:57:01.309 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bb26 recoveryCount=1
05-17 08:57:01.311 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bcc0 recoveryCount=2
05-17 08:57:07.370 ActivityTaskManager: Launch timeout has expired, giving up wake lock!
```

Window/process state before force-stop:

```text
mResumedActivity: top.niunaijun.blackboxa32/top.niunaijun.blackbox.proxy.ProxyActivity$P0
mLastOpeningApp: top.niunaijun.blackboxa32/top.niunaijun.blackbox.proxy.ProxyActivity$P0
ps: com.bestv.tv.video.iqy.tjdx is present and in R state
```

## Interpretation

The MethodHandle receiver redirect is not sufficient for acceptance. It likely removes one CheckJNI fatal path, but BestV now remains stuck before the proxy activity creates a visible window. The p0 process being present and runnable suggests a live loop or blocked main-thread/native path rather than the previous immediate stale-local abort.

## Next evidence needed

Before another code change, collect a stack snapshot while p0 is still alive/runnable:

- `pidof com.bestv.tv.video.iqy.tjdx`
- `debuggerd -b <pid>` if permitted
- `kill -3 <pid>` plus a short post-dump logcat capture
- `/proc/<pid>/task/*/stat` or `ps -T` to identify the hot thread

Do not assume the remaining timeout is still a MethodHandle/JNI-local issue unless the stack/log points there.

## Follow-up stack/hot-thread capture

Fresh run:

```bash
TAG=20260517_085941
# manual script-equivalent run with focused logcat plus ps/top/proc/debuggerd probes
```

Artifacts:

- screenshot: `/tmp/bestv_stack_mhtype_20260517_085941.png`
- filtered log: `/tmp/bestv_stack_mhtype_20260517_085941.filtered.logcat`
- full log: `/tmp/bestv_stack_mhtype_20260517_085941.full.logcat`
- threads/top/proc:
  - `/tmp/bestv_stack_mhtype_20260517_085941.ps_threads.txt`
  - `/tmp/bestv_stack_mhtype_20260517_085941.top_threads.txt`
  - `/tmp/bestv_stack_mhtype_20260517_085941.proc_status.txt`
- debuggerd:
  - `/tmp/bestv_stack_mhtype_20260517_085941.debuggerd.txt`
  - `/tmp/bestv_stack_mhtype_20260517_085941.debuggerd.err`

Key result: `debuggerd -b` from shell was denied with `debuggerd: root is required`, and `kill -3` did not produce a Java thread dump in logcat.

The previous stale-local crash remains absent: no `JNI ERROR`, `stale Local`, `stale Global`, or `invalid jobject` matches in the full log. The `MethodHandle.type` receiver redirect is active:

```text
05-17 08:59:53.706 NativeCore: jni MethodHandle.type WONT receiver replacement receiver=0xf5 replacement=0x115 pendingException=0 callerOffset=0x11d29d
05-17 08:59:53.709 NativeCore: jni MethodHandle.type WONT receiver replacement receiver=0xa1 replacement=0x75 pendingException=0 callerOffset=0x11d29d
05-17 08:59:53.711 NativeCore: jni MethodHandle.type WONT receiver replacement receiver=0xb1 replacement=0x75 pendingException=0 callerOffset=0x11d29d
```

The remaining failure is a hot main-thread loop after the WONT recoveries:

```text
05-17 08:59:53.698 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bb26 recoveryCount=1
05-17 08:59:53.703 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bcc0 recoveryCount=2
05-17 08:59:53.707 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bb26 recoveryCount=3
05-17 08:59:53.709 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bcc0 recoveryCount=4
05-17 08:59:53.710 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bb26 recoveryCount=5
05-17 08:59:53.711 NativeCore: post-static-int SIGSEGV recovered WONT table miss ... lrOffset=0x11bcc0 recoveryCount=6
05-17 08:59:53.711 NativeCore: post-static-int SIGSEGV probe restored via sigchain recoveries=6 logs=0 treeRecoveries=0
05-17 08:59:53.713 NativeCore: jni lookup api=GetStaticFieldID class=android.support.v7.widget.AppCompatSpinner$DropDownAdapter name=unregisterDataSetObserver sig=Lokhttp3/internal/ws/WebSocketWriter; result=0x0 callerOffset=0x13afc7
05-17 08:59:59.831 ActivityTaskManager: Launch timeout has expired, giving up wake lock!
```

Thread evidence before force-stop:

```text
u0_a189 11501 11501 ... R .video.iqy.tjdx
Threads: 21 total, 1 running, 20 sleeping
11501 ... R 100% .video.iqy.tjdx com.bestv.tv.video.iqy.tjdx
```

`/proc/<pid>/status` shows `State: R (running)`, `Threads: 21`, `NoNewPrivs: 1`, `Seccomp: 2`.

Updated interpretation: the current blocker is no longer the stale MethodHandle local abort. BestV's p0 main thread spins in user/native code after Jiagu's WONT static-int path reaches the six-recovery cap and after an AppCompat static-field probe miss. Next evidence should identify the hot main-thread PC/call stack; do not blindly raise the WONT recovery cap without proving the spin site.

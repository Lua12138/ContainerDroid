# direct libc metadata hook + backup trampoline crashes Tester/WebView

## Context

- Goal: cover libffi/direct calls to libc metadata APIs after `/proc/self/maps` sanitization.
- Attempt: inline-hook direct libc `access/stat/lstat/readlink/mkdir` entries and set `gOrig*` to hand-built Thumb backup trampolines so existing wrappers can call the original libc bodies.
- Build/tests before runtime:
  - `:Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.NativeFileHookSourceTest --tests top.niunaijun.blackbox.core.NoTargetHardcodedInterceptionSourceTest` passed.
  - `assembleBlackBox32Debug` passed.

## Runtime evidence

Tester run:

```text
/tmp/20260518_tester_direct_metadata_hook.logcat
```

Decisive lines:

```text
NativeCore: native direct libc metadata hook symbol=access ...
NativeCore: native direct libc metadata hook symbol=stat ...
NativeCore: native direct libc metadata hook symbol=lstat ...
NativeCore: native direct libc metadata hook symbol=readlink ...
NativeCore: native direct libc metadata hook symbol=mkdir ...
libc: Fatal signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xe8cdf000 in tid ... (ThreadPoolSingl)
DEBUG: #00 pc 00003000  [anon:dalvik-thread local mark stack]
DEBUG: #01 ... /product/app/TrichromeLibrary/TrichromeLibrary.apk!libmonochrome.so
BProcessManager: App Died: com.example.tester ... binderAlive=false
```

The crash happened in the WebView/Chromium `ThreadPoolSingleton` path, before the Tester run could be considered a clean sandbox baseline.

## Conclusion

The broad direct metadata hook concept is still plausible, but the implementation that forwards through copied libc entry backup trampolines is not safe enough. It can perturb common framework/WebView native code and fails the "Tester first" gate.

Do not retry the same design by only changing the copied byte count. Prefer a safer forwarding path that avoids calling patched libc entry backups, e.g. direct-hook the high-level metadata entry points but implement their replacements through unpatched lower-level `*at` libc APIs or raw syscalls where ABI-compatible.

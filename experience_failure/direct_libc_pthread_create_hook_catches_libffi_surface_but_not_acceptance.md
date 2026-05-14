# Direct libc pthread_create hook catches libffi surface but is not acceptance

## Context

The protected BestV sample dispatches some native anti-debug thread creation through libffi, so patching only app-object PLT/GOT entries is insufficient and replacing `dlsym("pthread_create")` with a `libblackbox.so` trampoline is itself an observable surface.

## Attempt

Implemented a generic direct inline hook on libc's real `pthread_create` entry:

- Preserve the public `dlsym("pthread_create")` result by leaving dlsym replacement disabled by default.
- Patch the lower libc entry with `PineNativeInlineHookFuncNoBackup`.
- Build a small architecture-specific backup trampoline so the existing AOSP-compatible wrapper can call the original implementation.
- Record successfully-created app-owned native thread ids without replacing the requested start routine.

Verified source/unit/build:

- `:Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.NativeFileHookSourceTest --tests top.niunaijun.blackbox.core.NoTargetHardcodedInterceptionSourceTest`
- `assembleBlackBox32Debug`

## Device result

Tester remained healthy:

- artifact: `/tmp/20260518_tester_direct_pthread_hook.logcat`
- screenshot: `/tmp/20260518_tester_direct_pthread_hook.png`
- Apple page visible.
- no `BProcessManager: App Died`, no fatal signal.

BestV still failed acceptance:

- artifact: `/tmp/20260518_bestv_direct_pthread_hook.logcat`
- screenshot: `/tmp/20260518_bestv_direct_pthread_hook.png`
- hook installed: `native direct pthread_create hook symbol=pthread_create ...`
- real app code was reached (`BesTVConfig` logs observed).
- shell still hit self-destruction path:
  - `native termination shield blocked api=raise package=com.bestv.tv.video.iqy.tjdx target=... signal=9 ... caller=0xbab4c673`
- final one-vote failure:
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx ... binderAlive=false`
  - `Zygote: Process ... exited due to signal 11 (Segmentation fault)`
  - final native crash in libc free path with caller in runtime-generated `[anon:.bss]`.

## Conclusion

The generic direct libc `pthread_create` hook is useful coverage for libffi-dispatched calls and did not break Tester, but by itself it does not make BestV pass. The remaining self-kill predicate is likely an environment/path/application-info/classloader check in runtime-unpacked code near the `raise(SIGKILL)` call site, not simply missing `pthread_create` interception.

Do not retry pthread-create-only fixes as an acceptance solution. Continue by reverse-tracing the runtime `[anon:.bss]` self-kill branch and comparing the generic path/classloader/process values it reads between physical and sandbox runs.

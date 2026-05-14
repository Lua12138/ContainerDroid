# dlsym pthread_create replacement exposes libblackbox pointer and regresses BestV startup

## Attempt

After adding `dlopen` / `android_dlopen_ext` hooks so newly loaded native
libraries are PLT-patched before `JNI_OnLoad`, returned BlackBox's generic
`pthread_create` wrapper from `blackbox_dlsym(handle, "pthread_create")`.

The change was package-agnostic and intended to catch Jiagu/libffi dynamic
calls that bypass ordinary PLT slots.

## Evidence

Artifact:

- `/tmp/20260518_bestv_dlopen_patch_process_probe.logcat`

The early native-load hook worked and proved the libffi path:

```text
native dlopen probe api=android_dlopen_ext ... path=.../.jiagu/libjiagu.so
native dlsym probe symbol=pthread_create replacement=1 ... resultMap=.../libblackbox.so callerOff=0xbb0c callerMap=.../.jiagu/libjiagu.so
native process probe api=pthread_create ... callerOff=0x141223 callerMap=.../.jiagu/libjiagu.so startOff=0x14124d
```

However, BestV regressed relative to the previous baseline:

- `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`: repeated
- Zygote reported signal 9.
- The run did not reach `BesTVConfig`, `SysEnvAdapter`, or the previous real
  app initialization window.

The likely observable difference is that `dlsym("pthread_create")` returned an
address in `libblackbox.so` instead of `libc.so`. A protector that checks the
returned function pointer, `dladdr`, or `/proc/self/maps` can detect this before
or during its libffi call.

## Conclusion

Do not return BlackBox wrapper pointers from `dlsym` by default. Gate
replacement behind an explicit diagnostic property (`debug.blackbox.dlsym_replace`)
or use a lower level hook that preserves the original libc function address
surface.

Follow-up with replacement disabled (`replacement=0`) still regressed if
`dlsym` itself and immediate post-`android_dlopen_ext` PLT re-patching were
enabled during `JNI_OnLoad`: BestV reached `AppContext attachBaseContext` but
died before `BesTVConfig`. This means even log-only dlsym interposition changes
the linker caller surface, and pre-`JNI_OnLoad` GOT patching is observable.
Therefore both should remain explicit diagnostics:

- `debug.blackbox.dlsym_probe` for dlsym logging/interposition;
- `debug.blackbox.early_dlopen_repatch` for immediate post-dlopen PLT re-scan.

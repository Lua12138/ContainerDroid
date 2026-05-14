# thumb-aware direct metadata hook passes Tester but does not pass BestV

## Context

Implemented a package-agnostic direct libc metadata hook:

- `access/stat/lstat/readlink/mkdir` real libc entries are patched.
- `2 mod 4` Thumb entries use an alignment-aware literal jump instead of Pine's misaligned direct jump.
- Wrappers forward through unpatched lower-level `*at` libc APIs, avoiding copied libc-entry backup trampolines.

## Verification that did pass

Tester run:

```text
/tmp/20260518_tester_thumb_aware_metadata.logcat
/tmp/20260518_tester_thumb_aware_metadata.png
```

Result:

- Screenshot reached the expected Apple page.
- No `Fatal signal`, no `SIGSEGV`, and no `BProcessManager: App Died`.
- Metadata hook installed all 5 entries, with thumb-aware patches for `access/lstat/mkdir`.

## BestV evidence

BestV run:

```text
/tmp/20260518_bestv_thumb_aware_metadata.logcat
/tmp/20260518_bestv_thumb_aware_metadata.png
```

Decisive lines:

```text
NativeCore: native direct libc metadata hook patched=5 attempted=5
NativeCore: native termination shield blocked api=raise ... signal=9 caller=0xba98b673
RuntimeExitProxy: blocked java.lang.System.exit(1) ... com.stub.StubApp.attachBaseContext:223
UI_UTILS: Context = null
BesTVConfig: configFilePath=/cus_config/defaultConfig.properties
BesTVConfig: TARGET_OEM=SXYD
```

Screenshot stayed at the splash screen. The real application still was not initialized correctly.

## Conclusion

The Thumb direct-jump bug is fixed and the hook is stable enough for Tester, but this is not the acceptance root cause for BestV. The same runtime-unpacked libjiagu branch still self-kills before setting up the real app class/context.

Do not retry direct `access/stat/lstat/readlink/mkdir` metadata work as the next BestV acceptance fix. The next investigation should collect the actual libjiagu environment predicate inputs immediately before `raise(SIGKILL)`, especially direct `*at` metadata/syscall calls, classloader/native-library-directory values, and maps follow-up paths.

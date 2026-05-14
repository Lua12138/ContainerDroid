# direct libc metadata hook with *at forwarding still crashes on misaligned Thumb entries

## Context

After `direct_libc_metadata_hook_backup_crashes_webview.md`, the metadata wrappers were changed to avoid copied libc backups and forward through unpatched lower-level `*at` APIs.

## Runtime evidence

Tester run:

```text
/tmp/20260518_tester_direct_metadata_at_forward.logcat
```

Decisive lines:

```text
NativeCore: native direct libc metadata hook symbol=lstat target=0xe54c603b replacement=0xbafa3871
libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x3871f000 in tid ... (ThreadPoolSingl)
DEBUG: r2 e54c603b
DEBUG: pc 3871f000
BProcessManager: App Died: com.example.tester ...
```

The fault PC `0x3871f000` is not random: it is formed from the low half of the Thumb replacement pointer `0xbafa3871` plus the `f000` halfword from Pine's `ldr.w pc, [pc, #0]` direct-jump template.

## Conclusion

The crash is caused by writing Pine's literal-load Thumb direct jump at libc functions whose real code address is `2 mod 4` (`lstat`, also `access`/`mkdir` on this device). The PC-relative literal load reads from the wrong halfword-aligned location and jumps to a corrupted address.

Do not retry by changing wrapper forwarding only. Direct Thumb entry hooks must be alignment-aware: either skip `2 mod 4` targets or use a custom jump template that pads to a 4-byte literal address.

# Default declared-field JNI diagnostics can perturb BestV native failure path

## Attempt

Enhanced the generic `JniDiagnosticsHook` so every failed `GetFieldID` /
`GetStaticFieldID` also reflects the failing class loader and declared fields.
The hook still restored the original pending Java exception and did not contain
target-specific class, package, or field names.

## Evidence

- Unit tests, target-hardcoding scan, and `assembleBlackBox32Debug` passed.
- `com.example.tester` still displayed the Apple page.
- On BestV, the enhanced metadata proved the important generic fact:
  - the failing class was loaded by a `dalvik.system.PathClassLoader`
  - the requested field existed
  - the field type was `int`
  - the field was `static=true`
- However, enabling this reflection-heavy diagnostic by default changed the
  BestV failure mode:
  - immediately after the field diagnostic line, the process attempted tombstone
    reporting and crashed with `SIGSEGV`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx` appeared, which is
    a PLAN_v3 veto condition
  - screenshot stayed black and did not match the direct physical UI

Representative artifact paths:

- `/tmp/20260517_bestv_jni_fields.logcat`
- `/tmp/20260517_bestv_jni_fields.png`

## Conclusion

Do not leave reflection-heavy declared-field diagnostics enabled by default in
the BestV acceptance path. It is useful evidence gathering, but it perturbs the
native failure path enough to trigger the one-vote veto.

If this metadata is needed again, it must be behind an explicit generic debug
switch. The default runtime should keep only lightweight failed-field lookup
logging that preserves the original exception semantics.

## Follow-up: lightweight default hook is also too risky

After gating the reflection-heavy metadata behind
`debug.blackbox.jni_field_details`, a default BestV run still produced a process
where the lightweight JNI diagnostics hook logged the failed field lookup,
followed by a Java `NoSuchFieldError`, a later SIGSEGV, and:

```text
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
```

Artifact:

- `/tmp/20260517_bestv_jni_fields_gated.logcat`

This means the JNI field diagnostics hook itself should not be enabled by
default in the acceptance path. Keep it as an explicit opt-in diagnostic only.

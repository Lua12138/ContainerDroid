# Repository Guidelines

## Project Structure & Module Organization
`app/` contains the Android UI, resources, and app entry points under `app/src/main/java` and `app/src/main/res`. `Bcore/` holds the virtualization runtime and shared core services. `android-mirror/` provides mirrored Android framework stubs and AIDL interfaces used by the core. Top-level build configuration lives in `build.gradle`, `settings.gradle`, and `gradle.properties`. Reference assets for docs and releases are in `assets/`.

## Build, Test, and Development Commands
Use the Gradle wrapper from the repository root:

- `./gradlew assembleBlackBox32Debug` builds the 32-bit debug APK.
- `./gradlew assembleBlackBox64Debug` builds the 64-bit debug APK.
- `./gradlew assembleDebug` builds all debug variants, including beta flavors.
- `./gradlew test` runs JVM unit tests for modules that define them.
- `./gradlew lint` runs Android lint with the relaxed settings in `app/build.gradle`.
- `./gradlew clean` removes Gradle build output.

## Coding Style & Naming Conventions
Match the existing style in each module. Kotlin is used mainly in `app/`; `Bcore/` and `android-mirror/` are primarily Java. Use 4-space indentation, braces on the same line, and keep method and field naming in standard Java/Kotlin `camelCase`. Types use `PascalCase`; constants use `UPPER_SNAKE_CASE`. Resource names stay lowercase with underscores, for example `activity_main.xml` and `ic_launcher_beta.png`.

## Testing Guidelines
Test coverage is currently light. Existing JVM tests live in `android-mirror/src/test/java`. Add new unit tests under `<module>/src/test/java` and instrumentation tests under `<module>/src/androidTest/java` when Android behavior must be exercised. Name test classes after the target class, such as `PackageManagerCompatTest`, and run them with `./gradlew test` or a module-specific task like `./gradlew :android-mirror:test`.

## Commit & Pull Request Guidelines
Recent history favors short, imperative commit subjects, for example `Fix HUAWEI crash & photo selection on MIUI.` or `Add UserId to requestInstallPackage`. Keep commits focused and descriptive. PRs should explain the problem, summarize the approach, note affected modules or flavors, and include screenshots for UI changes. Link related issues when applicable and preserve the existing architecture instead of introducing project-wide style changes.

## Configuration Notes
This project builds multiple product flavors (`BlackBox32`, `BlackBox64`, and beta variants). Verify the target flavor before testing ABI-specific changes, especially anything under `Bcore/` or native-hook related modules.

## Diagnostic Switches
Most runtime diagnostic switches are disabled by default and are intended for short-lived investigation runs. Prefer Android system properties for device testing, for example `adb shell setprop debug.blackbox.<name> 1`. Java-side switches that have environment-variable or Java-system-property aliases also accept truthy values `1`, `true`, `yes`, or `on` unless a row below says otherwise. Native system-property switches generally accept `1`, `true`, `TRUE`, `yes`, `YES`, `on`, or `ON`.

### Gradle/build-time switches

| Switch | Default when unspecified | Scope | Meaning |
| --- | --- | --- | --- |
| `-PblackboxDiagnosticLogcatEnabled=<bool>` | `true` | `app`, `Bcore`, `Bcore:black-binder`, `Bcore:pine-core` | Compile-time gate for diagnostic logcat output. It controls `BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED`; in `pine-core` it also becomes native macro `PINE_LOGCAT_ENABLED`. When `false`, Java logcat diagnostics guarded by this flag are compiled as disabled, BinderMonitor is hard-disabled so its BinderProxy/Parcel Pine hooks and JSONL sink are not installed, Pine/native debug logcat code is built disabled, and the app module applies `proguard-diagnostic-logcat-disabled.pro`. |
| `-PblackboxDexDumpEnabled=<bool>` | `true` | `app`, `Bcore` | Compile-time gate for dex dump support. When `false`, `BuildConfig.BLACKBOX_DEX_DUMP_ENABLED` is false, so runtime dex-dump options are ignored even if a caller enables dex dump at runtime. |
| `-PblackboxDiagnosticLogcatMinifyEnabled=<bool>` | `false` | `app` | Requests app minification for diagnostic-logcat builds. Effective app `minifyEnabled` is `true` if this switch is true or if `blackboxDiagnosticLogcatEnabled=false`; otherwise it is false. |
| `-PblackboxDebuggableEnabled=<bool>` | Same as `blackboxDiagnosticLogcatEnabled` | `app` debug build type | Controls `debuggable` and `jniDebuggable` for debug APKs. Diagnostic/no-log builds default to non-debuggable to avoid ART/JDWP debug slow paths; pass `true` explicitly only when Java/JNI debugging is required. |

### Java/runtime diagnostic switches

| Switch forms | Default when unspecified | Meaning |
| --- | --- | --- |
| `BLACKBOX_CLASSLOADER_DIAG`, `blackbox.classloader_diag`, `debug.blackbox.classloader_diag` | `false` | Enables `ClassLoaderDiagnosticsProxy`. Records `ClassNotFoundException` failures with class loader summaries and stack snippets to help locate missing/deferred classes. |
| `BLACKBOX_APP_LIFECYCLE_DIAG`, `blackbox.app_lifecycle_diag`, `debug.blackbox.app_lifecycle_diag` | `false` | Enables heavy `BActivityThread` lifecycle identity diagnostics around virtual `Application` creation. These logs touch package context directories and class loader summaries, so they are opt-in and also require diagnostic logcat support to be enabled at build/runtime. |
| `BLACKBOX_DEXLOAD_SECCOMP`, `blackbox.dexload_seccomp`, `debug.blackbox.dexload_seccomp` | `false` | Enables a diagnostic seccomp install before standalone `dalvik.system.DexFile.loadDex` flows while dex dump is enabled. This is for tracing dex-load timing; it does not enable dex dump if the compile/runtime dex-dump gates are off. This switch uses exact truthy matching: `1`, `true`/`TRUE`, `yes`, or `on`. |
| `BLACKBOX_ATTACH_SECCOMP`, `blackbox.attach_seccomp`, `debug.blackbox.attach_seccomp` | `false` | Installs the seccomp shield after `Application.attach`. Use only to debug attach-time syscall behavior. |
| `BLACKBOX_ATTACH_TERMINATION_TRAP`, `blackbox.attach_termination_trap`, `debug.blackbox.attach_termination_trap` | `false` | Installs the termination-trap seccomp shield after `Application.attach`. Use to capture termination attempts near attach-time; do not leave enabled for normal compatibility tests. |
| `BLACKBOX_ATTACH_RAW_SYSCALL_PROBE`, `blackbox.attach_raw_syscall_probe`, `debug.blackbox.attach_raw_syscall_probe` | `false` | Installs the raw-syscall termination probe after `Application.attach`. This is more invasive than ordinary Java/native logging and is meant for locating raw `exit`/`kill` style calls. |
| `BLACKBOX_RUNTIME_EXIT_SHIELD`, `blackbox.runtime_exit_shield`, `debug.blackbox.runtime_exit_shield` | `false` | Enables Java-level blocking of sandbox self-exit APIs (`System.exit`, `Runtime.exit/halt`, `android.os.Process` termination signals). When disabled, calls are observed/logged but allowed to proceed. |
| `BLACKBOX_NATIVE_TERMINATION_SHIELD`, `blackbox.native_termination_shield`, `debug.blackbox.native_termination_shield` | `false` | Enables native-level blocking of direct termination APIs and raw termination syscalls after native sandbox environment setup. Keep disabled for acceptance runs that must prove an app survives without exit blocking. |
| `BLACKBOX_DYNAMIC_PROC_MOUNTS`, `blackbox.dynamic_mounts`, `debug.blackbox.dynamic_mounts` | `false` | Makes `RuntimeExecProxy` build sanitized `/proc/mounts` output from the live device file instead of the static default fixture. |
| `BLACKBOX_STATIC_PROCESS_TRACE`, `blackbox.static_process_trace`, `debug.blackbox.static_process_trace` | `false` | Logs interactions with synthetic `Process` objects returned by sanitized shell-command handlers, including stream reads, EOF, close, `waitFor`, and `exitValue`. |
| `BLACKBOX_EXEC_TRACE`, `blackbox.exec_trace`, `debug.blackbox.exec_trace` | `false` | Logs Java `Runtime.exec` / `ProcessBuilder.start` commands and call stacks before/after execution. |
| `debug.blackbox.skip_kill_on_binder_died` | `false` | Prevents `BProcessManagerService` from calling `record.kill()` after binder death, while still logging and removing the process record. Diagnostic only; it can leave real processes alive after virtual bookkeeping has removed them. |

### Native/runtime diagnostic switches

| Switch | Default when unspecified | Meaning |
| --- | --- | --- |
| `debug.blackbox.proc_shim` | `false` | Enables the `/proc/self/maps` shim path used by native and runtime hooks. When enabled, app-visible maps reads may be served from sanitized shim descriptors instead of raw procfs. |
| `debug.blackbox.maps_path_sanitize` | `true` | Enables `/proc/self/maps` path sanitization. Public Java/framework snapshots hide sandbox runtime artifacts and writable-executable scratch mappings; app-owned native readers can still receive real ranges with app-visible paths. If set to `0`, `false`, `no`, or `off`, native maps readers can see raw host/sandbox paths. |
| `debug.blackbox.transient_maps` | `false` | Enables transient sanitized maps file descriptors for app-visible `/proc/self/maps` reads. Useful for diagnosing packers that read maps before the protected shim is ready. |
| `debug.blackbox.raw_proc_virtual` | `false` | Opts raw SVC `open`/`openat` proc reads into synthetic memfd-backed virtual `/proc` fds. Default is false because memfd semantics differ from procfs and can trigger environment checks. |
| `debug.blackbox.raw_syscall_thread_refresh` | `true` | Enables per-app-owned-pthread refresh of the raw SVC syscall probe so unpacked anonymous SVC trampolines keep raw file-syscall virtualization. If set to `0`, `false`, `no`, or `off`, refresh is disabled for diagnosis; disabling can break protected loaders that use raw SVC opens for app-private files. Hot non-path `read`/`lseek` sites are restored after a threshold to avoid tight-loop SIGTRAP overhead. |
| `debug.blackbox.process_probe` | `false` | Enables broad native process diagnostics, including process/thread/dlopen/dlsym caller resolution and extra trust for app-owned native maps readers. It also implies `debug.blackbox.file_probe`. |
| `debug.blackbox.file_probe` | `false` | Enables native file-operation diagnostic logs for direct libc and metadata hooks. It logs selected opens, stats, reads, directory operations, proc-shim accesses, and app-private path redirections. |
| `debug.blackbox.termination_probe` | `false` | Logs native termination attempts with caller map information. This records evidence but does not by itself enable termination blocking; blocking is controlled by `debug.blackbox.native_termination_shield` or its aliases. |
| `debug.blackbox.termination_memdump` | `false` | Writes diagnostic memory dumps around native termination caller/stack locations when termination metadata is available. Intended for offline reverse engineering; can produce extra files and overhead. |
| `debug.blackbox.native_crash_probe` | `false` | Installs native SIGSEGV/SIGBUS/SIGILL diagnostics that log PC/LR/SP, fault address, and map offsets before forwarding the original signal. This records crash evidence only and does not block process death. |
| `debug.blackbox.dlopen_probe` | `false` | Enables native `dlopen` probe logging for dynamic library load paths and caller locations. |
| `debug.blackbox.early_dlopen_repatch` | `false` | Re-runs native file-hook patching after successful dynamic loads. Useful when late-loaded libraries resolve libc symbols after initial hook installation. |
| `debug.blackbox.dlsym_probe` | `false` | Enables native `dlsym` probe logging, especially around dynamically resolved `pthread_create`. |
| `debug.blackbox.dlsym_replace` | `false` | Allows native `dlsym` replacement for selected symbols such as `pthread_create`, so dynamically resolved calls can still pass through the sandbox wrapper. More invasive than `debug.blackbox.dlsym_probe`. |
| `debug.blackbox.jni_field_diag` | `false` | Enables JNI field lookup diagnostics in `JniDiagnosticsHook`, recording failed field lookups with compact context. |
| `debug.blackbox.jni_field_details` | `false` | Adds detailed JNI field diagnostics. Use with or after `debug.blackbox.jni_field_diag` when compact failure information is insufficient. |
| `BLACKBOX_SECCOMP_WATCHDOG` | `false` | Starts the native SIGSYS/seccomp watchdog diagnostics path. Accepted truthy values are `1`, `true`/`TRUE`, `yes`, or `YES`. |

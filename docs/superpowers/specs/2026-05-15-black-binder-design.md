# Specification: BlackBinder Integration (Binder Interception)

## 1. Introduction
This document specifies the integration of the `binderceptor` project's core functionality into the `BlackBox` project as a new sub-project named `black-binder`. The primary goal is to provide deep Binder transaction logging for containerized applications.

## 2. Goals
- Intercept all Binder transactions (`ioctl` level) within the virtual process.
- Parse Binder parcels to extract service descriptors and transaction codes.
- Log transactions to Logcat in a human-readable format.
- Maintain `BlackBox` coding standards and architecture.
- No external binary dependencies; all required code is ported to source.

## 3. Architecture

### 3.1. Project Structure
A new module will be added: `Bcore/black-binder`.
- **Java**: `top.niunaijun.blackbox.binder.BinderceptorManager`
- **Native**:
    - `BlackBinder.cpp`: Hook implementation and JNI bridge.
    - `binderceptor/`: Ported parsing logic from `iofomo/binderceptor`.

### 3.2. Interception Mechanism
- **Technique**: GOT (Global Offset Table) Hooking.
- **Target**: `ioctl` function in `libbinder.so`.
- **Reason**: More stable and less invasive than Seccomp for simple logging; targets exactly where Binder communication happens in userspace.
- **Library**: Will use the existing `ElfImg` utility (from `pine-core`) to locate the `ioctl` symbol in the `libbinder.so` GOT.

### 3.3. Parsing Logic
Ported components from `binderceptor`:
- `BinderParcel`: Low-level parcel reading without consuming the original parcel data.
- `TBinderTokenItem`: Tracks and resolves service descriptors (e.g., `android.app.IActivityManager`).
- `TBinderInfo`: Container for transaction metadata.

## 4. Implementation Details

### 4.1. Native Layer (`BlackBinder.cpp`)
- `init()`: Initializes the hook.
- `new_ioctl()`: The wrapper function.
    - Checks if the command is `BINDER_WRITE_READ`.
    - Parses `BC_TRANSACTION` and `BR_TRANSACTION`.
    - Extracts `data.ptr.buffer` and uses `BinderParcel` to read the interface token.
    - Logs results: `[BlackBinder] -> Service: android.app.IActivityManager, Code: 1 (START_ACTIVITY_TRANSACTION)`.

### 4.2. Java Layer (`BinderceptorManager.java`)
- `init()`: Loads `libblackbox.so` (which will now include `black-binder` code).
- `enable(boolean)`: Toggles the interception.

### 4.3. Build System
- `settings.gradle`: Add `include ':Bcore:black-binder'`.
- `Bcore/build.gradle`: Add `implementation project(':Bcore:black-binder')`.
- `Bcore/src/main/cpp/CMakeLists.txt`: Include `black-binder` source files or link the library. (Preferred: compile `black-binder` into the main `libblackbox.so` to avoid multiple SO files).

## 5. Success Criteria
- When a sandboxed app is launched, Logcat displays `[BlackBinder]` tags with correct service names and codes.
- No significant performance degradation in normal app operation.
- No crashes during heavy IPC usage (e.g., scrolling in a list that fetches data from a service).

## 6. Security & Privacy
- Binder logging is for **debug only**. It must be disabled in production builds or guarded by a developer flag.
- Ensure sensitive data in parcels (if any) is not leaked beyond Logcat.

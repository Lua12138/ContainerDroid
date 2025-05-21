# ContainerDroid

ContainerDroid is a virtual engine for Android that enables cloning and running multiple instances of applications in a virtualized environment. It provides a sandboxed space where applications can run independently from the host system.

## Principles of Operation

ContainerDroid operates by intercepting and redirecting system calls and framework-level interactions between the guest application and the Android OS. The core virtualization mechanism is based on the following pillars:

### 1. Framework Hooking & Proxying
ContainerDroid employs dynamic proxies and binder interception to redirect standard Android service calls (such as `ActivityManagerService`, `PackageManagerService`, etc.) to its own internal management services. This allows the engine to:
- Spoof application identity (Package Name, UID, PID).
- Manage virtual file system paths for isolated data storage.
- Control component lifecycles (Activities, Services, Receivers) within the virtual process.

### 2. Service Virtualization
Key Android system services are emulated or proxied within the `Bcore` module. When a guest app attempts to communicate with the system, ContainerDroid's `HookManager` intercepts the binder transaction and routes it through specialized "Fake" service implementations that maintain the virtual state.

### 3. Native Layer Interception
For lower-level compatibility, ContainerDroid utilizes native hooking techniques (likely involving PLT hooking or inline hooking via libraries like Pine) to intercept C/C++ library calls. This is essential for:
- Redirecting file access to virtual data directories.
- Handling native process lifecycle events.
- Bypassing certain security checks that would otherwise detect the virtual environment.

### 4. Component Redirection (Stubbing)
To run apps without actual system-level installation, ContainerDroid uses "Stub" components declared in its own manifest. These stubs act as containers that host the guest application's components, allowing the Android system to manage the process while ContainerDroid manages the actual execution logic.

## Architecture
- **App Module**: Handles user interaction, UI, and the management interface.
- **Bcore Module**: The core virtualization engine responsible for process scheduling, hook injection, and system service emulation.

## Technical Details
ContainerDroid targets Android 5.0 through 12.0+ and supports both 32-bit and 64-bit architectures through separate process hosting.

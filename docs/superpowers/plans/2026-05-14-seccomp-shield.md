# Seccomp Shield Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an ABI-specific Seccomp-BPF shield that traps container app self-termination syscalls, logs detail plus native stack, and resumes execution.

**Architecture:** Keep Seccomp installation in `BActivityThread.bindApplication()` after `NativeCore.init()` and before target app creation. Put the filter and `SIGSYS` handling in a dedicated native unit with `__aarch64__` and `__arm__` branches, and gate installation from Java with a one-time helper so tests can cover the non-native decision logic.

**Tech Stack:** Android library module, JNI, CMake, Linux seccomp/filter APIs, `sigaction`, JUnit 4.

---

### Task 1: Add Java gating and regression test

**Files:**
- Create: `Bcore/src/test/java/top/niunaijun/blackbox/core/SeccompInstallGateTest.java`
- Create: `Bcore/src/main/java/top/niunaijun/blackbox/core/SeccompInstallGate.java`
- Modify: `Bcore/build.gradle`

- [ ] **Step 1: Write the failing test**

```java
package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeccompInstallGateTest {
    @Test
    public void allowsFirstInstallOnSupportedAbi() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertTrue(gate.tryInstall(new String[]{"arm64-v8a"}));
    }

    @Test
    public void rejectsSecondInstallEvenOnSupportedAbi() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertTrue(gate.tryInstall(new String[]{"armeabi-v7a"}));
        assertFalse(gate.tryInstall(new String[]{"armeabi-v7a"}));
    }

    @Test
    public void rejectsUnsupportedAbi() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertFalse(gate.tryInstall(new String[]{"x86"}));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.SeccompInstallGateTest`
Expected: FAIL because `SeccompInstallGate` does not exist yet.

- [ ] **Step 3: Write minimal implementation**

```java
final class SeccompInstallGate {
    private final AtomicBoolean installed = new AtomicBoolean();

    boolean tryInstall(String[] supportedAbis) {
        if (!isSupportedAbi(supportedAbis)) {
            return false;
        }
        return installed.compareAndSet(false, true);
    }

    static boolean isSupportedAbi(String[] supportedAbis) {
        if (supportedAbis == null) {
            return false;
        }
        for (String abi : supportedAbis) {
            if ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.SeccompInstallGateTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add Bcore/build.gradle Bcore/src/main/java/top/niunaijun/blackbox/core/SeccompInstallGate.java Bcore/src/test/java/top/niunaijun/blackbox/core/SeccompInstallGateTest.java
git commit -m "Add seccomp install gate"
```

### Task 2: Wire Java and JNI entrypoints

**Files:**
- Modify: `Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java`
- Modify: `Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java`
- Modify: `Bcore/src/main/cpp/BoxCore.cpp`

- [ ] **Step 1: Add Java integration points**

```java
private static final SeccompInstallGate SECCOMP_INSTALL_GATE = new SeccompInstallGate();

public static native void installSeccompShield();

public static void installSeccompShieldIfNeeded() {
    if (!SECCOMP_INSTALL_GATE.tryInstall(Build.SUPPORTED_ABIS)) {
        return;
    }
    installSeccompShield();
}
```

- [ ] **Step 2: Call it during container bind**

```java
NativeCore.init(Build.VERSION.SDK_INT);
NativeCore.installSeccompShieldIfNeeded();
IOCore.get().enableRedirect(packageContext);
```

- [ ] **Step 3: Export JNI symbol**

```cpp
void installSeccompShield(JNIEnv *env, jclass clazz) {
    blackbox::seccomp::installSeccompShield();
}
```

- [ ] **Step 4: Run targeted test**

Run: `./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.SeccompInstallGateTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java Bcore/src/main/cpp/BoxCore.cpp
git commit -m "Wire seccomp shield installation"
```

### Task 3: Add ABI-specific native shield

**Files:**
- Create: `Bcore/src/main/cpp/SeccompShield.h`
- Create: `Bcore/src/main/cpp/SeccompShield.cpp`
- Modify: `Bcore/src/main/cpp/CMakeLists.txt`
- Modify: `Bcore/build.gradle`

- [ ] **Step 1: Implement dedicated native unit**

```cpp
namespace blackbox::seccomp {
void installSeccompShield();
}
```

Include:
- `SIGSYS` handler registration with `SA_SIGINFO | SA_NODEFER`
- BPF filter for `exit`, `exit_group`, `kill`, and `tgkill` on arm32
- register snapshots, `si_call_addr`, pid/tid logging
- native backtrace logging
- ABI-specific PC advance logic

- [ ] **Step 2: Restrict ABIs and compile the new source**

Run edits:
- Set `abiFilters 'armeabi-v7a', 'arm64-v8a'`
- Add `SeccompShield.cpp` to the `blackbox` shared library

- [ ] **Step 3: Build the debug variant**

Run: `./gradlew :Bcore:assembleDebug`
Expected: PASS with native library built for `armeabi-v7a` and `arm64-v8a`

- [ ] **Step 4: Commit**

```bash
git add Bcore/build.gradle Bcore/src/main/cpp/CMakeLists.txt Bcore/src/main/cpp/SeccompShield.cpp Bcore/src/main/cpp/SeccompShield.h
git commit -m "Add seccomp shield native handler"
```

### Task 4: Final verification

**Files:**
- Verify only

- [ ] **Step 1: Run JVM regression test**

Run: `./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.SeccompInstallGateTest`
Expected: PASS

- [ ] **Step 2: Run module build verification**

Run: `./gradlew :Bcore:assembleDebug`
Expected: PASS

- [ ] **Step 3: Inspect diff**

Run: `git diff --stat`
Expected: Only the planned Seccomp shield files and build/config changes

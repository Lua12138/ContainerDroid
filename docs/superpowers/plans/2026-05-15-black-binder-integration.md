# BlackBinder (Binder Interception) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Binder interception logging into BlackBox by porting core logic from `iofomo/binderceptor` into a new `black-binder` sub-project.

**Architecture:** A GOT (Global Offset Table) hook on `ioctl` in `libbinder.so` triggers a custom wrapper that uses ported `binderceptor` parsing logic to log transaction details (Service Name, Code) to Logcat.

**Tech Stack:** C++, JNI, Android NDK, CMake, Gradle.

---

### Task 1: Initialize black-binder Module

**Files:**
- Create: `Bcore/black-binder/build.gradle`
- Modify: `settings.gradle`
- Modify: `Bcore/build.gradle`

- [ ] **Step 1: Create `black-binder/build.gradle`**
- [ ] **Step 2: Update `settings.gradle`**
- [ ] **Step 3: Update `Bcore/build.gradle`**
- [ ] **Step 4: Commit**

### Task 2: Port Binderceptor Core Source

**Files:**
- Create: `Bcore/black-binder/src/main/cpp/binderceptor/inc/*.h`
- Create: `Bcore/black-binder/src/main/cpp/binderceptor/src/*.cpp`

- [ ] **Step 1: Copy ported files from temporary storage to the project**
- [ ] **Step 2: Commit ported code**

### Task 3: Implement Native Interception Logic

**Files:**
- Create: `Bcore/black-binder/src/main/cpp/BlackBinder.cpp`
- Create: `Bcore/black-binder/src/main/cpp/BlackBinder.h`

- [ ] **Step 1: Write `BlackBinder.h`**
- [ ] **Step 2: Write `BlackBinder.cpp`**
- [ ] **Step 3: Commit native implementation**

### Task 4: Java Integration and Build System Update

**Files:**
- Create: `Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BinderceptorManager.java`
- Modify: `Bcore/src/main/cpp/CMakeLists.txt`
- Modify: `Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java`

- [ ] **Step 1: Create `BinderceptorManager.java`**
- [ ] **Step 2: Update `Bcore/src/main/cpp/CMakeLists.txt`**
- [ ] **Step 3: Update `BlackBoxCore.java`**
- [ ] **Step 4: Commit integration**

### Task 5: Final Verification

- [ ] **Step 1: Build the project**
- [ ] **Step 2: Install and Verify**
- [ ] **Step 3: Commit changes**

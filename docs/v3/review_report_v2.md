第一部分：核心 C++ 底层拦截引擎 (最高风险区)
涉及文件：
 1. Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp
 2. Bcore/src/main/cpp/RawSyscallTerminationProbe.h
 3. Bcore/src/main/cpp/SeccompShield.cpp
 4. Bcore/src/main/cpp/SeccompShield.h
 5. Bcore/src/main/cpp/Hook/NativeFileHook.cpp (新增，高达 +5708 行)
 6. Bcore/src/main/cpp/Hook/JniDiagnosticsHook.cpp
 7. Bcore/src/main/cpp/Hook/JniDiagnosticsHook.h
 8. Bcore/src/main/cpp/Hook/RuntimeHook.cpp
 9. Bcore/src/main/cpp/Hook/UnixFileSystemHook.cpp
 10. Bcore/src/main/cpp/IO.cpp
 11. Bcore/src/main/cpp/IO.h
 12. Bcore/src/main/cpp/Utils/fake_dlfcn.cpp
 13. Bcore/src/main/cpp/BoxCore.cpp
 14. Bcore/src/main/cpp/CMakeLists.txt
 15. Bcore/pine-core/src/main/cpp/android.cpp
 16. Bcore/pine-core/src/main/cpp/android.h

1. 内存硬扫与原始系统调用拦截 (RawSyscallTerminationProbe.cpp)
 * 逐行审计发现：文件实现了一个暴力扫描 /proc/self/maps 的逻辑，寻找 [anon] 匿名段和可执行段中的 svc 0 (ARM32 系统调用指令)，通过 mprotect 修改页权限，将其替换为 bkpt (0xbe00) 触发 SIGTRAP 进而接管 sys_exit
 和 sys_kill。拦截后强行令 mc.arm_r0 = 0，修改 mc.arm_pc 越过断点。
 * 交叉验证 (AOSP)：在 AOSP 的 bionic/libc 中，syscall 退出类函数（如 _exit）在内联汇编执行完毕后，通常紧跟 __builtin_trap() 或 udf #0 指令。
 * 风险点 (Risk) [极高]：如果你在 SIGTRAP 处理函数中放行（将 PC 指向下一条指令），App 会立刻执行到 __builtin_trap() 从而触发 SIGILL (Illegal Instruction) 崩溃。多线程下进行 mprotect 极易引发竞态条件导致
 SIGSEGV。
 * 收益 (Benefit)：能够防御顶级加壳工具（如某些 VMP）通过内联汇编直接发起的静默自杀。
 * 无法交叉验证内容：加固壳在匿名内存（如 [anon:dalvik-jit-code-cache]）中动态释放的内联 svc 代码位置和时机无法预测。如果探测器扫描期间目标代码正在执行，会直接导致内核抛出总线错误。此外，代码仅做了 #if
 defined(__arm__)，意味着在 64 位 (arm64-v8a) 环境下该文件就是一段不执行的死代码。

2. 内核级 Seccomp 屏障增强 (SeccompShield.cpp)
 * 逐行审计发现：注入了针对 kSysExitGroup, kSysKill, kSysTgkill 的 BPF 过滤指令，遇到针对进程组或自身的终止信号（SIGKILL, SIGTERM）时返回 kSeccompReturnTrap。
 * 交叉验证 (AOSP)：BPF 汇编指令集与 Linux Kernel 的 seccomp_data 结构体偏移量（如 offsetof(struct seccomp_data, args[1])）完全吻合。
 * 风险点 (Risk) [中]：虽然比硬扫内存安全，但在 Trap 处理器中返回后，仍需面对 Bionic libc 对 exit 返回值的致命断言（与上述相同）。

3. 巨型原生文件重定向引擎 (NativeFileHook.cpp + IO.cpp + UnixFileSystemHook.cpp)
 * 逐行审计发现：NativeFileHook.cpp (5708行) 大量使用宏定义拦截了包括 openat, fstat, readlinkat, opendir 等几乎所有底层 IO 操作。IO.cpp 中新增了 reverseRedirectPathWithAlias 函数，引入了对 Android 7.0+
 /data/user/0/ 和 /data/data/ 路径硬编码互相转换的逻辑。
 * 交叉验证 (AOSP)：AOSP 的路径规范化在不同版本间存在细微差异。BlackBox 在这里引入了双向重定向（将沙盒真实路径再伪装回虚拟路径返回给 App）。
 * 风险点 (Risk) [高]：底层的全局 Inline Hook 极易引发堆栈溢出递归。如果在 Hook 的回调内部，使用了任何底层也调用了 open/stat 的 libc 函数（如打印日志的 __android_log_print），将导致无限递归崩溃。
 * 无法交叉验证内容：部分 OEM (如华为、小米) 对底层的 openat 进行了魔改（例如增加权限校验参数）。这种针对标准 NDK 函数的暴力 Hook 可能在特定非原生 ROM 上造成签名不匹配导致启动即崩溃。

---

第二部分：Binder 级通讯劫持
涉及文件：
 17. Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BinderEvent.java
 18. Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BinderMethodMapping.java
 19. Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BlackBoxBinderMonitor.java
 20. Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BinderPayloadSummary.java
 21. Bcore/src/main/java/top/niunaijun/blackbox/fake/service/PackageManagerBinderInterceptor.java

 * 逐行审计发现：不依赖 Java Proxy，直接在 Binder 的 onTransact 层拦截 IPackageManager。通过重写 Parcel 对象，劫持了 getPackageInfo 和 getApplicationInfo。
 * 交叉验证 (AOSP)：在覆写 Parcel 返回值时，执行了：

 1 packageInfo.writeToParcel(reply, 0);
 2 reply.setDataPosition(0);
 * 风险点 (Risk) [高，这是一个潜在 Bug]：在 AOSP Binder 驱动传输机制中，服务端返回给客户端的有效数据大小依赖于 Parcel.dataSize()。执行 setDataPosition(0) 仅移动了指针而没有截断数据。尽管 Android 的 C++
 层读取机制可能容错，但在跨进程反序列化时，这违背了标准的 AIDL 生成规范，极易在 Android 12+ 引发 BadParcelableException。
 * 收益 (Benefit)：即使 App 绕过 ServiceManager 直接通过 C++ 拿到 Binder Proxy，依然能够被骗过。

---

第三部分：系统服务与环境深度伪装 (Java 层 Fake Services)
涉及文件 (由于结构高度同质化，合并审计)：
 22. ActivityThreadIdentityProxy.java
 23. ApplicationAttachSeccompProxy.java
 24. ClassLoaderDiagnosticsProxy.java
 25. ContextDataDirProxy.java
 26. DexDumpProxy.java
 27. FileMetadataProxy.java
 28. HCallbackProxy.java
 29. IActivityManagerProxy.java
 30. IConnectivityManagerProxy.java
 31. IPackageManagerProxy.java
 32. IUserManagerProxy.java
 33. IWifiManagerProxy.java
 34. NetworkInterfaceMacProxy.java
 35. RuntimeExecProxy.java
 36. RuntimeExitProxy.java
 37. ServiceManagerProxy.java
 38. OsStub.java
 39. BProcessManagerService.java
 40. PackageManagerCompat.java
 41. HookManager.java

 * 逐行审计发现：
 * RuntimeExecProxy: 劫持了 Runtime.exec，过滤了诸如 su, sh, pm 等敏感系统命令，防止被识别沙盒。
 * NetworkInterfaceMacProxy: 通过反射拦截并硬编码返回了固定的虚拟 MAC 地址。
 * OsStub: 接管了 libcore.io.Os 接口，在 Java 层级对 stat, lstat 实施了第二道重定向防护。
 * 交叉验证 (AOSP)：
 * 在 Android 11 (API 30)+，AOSP 已经严格限制普通应用获取真实 MAC 地址（默认返回 02:00:00:00:00:00）。NetworkInterfaceMacProxy 强制伪造特定 MAC 地址可能会适得其反，反而被第三方风控 SDK 识别为环境异常。
 * 风险点 (Risk) [低-中]：Java 层的动态代理技术非常成熟。唯一风险在于 RuntimeExecProxy 的过度拦截可能导致依赖正当 shell 脚本（如 ping 测试网络）的 App 功能失效。
 * 收益 (Benefit)：构建了一套极其细致的环境沙盒，有效抹平了设备指纹。

---

第四部分：Android 生命周期重构与自动化脱壳
涉及文件：
 42. Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java
 43. Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java
 44. Bcore/src/main/java/top/niunaijun/blackbox/core/IOCore.java
 45. Bcore/src/main/java/top/niunaijun/blackbox/core/SeccompInstallGate.java
 46. Bcore/src/main/java/top/niunaijun/blackbox/utils/AbiUtils.java
 47. Bcore/src/main/java/top/niunaijun/blackbox/utils/compat/ContextCompat.java
 48. Bcore/pine-xposed-res/src/main/java/top/canyie/dreamland/utils/IOUtils.java
 49. Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/AppInstrumentation.java
 50. Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/BaseInstrumentationDelegate.java

 * 逐行审计发现：
 * NativeCore.java 引入了庞大的 dumpDexByteBuffers 逻辑。它不仅在 BActivityThread 的生命周期注入，还读取每个 ByteBuffer 的内存片段，计算 SHA-1，判断若是 PK (ZIP) 格式或 dex\n 特征，则写入本地磁盘。
 * BActivityThread.java 引入了 syncInitialApplicationFromRuntime，通过反射强制同步 Thread 和 LoadedApk 中的实例。
 * 交叉验证 (AOSP)：
 * AOSP 中 DexFile 对象在内存中展开为 ByteBuffer 的方式在 ART 虚拟机不同版本中差异巨大。
 * 风险点 (Risk) [极高 - 性能核弹]：
 * dumpDexByteBuffers 在主线程执行高密度的 I/O 与 SHA-1 哈希计算。对于体量稍大、包含上百个类包的应用程序，该操作会直接卡死主线程，导致无条件 ANR。
 * 磁盘耗尽：如果不加以总量限制，壳程序在运行期间动态释放代码的行为将产生上万个文件，瞬间将手机存储挤爆。
 * 收益 (Benefit)：强大的“内存抓取”式脱壳能力，无需 root 即可实现对加固 App 核心代码的自动化 dump。
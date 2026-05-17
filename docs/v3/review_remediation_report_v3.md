# review_report_v1/v2 整改闭环报告

- 日期：2026-05-19
- 代码基线：在 `83ddf90` 之后继续整改显示/资源兼容性
- 输入审计文档：`docs/v3/review_report_v1.md`、`docs/v3/review_report_v2.md`
- 结论：两份审计报告已逐条核验。属实且适合本轮修复的项目已改；不成立、证据不足或不适合本轮修复的项目均在下表显式说明，没有静默忽略。

## 交叉验证原则

1. 以当前源码、source test、Gradle 构建、真实设备 logcat 与截图为主证据。
2. 对退出拦截语义，交叉核验 Linux seccomp `SECCOMP_RET_TRAP` 和 bionic `_exit` 不可返回路径：exit/exit_group 不能简单伪造成成功后继续执行 syscall 后继指令。
3. 对 Binder PM reply 语义，交叉核验本项目的 Java 层 `BinderProxy.transact` hook：这里直接写调用方传入的 `reply` Parcel，不经过 binder driver，所以需要把 `reply.dataPosition` 复位给调用方 proxy 读取。
4. 对 Tester 发现的环境差异，先跑物理基线，再跑沙盒对比，再做通用修复；禁止目标包名硬编码。
5. 每个改动后均用 source test、构建或设备验收交叉验证；验证失败的尝试已撤回并在报告中记录。

## v1 风险逐条闭环

| v1 编号 | 交叉核验结论 | 处理 | 证据与理由 |
| --- | --- | --- | --- |
| R1 `IO.cpp` `malloc` 后 `strlen(result)` 未初始化 UB | 属实 | 已修复 | `Bcore/src/main/cpp/IO.cpp` 改为 `memset(result, 0, result_len)`，并保留空指针检查；`NativeFileHookSourceTest` 覆盖该反回归点。收益是消除未定义行为；风险低。 |
| R2 dex cookie 在 native dump 成功前去重 | 属实 | 已修复 | `NativeCore.dumpDexCookie` 改为 native 成功后才写 `DUMPED_DEX_KEYS`，失败按 key 记录重试次数；`DEX_COOKIE_MAX_FAILED_ATTEMPTS=3` 防止无限重试。BestV 最新沙盒日志 dump 出 payload dex `sha1=81069652080f469c9417b3928b773983684858ee`。 |
| R3 `syscall` 包装盲读 6 个 varargs | 属实 | 已修复已知/处理路径，未知 syscall 保留残余风险 | 新增 `syscallArgumentCount`、`takeSyscallArgsForNumber`、open/openat mode 条件读取；`__NR_statfs64` 按 Android 32-bit 三参处理。Tester 曾复现 `syscall_statfs errno=14 Bad address`，修正后最新 Tester sandbox `PASS failCount=0`。未知 syscall 无法从 C varargs 安全推断实参数量，仍记录为残余风险。 |
| R4 直接 libc/GOT/inline hook 的 OEM/ABI 脆弱性 | 部分属实 | 不做大范围重构 | 当前实现已有 ABI 分支、递归防护、属性/诊断门控和 source test。该风险需要多 ROM/多 ABI 矩阵才能进一步定量；本轮无证据显示它导致当前失败，因此不做高风险大改，避免破坏已通过路径。 |
| R5 termination shield 改变正常退出语义、调用栈不足 | 属实 | 已部分修复 | `NativeFileHook.cpp` 增加 `dumpBlockedNativeTerminationFrames` 输出 bounded native backtrace；`RawSyscallTerminationProbe.cpp` 与 `SeccompShield.cpp` 对 exit/exit_group 改为 LR 返回，避免落入 bionic fatal tail。合法自退出语义改变仍是残余风险。 |
| R6 `/proc/self/maps` early shim 固定 FD/竞态 | 部分属实 | 不改固定 FD 诊断模型；另补 Java 层 maps 快照 | 固定 FD shim 仍属于诊断/早期路径，本轮不改 fd 生命周期，避免引入更大回归。针对 Tester 的 maps 暴露，新增 `IOCore.redirectProcMapsPath` 生成应用可见的 `/blackbox/proc/<pid>/maps` 快照，隐藏 BlackBox/Pine/WX 映射；最新 Tester sandbox `blackboxPathCount=0`、`writableExecutableCount=0`。 |
| R7 raw syscall text patch 风险 | 属实 | 部分修复，保留残余 | 修复 exit/exit_group 恢复点；保留 mprotect/text patch race、ARM-only 范围限制和完整性校验风险。该能力仍应作为诊断/高风险路径，而不是无条件长期策略。 |
| R8 seccomp 不可逆/thread coverage/exit trap 风险 | 属实 | 部分修复，保留残余 | `SeccompShield.cpp` 对 exit/exit_group 使用 `emulateBlockedProcessExitReturn`，arm64 走 `regs[30]`、arm 走 `arm_lr`。seccomp 安装不可逆、TSYNC/fallback 覆盖差异是机制限制，本轮不伪称已解决。 |
| R9 `RuntimeExecProxy` 默认 always trace | 属实 | 已修复 | 新增 `BLACKBOX_EXEC_TRACE` / `blackbox.exec_trace` / `debug.blackbox.exec_trace` 开关。默认保留必要命令清洗/替换，广义未处理命令 tracing 默认关闭，降低行为面。 |
| R10 `BProcessManager` binder death 误判 | 当前证据未证实 | 不改默认死亡判定 | 本轮 BestV 失败点经交叉验证主要来自 PM reply reset 回归、maps/network/视觉差异等；恢复正确路径后最新 BestV sandbox 120s 无 `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`。默认 binder death 仍是进程死亡强信号；不加入“跳过 kill”默认逻辑，以免掩盖真实崩溃。后续如出现 appThread 桥误死证据，再加 `/proc/<pid>`/heartbeat 二次确认。 |
| R11 Context/dataDir native bypass | 部分属实 | 不做额外改动，继续以 Tester 覆盖 | Tester 覆盖 Java/native 文件、statfs/statvfs、readlink/getdents 等路径；最新 sandbox `environment_assessment PASS failCount=0 warnCount=0`。但 Tester 非形式化穷尽证明，后续新增探测继续按物理基线对比补齐。 |
| R12 PM Binder parcel 格式依赖 | 审计删除建议在本项目语境下不成立 | 保留 `reply.setDataPosition(0)` 并补测试说明 | 普通 Binder stub 不需要手动 reset；但本项目在 Java 层 `BinderProxy.transact` hook 中直接写 caller `reply`。A/B 验证：删除 reset 后 BestV `libjiagu.so JNI_OnLoad` 返回 `JNI_ERR`；恢复后 BestV 进入真实 UI。 |
| R13 网络接口模拟不完整 | 属实 | 已修复为通用安全模型 | `OsStub` 现在提供 `dummy0,wlan0,lo` 核心接口候选，`StructIfaddrs.hwaddr` 返回空数组而非固定 MAC；不再依赖目标包名。最新物理与沙盒 Tester 均为 `interfaceCount=3`、`hardwareAddressCount=0`、`interfaceNames=dummy0,wlan0,lo`，sandbox `warnCount=0`。 |
| R14 `resetAppComponentFactory` 缺少审计日志、可能改变行为 | 属实 | 已修复日志；默认行为保留 | `BActivityThread.resetAppComponentFactory` 增加泛化日志；该逻辑仍仅针对 known Core/AppCompat factory 类名，不含目标包名。若后续普通 app 回归，可再改为失败后 fallback。 |
| R15 验收文档状态滞后 | 属实 | 已更新为当前真实状态 | `docs/v3/LATEST_ACCEPTANCE_STATE.md`、`docs/v3/COMPLETION_AUDIT.md` 已写入 2026-05-18 最新 Tester/BestV 证据；严格说明截图只达到语义一致，不把动态像素差异误写成字节完全一致。 |

## v2 风险逐条闭环

| v2 条目 | 交叉核验结论 | 处理 | 证据与理由 |
| --- | --- | --- | --- |
| 1. RawSyscallTerminationProbe 把 ARM svc 改为 bkpt 后，exit syscall 继续执行会落入 bionic fatal tail | 属实 | 已修复 exit/exit_group 恢复点 | `RawSyscallTerminationProbe.cpp` 增加 `isProcessExitSyscall` 与 `resumeBlockedProcessExit`，exit/exit_group 设置成功返回值后跳 `lr`，kill/tgkill 仍跳下一条。mprotect race 与非 ARM 支持范围保留为残余风险。 |
| 2. SeccompShield trap return 仍会落入 bionic fatal assertion/trap | 属实 | 已修复 | `SeccompShield.cpp` 增加 `emulateBlockedProcessExitReturn`；arm64 使用 link register `regs[30]`，arm 使用 `arm_lr`。普通 kill/tgkill 仍按成功 syscall 恢复。 |
| 3. NativeFileHook/IO 递归、OEM 风险 | 部分属实 | 已修复明确 bug，保留大面残余 | 修复 `IO.cpp` UB、known syscall 参数读取、`statfs64` 三参；原有 `ScopedInternalFileProbe` 等递归防护保留。OEM 差异需要设备矩阵，未静默忽略。 |
| 4. Binder PM interceptor `reply.setDataPosition(0)` 可能非标准 | 在本项目语境下不成立 | 不删除，已恢复并记录 | 删除 reset 的 A/B 设备结果导致 BestV `JNI_ERR returned from JNI_OnLoad`；恢复后 BestV 120s 正常进入真实 UI。原因是本项目 hook 直接写 caller reply，不经 binder driver。 |
| 5. Java fake services：RuntimeExec 过拦截；NetworkInterfaceMacProxy 固定 MAC | RuntimeExec 属实；固定 MAC 描述与源码/策略不完全相符；网络接口缺口属实 | RuntimeExec 已修；网络改为安全空 MAC 枚举 | `RuntimeExecProxy` 广义 tracing 默认关闭；`OsStub`/网络枚举现在沙盒与物理 Tester 对齐为 `dummy0,wlan0,lo` 且 `hardwareAddressCount=0`。未发现生产代码固定目标 MAC。 |
| 6. NativeCore ByteBuffer dex dump 性能/磁盘风险 | 属实 | 已修复 | `NativeCore` 增加每次最多 16 个 buffer、单 buffer 16MiB、总量 32MiB 限制，并只在实际写出后计数。BestV 仍 dump 出真实 payload dex。 |

## 本轮新增且已交叉验证的发现

1. `__NR_statfs64` 参数数量回归：
   - 失败证据：Tester 曾出现 `verdict=FAIL failedChecks=io.native_statfs.app_data` 和 `syscall_statfs rc=-1 errno=14 Bad address`。
   - 根因：Android 32-bit `SYS_statfs64` 是 `path, sizeof(struct statfs64), buf` 三参，本轮初始实现误按二参读取。
   - 修复：`NativeFileHook.cpp` 中 `case __NR_statfs64: return 3;`；`NativeFileHookSourceTest` 固化。
   - 验证：最新 Tester sandbox `verdict=PASS failCount=0 warnCount=0`。

2. PM Binder reply reset 误删回归：
   - 失败证据：删除 `reply.setDataPosition(0)` 后 BestV 120s 出现 `JNI_ERR returned from JNI_OnLoad`。
   - 根因：本项目 Java 层 transact hook 直接写调用方传入的 `reply`，调用方随后从当前 dataPosition 读取。
   - 修复：恢复三处 `reply.setDataPosition(0)`，测试改为要求保留并说明原因。
   - 验证：最新 BestV sandbox 120s 无目标包 `BProcessManager: App Died`、无 `FATAL EXCEPTION`/`JNI_ERR`/`Fatal signal`，出现 `BesTVConfig` 与 `IqiyiActivity`。

3. `/proc/self/maps` 暴露与 Java metadata hook 回归：
   - 失败证据：Tester 曾报告 `runtime.maps.blackbox_path`、`runtime.maps.writable_executable` warning；后来尝试把 `FileMetadataProxy` 扩展到 `/proc/*/maps` 后，sandbox 重新暴露 raw maps，出现 `blackboxPathCount=19`、`writableExecutableCount=11` 和两个 timeout。
   - 根因：`FileMetadataProxy` 属 Java File metadata 层，扩大到 maps 会干扰 maps 读取路径/规范化路径，绕过 `IOCore` 的 sanitized snapshot。
   - 处理：保留 `FileMetadataProxy` 只处理 `/proc/*/cmdline`；maps 交给 `IOCore.redirectProcMapsPath` 处理。该撤回不是静默忽略，而是基于真实回归证据拒绝错误修复。
   - 验证：最新 Tester sandbox `proc_maps_summary blackboxPathCount=0 writableExecutableCount=0`，无 timeout。

4. 视觉兼容黑边：
   - 失败证据：BestV 物理直跑存在系统 compatibility letterbox，早期 sandbox 无黑边，语义一致但截图不一致。
   - 修复：新增通用 `LegacyAspectProxyActivity` 代理族，并在 `ActivityStack` 中按 `targetSdkVersion < O` 或声明 `maxAspectRatio` 选择 legacy aspect proxy；不含目标包名硬编码。
   - 验证：BestV sandbox 使用 `top.niunaijun.blackbox.proxy.LegacyAspectProxyActivity$P0`，持续渲染目标 `IqiyiActivity`，物理/沙盒内容达到语义一致；历史同帧验证曾出现与物理截图相同 SHA1 `edf62515482d949cdde43595df3b0200df1df2dc`。

5. 网络接口模型：
   - 失败证据：Tester sandbox 曾有 network warning。
   - 修复：`OsStub` 对 app 可见网络接口使用 `dummy0,wlan0,lo` 核心候选，暴露空硬件地址，遵循 Android R+ 普通 app MAC 隐私语义。
   - 验证：最新物理与沙盒 Tester 均为 `interfaceCount=3 upCount=3 loopbackCount=1 hardwareAddressCount=0 interfaceNames=dummy0,wlan0,lo`。

6. 目标 `CompatibilityInfo` 未传播到沙盒 `LoadedApk`/launch transaction：
   - 失败证据：BestV sandbox 已有 legacy letterbox，但 `version_view` 文本按错误资源/显示兼容尺度渲染；`/tmp/20260519_compatinfo_bestv_sandbox_120s.logcat` 曾出现 `create CompatibilityInfo failed: ClassCastException`，说明 mirror constructor 返回类型会把 framework `android.content.res.CompatibilityInfo` 误 cast 为 mirror interface。
   - 交叉核验：AOSP `LoadedApk` 构造/`setCompatibilityInfo` 会把 `CompatibilityInfo` 写入 `DisplayAdjustments`；`ActivityThread.AppBindData` 与 `LaunchActivityItem` 都有 compatInfo 字段。当前沙盒此前只改了 proxy activity aspect，未把目标包的 compatibility info 完整传播到 framework 资源/显示路径。
   - 修复：`BActivityThread` 基于目标 `ApplicationInfo` 创建 `CompatibilityInfo`，写入 `ActivityThread.AppBindData.compatInfo`、`LoadedApk.setCompatibilityInfo(...)`、legacy `LoadedApk.mCompatibilityInfo`，并在 `HCallbackProxy` 修复 `LaunchActivityItem.mCompatInfo`；`CompatibilityInfo` mirror constructor 改为返回 `Object`，避免 cast framework 对象失败。
   - 验证：`BActivityThreadAppComponentFactorySourceTest.bindAndLaunchUseTargetCompatibilityInfoForLegacyDisplayScaling` 固化；BestV sandbox 最新截图中 `version_view` 尺寸与物理直跑内容一致，且无 `BProcessManager: App Died`/`FATAL EXCEPTION`/`JNI_ERR`/`Fatal signal`。

## 当前验证与验收证据

本轮最终使用设备：

```text
adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp
product:dandelion model:M2006C3LC device:dandelion
```

本地验证：

```text
./gradlew :Bcore:testDebugUnitTest --tests top.niunaijun.blackbox.core.BActivityThreadAppComponentFactorySourceTest.bindAndLaunchUseTargetCompatibilityInfoForLegacyDisplayScaling
BUILD SUCCESSFUL

./gradlew :Bcore:black-binder:testDebugUnitTest :Bcore:testDebugUnitTest assembleBlackBox32Debug
BUILD SUCCESSFUL
```

设备验收：

| 包名 | 运行时长 | 结果 | 证据 |
| --- | ---: | --- | --- |
| `com.example.tester` sandbox | 100s | `environment_assessment PASS failCount=0 warnCount=0 timeoutCount=0`；maps 隐藏和网络模型均通过；截图显示 Apple.com 首页与 `ENVDIAG PASS` | `/tmp/20260519_loadedapk_compat_tester_sandbox_100s.logcat`、`/tmp/20260519_loadedapk_compat_tester_sandbox_100s.png` |
| `com.example.tester` physical | 100s | `environment_assessment PASS failCount=0 warnCount=0 timeoutCount=0`；网络接口与 sandbox 对齐；截图内容一致，差异仅状态栏动态时间/网速/电量 | `/tmp/20260519_tester_physical_fresh_100s.logcat`、`/tmp/20260519_tester_physical_fresh_100s.png` |
| `com.bestv.tv.video.iqy.tjdx` sandbox | 120s | 无目标包 `BProcessManager: App Died`、无 `FATAL EXCEPTION`、无 `JNI_ERR`、无 `Fatal signal`；出现 `BesTVConfig`、`IqiyiActivity`，payload dex dump 成功；使用 `LegacyAspectProxyActivity$P0` | `/tmp/20260519_loadedapk_compat_bestv_sandbox_120s.logcat`、`/tmp/20260519_loadedapk_compat_bestv_sandbox_120s.png` |
| `com.bestv.tv.video.iqy.tjdx` physical | 120s | 进入同一目标应用页面；无同类 fatal；截图内容与 sandbox 对齐 | `/tmp/20260519_bestv_physical_fresh_120s.logcat`、`/tmp/20260519_bestv_physical_fresh_120s.png` |

关键 payload dex：

```text
cookie_81069652080f469c9417b3928b773983684858ee.dex
```

截图 SHA1 与像素差异：

```text
f18b2153f8efc7f812cf19c878a7e0affc1341b4  /tmp/20260519_loadedapk_compat_tester_sandbox_100s.png
fdbb70ae5714bca1dac5e4f3dc6650c35e3c9898  /tmp/20260519_tester_physical_fresh_100s.png
content_match=Apple.com homepage + ENVDIAG PASS
dynamic_diff=状态栏时间/网速/电量，Apple 内容区一致

7b569341437835c829ce66ede9838e364acc130e  /tmp/20260519_loadedapk_compat_bestv_sandbox_120s.png
edf62515482d949cdde43595df3b0200df1df2dc  /tmp/20260519_bestv_physical_fresh_120s.png
content_match=legacy letterbox + same BestV installed/return-key page + version text scale aligned
pixel_diff=anti-alias/subpixel/text-edge differences remain; no semantic content delta observed
```

## 残余风险

1. raw syscall patch 仍有 text patch/mprotect race 与 ARM-only 约束；本轮只修复已证实的 exit/exit_group 错误恢复。
2. seccomp 仍有不可逆和线程覆盖差异；本轮不扩大默认策略。
3. NativeFileHook 对未知 syscall 仍用 6 参兜底，因为 C varargs 无法在运行时安全推断未知 syscall 实参个数；已覆盖当前处理路径和 Tester 发现的 `statfs64`。
4. 固定 FD `/proc/maps` early shim 仍保留诊断路径残余风险；默认验收通过依赖 Java 层 sanitized snapshot，而不是宣称固定 FD 模型已重构。
5. Tester 覆盖面大但不是形式化穷尽证明；后续新增检测项必须继续按物理基线、沙盒对比、通用修复流程执行。
6. 当前截图达到内容一致，但最新采集不是字节完全一致：Tester 差异来自状态栏动态时间/网速/电量，BestV 差异来自文本边缘/抗锯齿级像素。文档只声明内容一致，不把“字节级截图完全一致”伪报为完成。

## 外部/官方交叉核验来源

- Linux seccomp `SECCOMP_RET_TRAP` PC/SIGSYS 语义：https://man7.org/linux/man-pages/man2/seccomp.2.html
- Android bionic `_exit` syscall 后不可返回语义参考：https://android.googlesource.com/platform/bionic/
- Android Parcel/IPackageManager 相关源码入口：https://android.googlesource.com/platform/frameworks/base/

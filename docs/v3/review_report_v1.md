## 5. 主要风险点与收益

### R1. IO.cpp::replace 存在真实内存初始化 bug

证据：

Bcore/src/main/cpp/IO.cpp:22 result_len = ...
Bcore/src/main/cpp/IO.cpp:23 malloc(result_len)
Bcore/src/main/cpp/IO.cpp:24 memset(result, 0, strlen(result))

风险：

- result 刚 malloc，内容未初始化，strlen(result) 是未定义行为。
- 本次变更增加 reverse redirect 路径使用频率，可能提高触发概率。

收益：

- reverse redirect 能把内部重定向路径还原为应用可见路径，是 IO 隐藏必要能力。

建议：

- 下一 commit 优先改为：

memset(result, 0, result_len);
// 或 calloc

严重度：中高。

———

### R2. dex cookie dump 的去重时机可能导致失败后不重试

证据：

NativeCore.java:343 key = "cookie:" + Long.toHexString(cookie)
NativeCore.java:344 DUMPED_DEX_KEYS.add(key)
NativeCore.java:348 dumpDexCookieNative(...)

测试也明确固化了“native probe 前去重”：

DexNotifyDumpSourceTest.java:209-211

收益：

- 避免重复 ART cookie 内存探测，降低开销和崩溃面。
- 当前 BestV 设备证据已证明至少成功 dump 出真实 payload dex。

风险：

- 如果第一次 native dump 因 dex 尚未就绪、路径不可写、短暂 IO 错误失败，同一个 cookie 后续不会再 retry。
- 对壳分阶段加载场景可能漏 dump。

建议：

- Java 层地址 key 在 native dump 成功后再加入；或维护失败重试 TTL/计数。
- native SHA1 去重已存在，可保留内容级去重。

严重度：中。

———

### R3. syscall varargs 统一读取 6 个参数有 ABI 未定义行为风险

证据：

NativeFileHook.cpp:2777-2780
takeSyscallArgs(va_list args, long values[6]) {
for (...) values[i] = va_arg(args, long);
}

收益：

- 能统一处理 raw syscall(...) 的 IO、身份、退出相关路径。

风险：

- C varargs 在调用者实际参数少于 6 个时读取额外参数属于未定义行为。
- 当前测试多为结构性 source test，不能充分证明所有 ABI/调用形态安全。

建议：

- 按 syscall number 决定需要读取的参数个数。
- 对未知 syscall 保守透传，避免无条件读满 6 个。

严重度：中。

———

### R4. direct libc / GOT / inline hook 覆盖面强，但 ROM/ABI 脆弱性高

证据：

NativeFileHook.cpp:5639-5708 installNativeFileHooks()
NativeFileHook.cpp:5234 installDirectLibcTerminationHooks()
BActivityThread.java:354 NativeCore.setNativeTerminationShieldPackage(packageName)

收益：

- 能覆盖普通 PLT、dlsym、dlopen、pthread_create、libffi 动态调用一类反调试/自毁路径。
- 与用户提供的 libffi/pthread_create 反调试情报方向一致。

风险：

- 依赖 Pine inline hook、符号解析、loaded object patching。
- 不同 Android 版本、厂商 ROM、32/64 ABI、linker 行为可能有差异。
- 对完整性校验强的壳，inline patch 本身可能变成可检测特征。

建议：

- 保留当前通用路径，但补设备矩阵验证。
- 对高风险 patch 增加属性开关或分级启用策略。

严重度：中。

———

### R5. native termination shield 改变正常退出语义

证据：

BActivityThread.java:354
NativeFileHook.cpp:3672-3683

收益：

- 能防止/记录 kill/exit/abort/pthread_create watchdog 一类自毁路径。
- 当前 BestV 运行证据显示 UI 进入真实逻辑且无 App Died veto。

风险：

- 合法自退出、崩溃退出、进程组 kill 语义可能被改变。
- 可能造成僵持、后台残留或状态机误判。

建议：

- 默认只保护 sandbox 应用自身和明确危险 syscall。
- 增加日志中“被拦截退出原因 + caller map + Java/native stack”完整字段，便于判断是否误伤。

严重度：中。

———

### R6. /proc/maps early shim 使用固定 FD，有冲突/并发风险

证据：

NativeFileHook.cpp:3594-3655 prepare/refreshEarlyProcMapsShim()
dup2(fd, kProcMapsFd)
ftruncate/lseek/write/lseek

收益：

- 针对壳硬编码 /proc/self/maps、/dev/fd/N、maps 反查文件路径的场景有效。
- 能减少无效 maps 地址/路径导致的非法访问。

风险：

- 固定 FD 可能与应用自身 FD 使用冲突。
- refresh 过程对共享 fd 做 ftruncate/lseek/write/lseek，并发读取时可能读到中间态。
- 当前该能力默认受开关控制，降低了默认风险。

建议：

- 后续改为 per-open 独立 memfd/tmpfile，少用全局固定 fd。
- 如果必须固定 fd，至少加锁或 generation swap。

严重度：中。

———

### R7. raw syscall probe 会 patch text，诊断价值高但侵入性强

证据：

RawSyscallTerminationProbe.cpp:579-593 bkpt patch
RawSyscallTerminationProbe.cpp:614 scanProcessMaps(...)

收益：

- 对直接 ARM svc 退出路径有诊断价值，尤其是绕过 libc 的自毁。

风险：

- 修改代码段可能触发壳完整性校验。
- aarch64 支持不完整或受限。
- 不适合作为默认长期策略。

建议：

- 保持诊断开关，不默认启用。
- 优先用 seccomp/signal/ptrace-free 日志点定位，raw patch 作为最后手段。

严重度：中。

———

### R8. seccomp 诊断能力有不可逆和线程覆盖风险

证据：

SeccompShield.cpp:1286 TSYNC
SeccompShield.cpp:1298 prctl fallback
SeccompShield.cpp:1386 termination-only TSYNC
SeccompShield.cpp:1517 termination-trap TSYNC

收益：

- 能捕获直接 syscall 层退出/反调试动作，适合分析壳自毁。

风险：

- seccomp 安装不可逆。
- TSYNC 失败后 fallback 到 prctl 可能只覆盖当前线程。
- 不同 kernel/seccomp 能力差异没有完整矩阵验证。

建议：

- 继续保持 opt-in。
- log 中明确记录 TSYNC 成功/失败、fallback 范围、当前线程 ID。

严重度：中。

———

### R9. RuntimeExecProxy 当前 always trace，行为面偏大

证据：

RuntimeExecProxy.java:430 shouldTraceSandboxExec() returns true

收益：

- 能发现 getprop、id、cat /proc/...、shell 探测等环境检测。
- Tester 中相关探测已通过。

风险：

- 所有 exec 都被记录/处理，增加性能与行为可见面。
- 对依赖子进程副作用的普通 app 可能产生差异。

建议：

- 保持日志，但考虑属性开关控制“强改写/强拦截”，默认只记录最小必要信息。

严重度：中低。

———

### R10. BProcessManager 死亡判断默认仍然依赖 appThread binder death

证据：

BProcessManagerService.java:186 appThread.linkToDeath(...)
BProcessManagerService.java:212-218 logs binderAlive
BProcessManagerService.java:221-231 default record.kill()

收益：

- 当前日志更清晰，能看到 package/pid/bpid/buid/userId/binderAlive。
- 提供 debug.blackbox.skip_kill_on_binder_died 诊断开关。

风险：

- 默认没有改变死亡判定，只增强诊断。
- 如果 appThread binder 桥误死而真实进程仍有活跃逻辑，仍可能误 kill。

交叉验证：

- 当前 BestV 最终 log 无 BProcessManager: App Died。
- 但无法仅凭当前单设备运行证明不存在所有误判路径。

建议：

- 后续可在 binder death 前增加 /proc/<pid>、cmdline、status、thread count、最近 heartbeat 的二次确认，仅在确认进程不可用时 kill。

严重度：中。

———

### R11. Context/dataDir 虚拟化收益明显，但 native bypass 仍需持续验证

证据：

ContextCompat.java:85-127
ContextDataDirProxy.java:74-89
PackageManagerBinderInterceptor.java:123-149

收益：

- Java 层 ApplicationInfo.dataDir、ContextImpl、LoadedApk、PM 返回值更接近真实安装环境。
- Tester 文件矩阵 PASS 支持当前设备上行为符合预期。

风险：

- 如果 native 层绕过 hook 直接访问真实路径，仍可能看到 host/sandbox 痕迹。
- 当前 Tester 覆盖大量 Java/native 文件访问，但不是形式化穷尽证明。

严重度：中低。

———

### R12. PackageManagerBinderInterceptor 对 binder parcel 格式依赖较强

证据：

BinderPayloadSummary.parsePackageManagerCall(...)
PackageManagerBinderInterceptor.java:56/74/90 reply.writeNoException()

收益：

- 能避免虚拟包 PM 请求透传到宿主系统，修正 dataDir/sourceDir 等暴露面。

风险：

- PM binder transaction 参数在不同 Android API/ROM 有差异。
- parse 成功但签名理解错误时，可能写出不兼容 reply。
- 当前已按 Android 11 AOSP 签名核对，但没有覆盖所有 API。

建议：

- 继续保留 parse 失败 fallback。
- 增加 API 级别/transaction code 的设备矩阵测试。

严重度：中低。

———

### R13. 网络接口模拟仍不完整

证据：

- Tester 最终：

warningChecks=...network.hardware_address_visible

收益：

- 当前不会导致 Tester fail。
- 可提供比 Android placeholder MAC 更真实的接口信息。

风险：

- ap0,wlan0、硬件地址可见仍可能被高强度环境检测利用。
- loopback/IP/netlink 语义未完全建模。

建议：

- 下一阶段把 network interface 作为独立检测面，明确策略：隐藏、归一化、还是模拟物理环境。

严重度：中低。

———

### R14. resetAppComponentFactory 是通用类名兼容逻辑，但仍会改变部分 app 行为

证据：

BActivityThread.java:728-734
android.support.v4.app.CoreComponentFactory
androidx.core.app.CoreComponentFactory

收益：

- 解决部分 AppCompat/CoreComponentFactory 在虚拟环境初始化不兼容问题。
- 不是目标包名硬编码。

风险：

- 对确实依赖该 factory 自定义实例化路径的 app，可能改变组件创建行为。

建议：

- 记录触发日志。
- 若后续遇到普通 app 回归，可改为仅在检测到 factory 初始化异常后 fallback。

严重度：低到中。

———

### R15. 提交内验收文档状态滞后

证据：

docs/v3/LATEST_ACCEPTANCE_STATE.md:8 acceptance_check_exit_code: 1
docs/v3/LATEST_ACCEPTANCE_STATE.md:27 screenshot_status=failed
docs/v3/COMPLETION_AUDIT.md:6 status: not_complete

收益：

- 保留了历史失败与负证据，便于追溯。

风险：

- 与 2026-05-18 后续 /tmp/20260518_* 成功运行证据不一致。
- 后续接手者可能误以为当前 runtime 仍停留在 2026-05-17 的 failed_screenshot 状态。

建议：

- 下一 commit 更新 LATEST_ACCEPTANCE_STATE.md 和 COMPLETION_AUDIT.md，写入 2026-05-18 的 BestV/Tester 最新证据。

严重度：文档中。

———

## 6. 无法完全交叉验证的内容

以下内容我没有想当然下结论，明确列为未完全验证：

- 多 Android 版本矩阵：当前主要按 Android 11/AOSP 和当前设备验证。
- 多 ROM/linker/libc 变体：direct libc hook、GOT patch、inline hook 可能有厂商差异。
- arm64 raw syscall patch 完整性：当前 raw syscall probe 对 aarch64 覆盖不能等同 arm32。
- seccomp TSYNC 线程覆盖：需要在多线程壳运行时验证 TSYNC 成功覆盖所有线程。
- 性能影响：Binder monitor、RuntimeExecProxy、native hook 大面积启用后的长期性能没有压力测试。
- 网络环境模拟：Tester 仍有 network warning，不应视为已完全解决。
- 文档验收状态：提交内 docs 仍记录旧失败，需要后续更新。

———

## 7. 建议下一步

优先顺序：

1. 修 Bcore/src/main/cpp/IO.cpp:24 的 strlen(result) 未定义行为。
2. 调整 dex cookie dump 失败重试策略。
3. 更新 docs/v3/LATEST_ACCEPTANCE_STATE.md / COMPLETION_AUDIT.md 到 2026-05-18 最新状态。
4. 给 BProcessManager binder death 增加二次确认诊断。
5. 对 direct libc hook / seccomp / raw syscall probe 做 Android 版本与 ABI 矩阵测试。
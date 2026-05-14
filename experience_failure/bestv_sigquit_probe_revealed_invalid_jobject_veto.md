# BestV SIGQUIT/stack probe run exposed invalid jobject and veto App Died

## 现象

2026-05-17 08:02 +0800，在 ADB 已恢复后执行长窗口 BestV 启动并尝试用
`run-as top.niunaijun.blackboxa32 /system/bin/kill -3 <p0-pid>` 抓线程栈：

```bash
CAPTURE_SECONDS=80 LOGCAT_SECONDS=110 DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp ./script/install-to-device.sh com.bestv.tv.video.iqy.tjdx
```

产物：

- `/tmp/bestv_stack_attempt2.logcat`
- `/tmp/bestv_stack_sigquit3.log`
- `/tmp/bestv_stack_hang_activities.txt`
- `/tmp/bestv_stack_attempt2.exit_info`
- `/tmp/bestv_stack_attempt2.screencap.png`

这次运行命中一票否决：

```text
08:02:20.853 BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
```

exit-info 同步显示两个 `top.niunaijun.blackboxa32:p0` SIGSEGV：

```text
pid=8278 reason=2 (SIGNALED) status=11
pid=8335 reason=2 (SIGNALED) status=11
```

## 关键证据

第三个 p0 进程继续跑到 `IqiyiActivity.onCreate` 入口附近，但 JNI 检查报错：

```text
08:02:33.122 AppInstrumentation: callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity
08:02:33.242 NativeCore: jni get object class WONT replacement object=null replacement=0xf5 result=0x105 pendingException=0 callerOffset=0x11bcd1
08:02:33.253 NativeCore: jni lookup api=GetMethodID class=java.lang.invoke.MethodHandles$Lookup name=revealDirect ...
08:02:33.253 java_vm_ext.cc:577] JNI DETECTED ERROR IN APPLICATION: use of invalid jobject 0x1721da98
08:02:33.253 java_vm_ext.cc:577]     from void com.bestv.iptv.tv.IqiyiActivity.onCreate(android.os.Bundle)
```

随后 SIGQUIT 无法正常挂起主线程：

```text
Timed out waiting for threads to suspend
Thread not suspended: Thread[1,tid=8398,Runnable,...,"main"]
```

Activity/Window 状态也说明 `ProxyActivity$P0` 已成为 resumed/focused，
但没有窗口绘制，顶层可显示窗口仍是宿主 Launcher：

```text
mResumedActivity: ... ProxyActivity$P0
windows=[]
mTopFullscreenOpaqueWindowState=... top.niunaijun.blackboxa32/top.niunaijun.blackbox.app.LauncherActivity
```

## 结论

不要把 SIGQUIT/stack 抓取结果误判为单纯“线程栈缺失”。本轮已经复现了
BestV veto 失败，并且把当前根因范围进一步缩小到 post-WONT
`MethodHandles.Lookup.revealDirect` / `GetObjectClass(null)` 兼容路径：

- r4 MethodHandle 替换能让 Jiagu 继续读取 `MethodHandleImpl.type`、
  `MethodType.parameterArray`、`returnType`；
- 但后续 `revealDirect` 调用仍可能携带非空但无效的 jobject
  `0x1721da98`，导致 CheckJNI/ART 报错并使主线程无法挂起；
- 单纯重复 AppCompat probe clear 或只抓 SIGQUIT 不能通过验收。

下一步应在最小范围内验证 `revealDirect` 调用入参：先给
`CallObjectMethod*` 的 revealDirect 分支增加调用前诊断/保护，确认无效
jobject 来自 Jiagu 保存的 post-GetObjectClass 对象状态，而不是继续扩大
WONT/AppCompat 静态字段兼容面。

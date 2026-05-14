# BestV: AppCompat probe clear 后仍停在宿主 Launcher/黑屏

## 现象

2026-05-17 07:53-07:54 +0800，在 ADB 恢复后执行：

```bash
CAPTURE_SECONDS=45 LOGCAT_SECONDS=65 DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp ./script/install-to-device.sh com.bestv.tv.video.iqy.tjdx
```

构建、安装、启动成功，`top.niunaijun.blackboxa32:p0` 没有复现先前短采样里的 SIGSEGV，脚本 force-stop 前一直存活；也没有出现一票否决日志：

```text
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
```

但截图仍不合格：

- sandbox: `/tmp/bestv_long_repro_screencap.png`, sha256 `dea852c75caafb5d02a18a11344bdd51f348b00f94c23c31450943f8263784f0`, 近黑屏。
- real-device: `/tmp/blackbox_bestv_real_screenshot.png`, sha256 `54223c31f7f7289bd50d0822e7289ec808564cb4ddf01313dc1b01878b78d087`, 已显示 BestV UI/文字。

## 关键证据

这次长采样证明 AppCompat 静态字段探测清异常后能继续前进，但并未进入真实 UI：

```text
07:53:48.153 p0 NativeCore: FindClass com/bestv/iptv/tv/IqiyiActivity
07:53:48.153 p0 NativeCore: GetMethodID IqiyiActivity.onCreate
07:53:48.462 p0 NativeCore: GetStaticFieldID AppCompatSpinner$DropDownAdapter.unregisterDataSetObserver ... result=0x0
07:53:48.462 p0 NativeCore: jni field static probe miss cleared ...
07:53:54.231 system ActivityTaskManager: Launch timeout has expired, giving up wake lock!
07:54:25.833 system ActivityTaskManager: Force removing ... ProxyActivity$P0 ... app died, no saved state
```

同期直接物理环境日志有真实 UI 生命周期：

```text
07:43:01.160 com.bestv... IqiyiActivity: enter onCreate
07:43:01.917 com.bestv... IqiyiActivity: leave onCreate.
07:43:02.117 system Activity_windows_visible ... IqiyiActivity
07:43:06.198 com.bestv... IqiyiActivity: onShowRealUi
07:43:06.198 com.bestv... IqiyiActivity: afterLoaded do
```

沙盒长采样没有 `IqiyiActivity: enter onCreate`、`onShowRealUi` 或 `afterLoaded do`，SurfaceFlinger 截图目标是宿主 `LauncherActivity` 而非 `ProxyActivity$P0`：

```text
07:53:49.148 SurfaceFlinger: screenshot (top.niunaijun.blackboxa32/top.niunaijun.blackbox.app.LauncherActivity#0)
```

## 结论

不要再把当前状态判断为“BestV 崩溃”或单纯 “AppCompat probe 异常”。当前主要失败是：`ProxyActivity$P0` 对应的虚拟 BestV Activity 启动卡在 `IqiyiActivity.onCreate` 被 jiagu 查找到之后、Java 生命周期真正执行之前，最终系统 launch timeout，截图停留在宿主 Launcher/近黑屏。

下一步应优先抓取卡住时的 p0 线程栈/主线程状态，并比较沙盒与真机在 `IqiyiActivity.onCreate` 前后的关键分支，而不是继续重复 WONT/AppCompat 静态字段清异常方向。

## 2026-05-17 08:16 formal collect 补充

ADB 恢复后执行 `./script/codex.sh collect-required-packages`，BestV 产物路径：

- `/tmp/blackbox_bestv_logcat.txt`
- `/tmp/blackbox_bestv_screenshot.png`
- `/tmp/blackbox_bestv_real_logcat.txt`
- `/tmp/blackbox_bestv_real_screenshot.png`

本轮 formal gate 仍失败在截图一致性，且没有命中一票否决：

```text
veto_status=passed
screenshot_status=failed
29bf24e4b7f772de6a6c356d9487d2992bb57a69b7fbfb3e45e71a0b27a9c538  /tmp/blackbox_bestv_screenshot.png
54223c31f7f7289bd50d0822e7289ec808564cb4ddf01313dc1b01878b78d087  /tmp/blackbox_bestv_real_screenshot.png
```

沙盒 logcat 仍只证明到虚拟 instrumentation 已调用
`callActivityOnCreate: com.bestv.iptv.tv.IqiyiActivity`，并进入 WONT 静态字段兼容路径；
直接真机日志则正常出现：

```text
IqiyiActivity: enter onCreate
IqiyiActivity: leave onCreate.
Activity_windows_visible ... IqiyiActivity
IqiyiActivity: onShowRealUi
IqiyiActivity: afterLoaded do
```

注意：`codex.sh` formal collect 使用设备侧 `logcat -f`，本次在高频 JNI/NativeCore
诊断输出下出现 `logcat: Unexpected EOF!`，BestV sandbox 日志截断在
`post-static-int SIGSEGV probe installed` 附近。不要把 formal log 中未出现
post-WONT `revealDirect`/invalid-jobject 当作“该问题消失”；需要以后续
`install-to-device.sh` 长窗口或降噪采集结果为准。

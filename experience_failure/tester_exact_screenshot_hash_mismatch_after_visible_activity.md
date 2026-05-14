# Tester exact screenshot hash mismatch despite visible activity

## 现象

2026-05-17 08:17-08:18 +0800，在 ADB 恢复后执行
`./script/codex.sh collect-required-packages`，`com.example.tester` 构建、安装、
沙盒启动、直接真机启动均完成，但 formal gate 的逐字节截图比较失败：

```text
veto_status=passed
screenshot_status=failed
fa46545c24d48504efbb1e6f571cdf40a2d50497673e8bdb35033963e9972852  /tmp/blackbox_tester_screenshot.png
1e0ebc51391497dca8769dba3dc8f42d4d199260111f8d529314d73fcc2fdd9c  /tmp/blackbox_tester_real_screenshot.png
```

## 关键证据

沙盒侧不是启动失败；系统已经给 `ProxyActivity$P0` 可见窗口，实际 SurfaceFlinger
截图目标也是 tester 主界面：

```text
Activity_windows_visible ... top.niunaijun.blackboxa32/top.niunaijun.blackbox.proxy.ProxyActivity$P0
SurfaceFlinger: screenshot (com.example.tester/com.example.tester.MainActivity#0)
```

直接真机侧也显示 tester 主界面：

```text
Activity_windows_visible ... com.example.tester/.MainActivity
SurfaceFlinger: screenshot (com.example.tester/com.example.tester.MainActivity#0)
```

## 结论

不要把 tester 的 exact-hash mismatch 直接等同于沙盒基础功能崩坏。当前
BestV 仍是主 blocker；tester 需要在 BestV 修复后再用人工截图/稳定化采集确认
Apple.com 页面是否视觉一致。若继续追 exact hash，应先排除动态 WebView 内容、
采集时机、屏幕方向/letterbox 和残留前台任务影响。

# Global seccomp marker install attempt

## Attempt

Enabled `/data/local/tmp/blackbox_enable_seccomp` and reran
`com.bestv.tv.video.iqy.tjdx` through `script/install-to-device.sh` without
code changes, so the existing `ProxyContentProvider` path installed the
seccomp shield as soon as the proxy process initialized.

## Evidence

- The shield installed before `BActivityThread.handleBindApplication()` set the
  virtual uid:
  - `BlackBoxSeccomp: seccomp shield installed`
  - later: `NativeCore: NativeCore init.`
  - later: `BlackBoxSeccomp: virtual uid configured virtualUid=10001`
- The run did not reach the real BestV bootstrap path. Instead
  `com.stub.StubApp.attachBaseContext:223` called `System.exit(1)`, which
  `RuntimeExitProxy` blocked.
- After the blocked exit, `LoadedApk.makeApplication()` returned the stub
  application and provider installation failed with `ClassNotFoundException`
  for:
  - `com.bestv.ott.provider.ConfigProvider`
  - `com.bestv.iptv.adapter.TjgdAdapterInitProvider`
- The app then crashed in `ActivityThread.handleConfigurationChanged()` because
  the protected real application was not installed.
- A later seccomp trap only caught `tgkill(..., SIGKILL)` from the Java crash
  shutdown path; it did not produce the direct BestV screen.

## Conclusion

Global marker-based seccomp installation is too early and changes the Jiagu
bootstrap path back to the stub-exit failure mode. Do not use global marker
installation as the acceptance fix. If seccomp is retried, install it only from
the package bind path with package-specific gating and verify it does not
prevent the real `AppContext` bootstrap.

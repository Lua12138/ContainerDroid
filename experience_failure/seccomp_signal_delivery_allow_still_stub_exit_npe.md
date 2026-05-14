# Seccomp SIGSYS delivery allow still leaves StubApp exit / Application NPE

## Attempt

Changed the seccomp shield so only termination signal-delivery syscalls
(`SIGKILL`, `SIGTERM`, `SIGABRT`) are swallowed, while non-termination signals
including `SIGSYS` are allowed to reach the app's virtual signal handler. Also
kept `rt_sigaction(SIGSYS)` / legacy ARM `sigaction(SIGSYS)` trapped so the
virtual app handler can be stored and forwarded.

This was package-agnostic and did not contain target-app hardcoding.

## Evidence

- Targeted source tests passed:
  - `SeccompSignalDeliverySourceTest`
  - `DexNotifyDumpSourceTest`
  - `SeccompSignalMaskSourceTest`
- `assembleBlackBox32Debug` passed and rebuilt `libblackbox.so`.
- Tester still launched visibly and showed the Apple.com page:
  - `/tmp/20260517_121x_tester_sigdelivery_allow.logcat`
  - `/tmp/20260517_121x_tester_sigdelivery_allow.png`
- BestV still failed with the same early StubApp real-application swap failure:
  - `/tmp/20260517_121x_bestv_sigdelivery_allow.logcat`
  - `/tmp/20260517_121x_bestv_sigdelivery_allow.png`
  - `RuntimeExitProxy: blocked java.lang.System.exit(1) ... com.stub.StubApp.attachBaseContext`
  - `ActivityThread initial application state stage=afterSetInitialApplication localApplication=com.stub.StubApp`
  - `ActivityThread initial application state stage=afterApplicationOnCreate localApplication=com.stub.StubApp threadInitialApplication=null loadedApkApplication=null`
  - `FATAL EXCEPTION: main ... Application.getResources() on a null object reference`
- The payload dump still worked and produced the real BestV dex:
  - `cookie_19473_3_b9ad7000_4876632_0dfc4292.dex`
  - sha256 `ff2af121a9210866a133ecb4d953c271a9b1bd4ac769d874f9fc5b3f760c0de9`
  - strings include `com/bestv/iptv/tv/AppContext`,
    `com/bestv/iptv/tv/IqiyiActivity`,
    `com/bestv/ott/config/env/OttContext`, and
    `com/bestv/ott/config/adapter/SysEnvAdapter`.

## Conclusion

Allowing app-delivered `SIGSYS` is not sufficient to make the DexFile.loadDex
seccomp trigger an acceptance fix. It can remain useful as generic signal
semantics cleanup if no side effects appear, but do not retry
SIGSYS-delivery-only changes as the next standalone fix.

The decisive runtime failure is still earlier: installing seccomp before the
standalone `DexFile.loadDex` perturbs the Jiagu/StubApp loader so
`strEntryApplication`/real `Application` is not resolved, `StubApp` calls
`System.exit(1)`, and the later `Application.getResources()` NPE is secondary.

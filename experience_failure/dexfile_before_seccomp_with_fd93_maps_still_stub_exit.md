# DexFile.loadDex before-call seccomp plus fd93 maps shim still leaves StubApp exit

## Attempt

Combined two generic mitigations that had been useful independently:

- install the seccomp termination shield before deprecated standalone
  `dalvik.system.DexFile.loadDex(...)`;
- redirect direct `/proc/self/maps` and `/proc/<pid>/maps` probes to the
  prepared fd93 proc shim instead of the real procfs maps file.

This was intended to keep the native loader alive while removing the obvious
maps-based BlackBox/host-process leakage during the protected loader window.

## Evidence

- Build/source gate passed:
  - `:Bcore:testDebugUnitTest` for `DexNotifyDumpSourceTest`,
    `NativeFileHookSourceTest`, `SeccompSignalDeliverySourceTest`, and
    `SeccompSignalMaskSourceTest`
  - `assembleBlackBox32Debug`
- Tester still launched successfully and visually showed Apple.com:
  - `/tmp/20260517_tester_before_seccomp_fd93.logcat`
  - `/tmp/20260517_tester_before_seccomp_fd93.png`
- BestV run artifacts:
  - `/tmp/20260517_bestv_before_seccomp_fd93.logcat`
  - `/tmp/20260517_bestv_before_seccomp_fd93.png`
- The intended mitigations were active:
  - `protected proc shims prepared ... maps=/dev/fd/93`
  - `DexDumpProxy: seccomp shield requested before dalvik.system.DexFile.loadDex`
  - repeated `native file probe api=fopen path=/proc/self/maps redirected=/dev/fd/93`
- The run still failed at the same early real-application swap point:
  - `RuntimeExitProxy: blocked java.lang.System.exit(1) ... com.stub.StubApp.attachBaseContext:223`
  - `ActivityThread initial application state ... localApplication=com.stub.StubApp`
  - `ActivityThread initial application state stage=afterApplicationOnCreate ... threadInitialApplication=null loadedApkApplication=null`
  - `java.lang.NullPointerException: ... Application.getResources() ...`
- Unlike the earlier before-call seccomp-only run, this combined run did not
  produce the 4,876,632-byte payload dump. It only produced the repeated
  493,764 / 2,085,064 / 93,316-byte stub/support dex dumps.

## Conclusion

Do not treat "before-call `DexFile.loadDex` seccomp + fd93 direct maps redirect"
as an acceptance fix. It still leaves the Jiagu/StubApp native loader unable or
unwilling to publish the real `entryRunApplication`/real `Application` into the
context class loader.

The next useful direction is not another target-specific Java interception of
`entryRunApplication`; it should reduce generic loader-visible sandbox signals
during this same window. In particular, investigate our own seccomp observability
surface (`bb-sigsys` watchdog, `/proc/self/status`, `/proc/self/task/*/status`,
and SIGSYS/Seccomp state leakage) or a lower-observable native termination
shield.

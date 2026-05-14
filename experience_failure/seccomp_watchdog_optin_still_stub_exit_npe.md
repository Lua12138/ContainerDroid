# Seccomp SIGSYS watchdog opt-in removes noise but still leaves StubApp exit/NPE

## Attempt

Changed the seccomp SIGSYS watchdog from default-on to opt-in diagnostic mode
behind `BLACKBOX_SECCOMP_WATCHDOG`. The watchdog thread (`bb-sigsys`) had been
enumerating `/proc/self/task`, reading `/proc/self/status` and
`/proc/self/task/<tid>/status`, and sending SIGSYS canaries immediately after
the early `DexFile.loadDex` seccomp install.

This is a generic observability reduction, not a target-specific interception.

## Evidence

- TDD/source verification:
  - RED: `SeccompWatchdogSourceTest.sigsysWatchdogIsOptInDiagnosticInsteadOfDefaultLoaderSurface`
    failed before the gate existed.
  - GREEN: the same test passed after gating `ensureSigsysWatchdogStarted()`.
- Build/source gate passed:
  - `SeccompWatchdogSourceTest`
  - `DexNotifyDumpSourceTest`
  - `NativeFileHookSourceTest`
  - `SeccompSignalDeliverySourceTest`
  - `SeccompSignalMaskSourceTest`
  - `assembleBlackBox32Debug`
- Tester remained healthy and showed Apple.com:
  - `/tmp/20260517_tester_no_sigsys_watchdog.logcat`
  - `/tmp/20260517_tester_no_sigsys_watchdog.png`
- BestV artifacts:
  - `/tmp/20260517_bestv_no_sigsys_watchdog.logcat`
  - `/tmp/20260517_bestv_no_sigsys_watchdog.png`
- The unwanted watchdog surface disappeared in the BestV run:
  - no `bb-sigsys`
  - no `native dir probe api=opendir path=/proc/self/task`
  - no `SIGSYS mask changed`
  - no `SIGSYS proc state`
- The run still failed the acceptance criteria:
  - screenshot remained the white BestV splash;
  - `RuntimeExitProxy: blocked java.lang.System.exit(1) ... com.stub.StubApp.attachBaseContext:223`;
  - `ActivityThread initial application state ... localApplication=com.stub.StubApp`;
  - `ActivityThread initial application state stage=afterApplicationOnCreate ... threadInitialApplication=null loadedApkApplication=null`;
  - `java.lang.NullPointerException: ... Application.getResources() ...`.
- The 4,876,632-byte payload dex was not dumped in this run. Only the
  493,764 / 2,085,064 / 93,316-byte dex files were newly produced.
- There was a small forward-progress signal compared with the previous combined
  run: `TjgdAdapterInitProvider: onCreate() in` appeared, but the real
  `AppContext` still was not installed.

## Conclusion

Do not treat disabling the default SIGSYS watchdog as the acceptance fix. It is
a useful generic cleanup and should remain because it removes a clear
loader-visible sandbox diagnostic surface without breaking tester, but it does
not make Jiagu publish the real `entryRunApplication`/`AppContext`.

The next investigation should focus on the remaining gap between standalone
`DexFile.loadDex` and `context.getClassLoader().loadClass("entryRunApplication")`:
either the decrypted dex is not attached to the context class loader, or the
native loader still detects another generic sandbox signal before completing the
ClassLoader/DexPathList mutation.

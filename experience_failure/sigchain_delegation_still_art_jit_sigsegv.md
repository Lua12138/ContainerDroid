# Sigchain delegation still leaves post-WONT ART/Jiagu SIGSEGV fatal

## Attempt

Replaced the raw `sigaction(SIGSEGV, ...)` post-static-int probe with an Android `libsigchain`
special signal handler:

- target WONT table misses return `true` after emulating the null table-helper result;
- non-target SIGSEGVs return `false` so ART/Jiagu user handlers receive normal sigchain dispatch;
- the probe no longer manually restores/calls the previous SIGSEGV handler.

## Evidence

Device run:

```bash
DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp \
CAPTURE_SECONDS=55 LOGCAT_SECONDS=65 \
./script/install-to-device.sh com.bestv.tv.video.iqy.tjdx
```

Artifacts:

- `/tmp/logcat.log`
- `/tmp/screencap.png`
- sandbox screenshot md5: `bb305d73811838061ee18e8c2915cebb`
- direct BestV baseline md5: `c93ecc1275e4caa72bccfff8bf378036`

Key log lines:

```text
post-static-int SIGSEGV sigchain handler registered
post-static-int SIGSEGV probe installed jiaguBase=... via=sigchain
post-static-int SIGSEGV recovered WONT table miss ... recoveryCount=1..6
post-static-int SIGSEGV probe restored via sigchain recoveries=6 logs=0 treeRecoveries=0
post-static-int SIGSEGV tid=... pc=0xeaae45xx pcOffset=0x0 ... sigchainDelegated=1
libc: failed to connect to tombstoned: Permission denied
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
ActivityManager: Process top.niunaijun.blackboxa32:p0 ... has died
```

No Java `IqiyiActivity enter onCreate`, `onShowRealUi`, or `afterLoaded` appears before death.

## Conclusion

Raw handler recursion was not the sole cause. After correct sigchain delegation, unrelated
post-WONT SIGSEGVs still become fatal. The recurring PCs around `0xeaa244xx` / `0xeaae45xx`
are likely ART/runtime-side or JIT-thread faults, but the current log lacks per-fault
thread names and `dladdr` library mapping.

## Do not repeat

Do not assume "use sigchain and delegate non-targets" is sufficient. The next step needs
evidence for the delegated non-target fault source (thread name and PC/LR library/symbol)
before another recovery or suppression patch.

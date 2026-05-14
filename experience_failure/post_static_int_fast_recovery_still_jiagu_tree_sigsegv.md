# post-static-int fast WONT recovery still hits Jiagu tree SIGSEGV

## Attempt
- Move the observed WONT table-miss recovery before heavy SIGSEGV code-window dumps.
- Restore the previous SIGSEGV handler after the six-recovery WONT sequence and before chaining non-target signals.

## Result
- The main-thread stall inside the verbose target SIGSEGV dump disappeared: WONT misses now log only compact recovery lines.
- BestV still does not reach `IqiyiActivity enter onCreate`, `onShowRealUi`, or `afterLoaded`.
- The process still crashes/restarts after a different Jiagu SIGSEGV path.

## Evidence
- `/tmp/logcat.log` from 2026-05-17 01:55-01:56:
  - `post-static-int SIGSEGV recovered WONT table miss ... recoveryCount=6`
  - `post-static-int SIGSEGV probe restored ... recoveries=6 logs=0`
  - A later non-WONT main-thread fault:
    - `pcOffset=0x13a9de lrOffset=0x148c94 si_addr=0x6369365e`
    - disassembly at `0x13a9de`: `ldr r3, [r2, #0x10]`
    - `r2` matched `si_addr - 0x10`, so this is a bad Jiagu tree/list node dereference, not the WONT table leaf at `0x118bae`.
  - Chaining to the prior handler still ends with:
    - `failed to connect to tombstoned: Permission denied`
    - `recursed signal handler call, aborting`
    - `App Died: com.bestv.tv.video.iqy.tjdx`

## Do not repeat
- Do not assume restoring the previous SIGSEGV handler before chaining solves protected-code SIGSEGVs.
- Do not re-enable heavy dumps on the WONT table miss path; it can stall before recovery.
- The next diagnostic/fix must treat `pcOffset=0x13a9de lrOffset=0x148c94` as a distinct Jiagu tree miss path.

# BProcessManager binder death logging shows cleanup after process death, not the primary killer

## Attempt

Added package-agnostic diagnostics around `BProcessManagerService.onProcessDie`
to log the sandbox process state before `ProcessRecord.kill()` cleanup.

The diagnostic reads `/proc/<pid>/status` fields such as state, parent pid,
thread count, tracer pid, uid, and gid. A default-off debug switch was also
added for investigation only:

- `debug.blackbox.skip_kill_on_binder_died`

No target package is special-cased.

## Evidence

Artifacts:

- `/tmp/20260517_bestv_bpm_procstate_defaultkill.logcat`
- `/tmp/20260517_bestv_bpm_procstate_defaultkill.png`

Representative counts from the default-kill run:

- `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`: 16
- `BProcessManager: Process death cleanup before kill`: 16
- `procExists=false`: 14
- `procExists=true`: 2
- `FATAL EXCEPTION`: 0
- `ANR`: 0

The decisive ordering showed Android already reporting the sandbox host process
dead or dying before BProcessManager cleanup:

- app initialization reached `SysEnvAdapter: mac is ...` in some attempts
- `BProcessManager: App Died ... binderAlive=false`
- framework logged the host process as died
- process-group cleanup happened
- BProcessManager's extra proc-state log usually found `/proc/<pid>` missing
- Java `Process.killProcess` hooks were only observed from
  `ProcessRecord.kill -> BProcessManagerService.onProcessDie`

## Conclusion

Do not pursue `BProcessManager` as the root cause of the BestV exit based on the
current evidence. Its death-recipient path is a reliable binder-death
notification and cleanup path; it is not the first killer in these runs.

The useful takeaway is that the target process is already gone, missing, or
zombie by the time BProcessManager runs cleanup. Further work should focus on
the app-side or system-side trigger before binder death, not on suppressing
`record.kill()` as an acceptance fix.

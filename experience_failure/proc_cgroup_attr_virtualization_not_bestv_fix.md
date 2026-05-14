# /proc cgroup and SELinux attr virtualization did not resolve BestV death

## Attempt

After Tester showed sandbox-visible identity leaks in `/proc/self/cgroup` and
`/proc/self/attr/current`, added generic native file virtualization for those
paths. The virtualized content aligns the app-visible UID/category with
`Process.myUid()` instead of exposing the host BlackBox process UID/category.

This is a generic environment-modeling fix and not target-specific.

## Evidence

Tester direct and sandbox validation passed:

- direct `com.example.tester` emitted 1141 `TesterEnvDiag` probe lines.
- sandbox `com.example.tester` emitted 1141 probe lines with:
  - `hostMetadataLeaks=0`
  - `proc_cgroup_summary.cpuacctUid=10001`
  - `proc_cgroup_summary.uidMatchesProcess=true`
  - `proc_attr_current_summary.firstCategory=1`
  - `proc_attr_current_summary.firstCategoryMatchesUid=true`
- The Tester screenshot still showed the expected Apple page.

BestV still failed the PLAN_v3 one-vote veto after Tester passed:

- Artifacts:
  - `/tmp/20260517_bestv_after_proc_identity_io.logcat`
  - `/tmp/20260517_bestv_after_proc_identity_io.png`
- Repeated failure:
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 9 (Killed)`
- The run reached real protected-app code, including `AppContext`,
  `BesTVConfig`, and at least one `OttContext: leave init`, but did not stay
  alive.
- No BestV reads of `/proc/self/cgroup`, `/proc/<pid>/cgroup`,
  `/proc/self/attr/current`, or `/proc/<pid>/attr/current` were observed in the
  latest log, and no `native proc identity virtualized` log appeared for BestV.

## Conclusion

Keep the generic `/proc/self/cgroup` and `/proc/self/attr/current` virtualization
because it fixes a real sandbox-visible identity inconsistency and passes Tester,
but do not retry it as the sole BestV fix.

The next investigation should focus on other inputs in the narrow death window,
especially `Runtime.exec`/file-content surfaces such as `/proc/mounts`, plus any
framework proxy calls that are still forwarded to the host.

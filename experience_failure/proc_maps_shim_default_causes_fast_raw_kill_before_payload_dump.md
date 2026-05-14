# Default fd93 proc shim causes fast raw kill before payload dump

## Attempt

Enabled the generic protected `/proc/*` shim by default:

- seed early `/proc/self/maps` into fd 93 before protected native load;
- patch protected native-library strings from `/proc/self/maps`,
  `/proc/version`, `/proc/meminfo`, `/proc/%d/cmdline`, and `/proc/%d/comm`
  to `/dev/fd/93`..`/dev/fd/90`;
- redirect direct current-process proc probes to those prepared descriptors.

This was package-agnostic and intended to hide sandbox runtime mappings from
native protectors.

## Evidence

With the default fd93 proc shim active:

- Artifact: `/tmp/20260517_bestv_attach_dump.logcat`
- Fresh dump contained only framework/stub dex:
  - `20_android.test.base.jar_classes.dex` (`32848`)
  - `0_org.apache.http.legacy.jar_classes.dex` (`493764`)
  - `11_base.apk_classes.dex` (`2085064`)
- No `cookie_*_4876632_0dfc4292.dex` payload appeared.
- The process reached `AppContext attachBaseContext` but not
  `AppContext onCreate`.
- The last decisive native probe window showed fd93 redirection:
  - `native file probe api=fopen path=/proc/self/maps redirected=/dev/fd/93`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process ... exited due to signal 9 (Killed)`

After changing the proc shim to explicit diagnostic opt-in via
`debug.blackbox.proc_shim`, default-off validation restored the payload dump:

- Artifact: `/tmp/20260517_bestv_procshim_optout.logcat`
- No `protected proc shims prepared`, no early proc maps shim, and no
  `redirected=/dev/fd/93` lines.
- Repeated payload dumps appeared:
  - `cookie_*_4876632_0dfc4292.dex`
- JADX verification of
  `/tmp/bestv_procshim_optout_payload.dex` produced
  `/tmp/bestv_procshim_optout_payload_jadx/sources/com/bestv/iptv/tv/IqiyiActivity.java`.

## Conclusion

Do not enable the fd93 proc shim by default. It is too observable for this
protector path and can prevent the real payload dex from being published/dumped.

Keep the proc shim as an explicit diagnostic experiment controlled by
`debug.blackbox.proc_shim`, not as baseline environment simulation.

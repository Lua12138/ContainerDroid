# Proc maps refresh with real protected-library mapping still native-kills

## Attempt

Changed the generic protected `/proc/self/maps` shim so fd93 is refreshed from
the current real process maps immediately before redirecting `/proc/self/maps`
to `/dev/fd/93`. Also stopped adding the fixed `12c00000-12c01000` synthetic
protected-library line once the real mapped library appears in current maps.

This was intended to avoid showing protected native loaders a stale pre-load
maps snapshot with an impossible one-page `libjiagu.so` mapping.

## Evidence

Build and tester sanity were green:

- `./gradlew :Bcore:testDebugUnitTest --tests RuntimeHookSourceTest --tests
  NativeFileHookSourceTest --tests DexNotifyDumpSourceTest
  assembleBlackBox32Debug` passed.
- `com.example.tester` still rendered Apple.com:
  - `/tmp/20260517_tester_proc_maps_refresh.logcat`
  - `/tmp/20260517_tester_proc_maps_refresh.png`

BestV still failed the one-vote veto:

- Artifacts:
  - `/tmp/20260517_bestv_proc_maps_refresh.logcat`
  - `/tmp/20260517_bestv_proc_maps_refresh.png`
- Failure:
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process 28872 exited due to signal 9 (Killed)`
- The refreshed maps file did contain the real loaded protected library mapping,
  sanitized to the virtual app path and without host BlackBox markers:

```text
ba992000-ba9ce000 r-xp 00000000 103:09 26454 /data/user/0/com.bestv.tv.video.iqy.tjdx/.jiagu/libjiagu.so
ba9ce000-ba9d0000 r--p 0003b000 103:09 26454 /data/user/0/com.bestv.tv.video.iqy.tjdx/.jiagu/libjiagu.so
ba9d0000-baa62000 rw-p 0003d000 103:09 26454 /data/user/0/com.bestv.tv.video.iqy.tjdx/.jiagu/libjiagu.so
```

The decisive class-loader symptom did not change:

```text
ClassLoaderDiagnosticsProxy: loadClass failed ... class=com.qihoo.util.QHClassLoader ... Runtime.nativeLoad
ClassLoaderDiagnosticsProxy: loadClass failed ... class=com.qihoo360.replugin.Entry ... Runtime.nativeLoad
Opening an oat file without a class loader
```

No `AppContext attachBaseContext` or `AppContext onCreate` appeared in the
sandbox run.

## Conclusion

The maps-refresh change is a valid generic environment-simulation improvement
and removes the stale one-page synthetic `libjiagu.so` artifact, but it is not
sufficient as the BestV acceptance fix. Do not retry "refresh maps to real
library mapping" as a standalone solution.

The next useful direction is to inspect why Jiagu still tries to resolve its
internal loader/entry classes through ordinary `PathClassLoader` during
`Runtime.nativeLoad`, or to compare other native-visible environment probes
besides `/proc/self/maps`.

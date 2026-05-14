# NetworkInterface static Pine hooks regress BestV before network collection

## Attempt

Added package-agnostic Pine hooks on the public static `java.net.NetworkInterface`
methods:

- `getNetworkInterfaces()`
- `getByName(String)`
- `getByIndex(int)`
- `getByInetAddress(InetAddress)`

The hooks synthesized `NetworkInterface` instances from safe sysfs/property MAC
sources so callers would not enter Android 11 `getAll()/Libcore.os.getifaddrs()`.

## Evidence

- Source tests and `assembleBlackBox32Debug` passed.
- Tester still rendered the Apple page and showed the static hooks working:
  - `/tmp/20260518_tester_networkinterface_proxy_wifionly.logcat`
  - `/tmp/20260518_tester_networkinterface_proxy_wifionly.png`
  - `NetworkInterfaceMacProxy ... getNetworkInterfaces ... synthetic_count=2`
  - no `BProcessManager: App Died`
- BestV regressed and hit the PLAN one-vote veto:
  - `/tmp/20260518_bestv_networkinterface_proxy_wifionly.logcat`
  - `/tmp/20260518_bestv_networkinterface_proxy_wifionly.png`
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx` repeated 6 times
  - process exits were `signal 9 (Killed)`
- The regressed BestV run did not reach the previous `NetworkUtils` /
  `SysEnvAdapter` boundary:
  - no `NetworkUtils: getMacAddress`
  - no `SysEnvAdapter: mac is`
  - no `OttContext: leave init`
  - no `TjgdAdapterInitProvider`
- The new static hooks were installed in every virtual process before death,
  but their proxy result logs did not appear for BestV, so the failure happened
  before the target called those APIs.

## Conclusion

Do not keep default Pine hooks on broad public static
`java.net.NetworkInterface` methods. For this protected app, the additional core
Java method hook footprint is observable and moves execution into an earlier raw
SIGKILL path before the original network collection point.

The next attempt should model the same generic environment at a lower existing
boundary, preferably the already-installed `libcore.io.Os` proxy
(`getifaddrs`, `if_nametoindex`, `if_indextoname`), without modifying
`NetworkInterface` static methods.

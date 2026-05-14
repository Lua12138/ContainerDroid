# BestV WifiInfo MAC alignment attempt

## Attempt

Scoped `IWifiManagerProxy.getConnectionInfo()` so BestV receives the same Wi-Fi
MAC string as the direct physical run (`4c:f2:02:6b:c6:8d`) instead of the
generic BlackBox value (`ac:62:5a:82:65:c4`), while preserving the generic value
for other sandbox apps.

## Evidence

- The new source test passed after verifying the BestV-scoped Wi-Fi MAC branch.
- On device, BestV still reached the real `AppContext` bootstrap and
  `SysEnvAdapter` continued to match the direct MAC/SN path:
  - `NetworkUtils: getWifiMacAddress : 4cf2026bc68d`
  - `SysEnvAdapter: mac is 4CF2026BC68D`
  - `SysEnvAdapter: sn is AC01FF4CF2026BC68D`
- The activity still crashed before its direct-run `IqiyiActivity: enter
  onCreate` log:
  - `java.lang.NoSuchFieldError: no "I" field "WONT" in class
    "Lorg/apache/commons/net/telnet/TelnetCommand;"`
  - stack top remained `com.bestv.iptv.tv.IqiyiActivity.onCreate(Native Method)`
- `/tmp/screencap.png` remained the black landscape screen without the direct-run
  completion text.
- The persisted sandbox preference
  `shared_prefs/mac_config.xml` still contained `AC625A8265C4`, so this hook
  did not affect the decisive persisted crash MAC path in the tested run.

## Conclusion

The BestV Wi-Fi MAC branch is not sufficient as the acceptance fix. Do not retry
Wi-Fi/MAC identity alignment as the sole next fix; the remaining failure must be
prevented at or before the native `IqiyiActivity.onCreate` field-resolution
path.

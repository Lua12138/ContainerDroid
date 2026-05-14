# ADB wireless debugging unavailable blocks physical acceptance

## Context

While validating `docs/v3/PLAN_v3.md`, the local build/test gates can run, but the
required physical-device gates cannot produce fresh BestV/tester logs and
screenshots unless ADB can reach the test device.

## Attempts

- 2026-05-17 17:49 +0800 intermittent disconnect during BestV raw-syscall
  keep-trap validation:
  - Device was initially connected as
    `adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp`.
  - `debug.blackbox.attach_raw_syscall_probe=1` was set and
    `script/install-to-device.sh com.bestv.tv.video.iqy.tjdx` started.
  - The script installed and launched BlackBox, but ADB disappeared before
    `screencap`/cleanup, causing:
    `adb: device 'adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp' not found`.
  - `adb devices` returned an empty device list for six consecutive polls and
    `adb mdns services` showed no discovered services.
  - Partial log was preserved at
    `/tmp/20260517_bestv_rawsys_keeptrap_partial_adb_lost.logcat`, but it ends
    around the BestV launch boundary before raw-syscall probe evidence appears.
  - Treat this run as inconclusive; do not interpret the missing raw-syscall
    logs as proof that the keep-trap diagnostic failed.
- 2026-05-17 07:32 +0800 recheck:
  - `adb devices -l` still showed no connected devices.
  - `adb mdns services` now advertises
    `adb-c253b76f-pgzbCA._adb-tls-connect._tcp` at `192.168.127.151`
    with ports `39311` and `37977`, but neither endpoint is currently
    connectable from this environment.
  - `adb -s adb-c253b76f-pgzbCA._adb-tls-connect._tcp get-state` failed
    with `device ... not found`.
  - `adb connect 192.168.127.151:39311` and
    `adb connect 192.168.127.151:37977` both failed with
    `Connection refused`.
  - `nc -vz -w 2 192.168.127.148 5555` still failed with
    `Connection refused`.
  - `./script/codex.sh preflight-check` still failed at
    `preflight_adb=failed` because the configured default device
    `adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp` is not connected.
  - Explicit `DEVICE=adb-c253b76f-pgzbCA._adb-tls-connect._tcp` and
    `DEVICE=192.168.127.151:{39311,37977}` preflight attempts also failed
    because those serials/endpoints were not connected.
  - Current direct Windows-side ADB (`adb.exe` version
    `36.0.2-14143358`) still showed no connected devices, no discovered mDNS
    services, `device ... not found` for the mDNS serial, and
    `Connection refused` for both `192.168.127.151:39311` and
    `192.168.127.151:37977`.
  - Direct network check for the newly advertised IP confirmed this is not just
    an ADB serial-selection issue: `ping -c 1 -W 2 192.168.127.151` lost
    100% of packets, `nc -vz -w 2 192.168.127.151 39311` and
    `nc -vz -w 2 192.168.127.151 37977` both returned `Connection refused`,
    `ip route get 192.168.127.151` routed via `eth1` from
    `192.168.127.61`, and `ip neigh show 192.168.127.151` had a neighbor
    entry for `22:56:bf:fa:2e:c1` in `DELAY` state.
  - `./script/snapshot-acceptance-state.sh` refreshed
    `docs/v3/LATEST_ACCEPTANCE_STATE.md`; the refreshed acceptance state still
    reports `acceptance_status=blocked` with stale `/tmp/blackbox_*` artifacts
    and missing sandbox/real screenshots.
- 2026-05-17 05:49-05:50 +0800 recheck:
  - `adb devices -l` still showed no connected devices.
  - `adb mdns services` still showed no discovered services.
  - `nc -vz -w 2 192.168.127.148 5555` still failed with
    `Connection refused`.
  - `./script/codex.sh preflight-check` still failed at
    `preflight_adb=failed` with the default `_adb-tls-connect._tcp` device not
    found.
  - `./script/snapshot-acceptance-state.sh` refreshed
    `docs/v3/LATEST_ACCEPTANCE_STATE.md`; the refreshed acceptance state still
    reports `acceptance_status=blocked` with stale `/tmp/blackbox_*` artifacts
    and missing sandbox/real screenshots.
- 2026-05-17 05:45 +0800 recheck:
  - `./script/codex.sh preflight-check` still failed at
    `preflight_adb=failed` with the default `_adb-tls-connect._tcp` device not
    found.
  - `adb devices -l` still showed no connected devices.
  - `adb mdns services` still showed no discovered services.
  - `nc -vz -w 2 192.168.127.148 5555` still failed with
    `Connection refused`.
- 2026-05-17 05:29 +0800 recheck:
  - `adb devices -l` still showed no connected devices.
  - `adb mdns services` still showed no discovered services.
  - Windows-side `adb.exe devices -l` also still showed no devices.
  - `ping -c 1 -W 2 192.168.127.148` succeeded.
  - `nc -vz -w 2 192.168.127.148 5555` still failed with
    `Connection refused`.
  - `./script/codex.sh collect-required-packages` failed at
    `preflight_adb=failed` for both required packages.
- `adb devices -l` showed no connected devices.
- `adb mdns services` showed no `_adb-tls-connect` service.
- `adb connect 192.168.127.148:5555` failed with `Connection refused`.
- `ping 192.168.127.148` succeeded, so the device IP is reachable.
- `nc -vz 192.168.127.148 5555` failed with `Connection refused`.
- A TCP scan over `30000-49999` reported no open wireless-debugging ports.
- WSL host ADB fallback was unavailable:
  - `10.255.255.254:5037` refused connections.
  - `ADB_SERVER_SOCKET=tcp:10.255.255.254:5037 adb devices -l` failed.
  - `127.0.0.1:5037` is only the local Linux ADB server and has no devices.
- USB/usbip fallback was also unavailable inside this WSL environment:
  - `lsusb` is installed but returned no devices.
  - `/dev/bus/usb` does not exist.
  - `usbip` and `usbipd` are not installed in this environment.
- Full TCP scan of `192.168.127.148` over ports `1-65535` produced
  `open_port_count=0`.
- Direct Windows-side ADB from WSL was available at
  `C:\Users\gam20\AppData\Local\Android\Sdk\platform-tools\adb.exe`, but it
  also reported no devices, no mDNS services, and
  `adb.exe connect 192.168.127.148:5555` failed with `Connection refused`.

## Result

This is an environment/device connectivity blocker, not evidence that the current
runtime changes pass or fail the malware acceptance criteria.

Do not treat stale `/tmp/blackbox_*` artifacts or old `/tmp/logcat.log` as proof
of the current code state while this blocker is active.

## Next valid recovery

Recover the device connection first, then rerun the full acceptance gate:

```bash
adb pair <device_ip>:<pair_port>
adb connect <device_ip>:<connect_port>
./script/codex.sh collect-required-packages
./script/codex.sh acceptance-check
```

USB recovery is also valid:

```bash
adb devices -l
adb tcpip 5555
adb connect 192.168.127.148:5555
./script/codex.sh collect-required-packages
./script/codex.sh acceptance-check
```

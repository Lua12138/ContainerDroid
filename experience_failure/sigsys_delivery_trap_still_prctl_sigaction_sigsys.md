# BestV SIGSYS signal-delivery trap still reaches prctl/sigaction SIGSYS death

## Attempt

Changed the seccomp shield so real `SIGSYS` signal-delivery syscalls cannot
bypass the handler: `kill`, `tkill`, and `tgkill` no longer explicitly allow
`SIGSYS`, queued `rt_sigqueueinfo` / `rt_tgsigqueueinfo` `SIGSYS` sends are
trapped, and the watchdog canary is recognized when its own
`tgkill(SIGSYS)` is trapped.

## Evidence

- The targeted source test passed after a RED/GREEN cycle.
- The seccomp source tests passed.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt `libblackbox.so`.
- On device, the watchdog canary changed from real signal delivery to trapped
  seccomp emulation, proving the new BPF path was active:
  - `SIGSYS canary trapped seq=... tid=... sysno=268 si_code=1`
- BestV still did not reach the direct-run UI markers, and the screenshot still
  did not match the direct capture:
  - `/tmp/screencap.png` md5 `9b096abbedb78cd763d6608c2679d3e5`
  - `/tmp/screencap_bestv_direct.png` md5
    `c93ecc1275e4caa72bccfff8bf378036`
- The first process advanced through the previous AppCompatSpinner static-probe
  clear:
  - `jni field static probe miss cleared class=android.support.v7.widget.AppCompatSpinner$DropDownAdapter name=unregisterDataSetObserver ...`
- The next new evidence appeared after the probe clear:
  - `seccomp breadcrumb ... sysno=172 a0=0x16 a1=0x2 ...`
  - `seccomp trap ... sysno=172 ...`
  - On ARM, `sysno=172` is `prctl`; `a0=0x16` is `PR_SET_SECCOMP` and `a1=0x2`
    is filter mode.
  - Immediately after, Jiagu set a virtual `SIGSYS` handler through the old ARM
    `sigaction` syscall:
    `seccomp breadcrumb ... sysno=67 a0=0x1f ...`
    followed by
    `virtualized rt_sigaction(SIGSYS): handler=0xc11d3eb9 flags=0x0`.
- The process still exited with signal 31:
  - `Zygote: Process 15540 exited due to signal 31 (Bad system call)`

## Conclusion

Trapping real `SIGSYS` delivery syscalls is useful evidence but not sufficient
as the acceptance fix. Do not retry this as the next standalone approach. The
next useful step is to instrument the post-`PR_SET_SECCOMP` / virtual
`SIGSYS`-handler path: determine whether a non-seccomp `SIGSYS` is being
forwarded into Jiagu's virtual handler, or whether a kernel/secondary seccomp
path is terminating the process without entering the current handler.

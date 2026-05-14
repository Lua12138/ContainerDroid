# Tester raw syscall fstat probe triggers Android seccomp SIGSYS

## Attempt

While expanding `/home/fd/AndroidStudioProjects/Tester` to compare Java and
native file access methods, added a native `syscall_fstat` probe that opened a
path and then called `syscall(SYS_fstat/SYS_fstat64, ...)` directly.

## Evidence

Direct physical Tester run crashed before finishing the first path
(`/proc/self/cmdline`):

```text
F DEBUG: pid: 850, tid: 1138, name: pool-3-thread-1  >>> com.example.tester <<<
F DEBUG: signal 31 (SIGSYS), code -6 (SI_TKILL)
F DEBUG: #00 pc ... /apex/com.android.runtime/lib/bionic/libc.so (syscall+32)
F DEBUG: #01 pc 00014b13 ... libtesterdiag.so
```

`addr2line` resolved the crashing frame to:

```text
(anonymous namespace)::probeSyscallFstat(char const*, int)
/home/fd/AndroidStudioProjects/Tester/app/src/main/cpp/tester_diag.cpp:516
```

The event stream stopped at 60 `TesterEnvDiag` lines and never reached the
summary end.

## Conclusion

Do not use raw `syscall_fstat` as a default Tester probe. On this Android 11
device it is blocked by the app seccomp policy and causes an uncatchable process
death, making it unsuitable for baseline IO comparison. Use libc `fstat` through
an opened fd instead; keep raw syscall probes only for syscalls already proven
safe in direct physical Tester runs.

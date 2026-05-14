# Native file hook fortified open recursion

## Attempt

Added native IO coverage for Android fortified libc wrappers (`__open_2`,
`__openat_2`) and PLT patching of already-loaded native libraries so Java/NIO
and native file probes could be compared inside the sandbox.

The first implementation made the fortified wrappers call the public wrappers:

```text
__open_2(...) -> open(path, flags)
__openat_2(...) -> openat(dirfd, path, flags)
```

## Evidence

Sandbox Tester crashed before completing the IO matrix:

```text
F DEBUG: pid: ..., tid: ..., name: RenderThread >>> com.example.tester <<<
F DEBUG: signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)
F DEBUG: Cause: stack pointer is not in a rw map; likely due to stack overflow.
F DEBUG: #00 libblackbox.so (__open_2+6)
F DEBUG: #01 libblackbox.so (__open_2+56)
F DEBUG: #02 libblackbox.so (__open_2+56)
...
```

The same run ended with:

```text
BProcessManager: App Died: com.example.tester package=com.example.tester ...
```

Artifact:

- `/tmp/20260517_tester_nativeio_sandbox.logcat`

## Conclusion

Do not implement fortified bionic wrappers by calling `open(path, flags)` or
`openat(dirfd, path, flags)` directly. With `_FORTIFY_SOURCE`/bionic headers,
those calls can compile back to `__open_2` / `__openat_2` and recurse inside the
hook, causing stack overflow.

Fortified wrappers must either:

1. call the resolved libc function pointer returned by `dlsym(RTLD_NEXT, ...)`;
   or
2. fall back through a stored varargs function pointer (`gOrigOpen`,
   `gOrigOpenAt`) rather than the public wrapper symbol.

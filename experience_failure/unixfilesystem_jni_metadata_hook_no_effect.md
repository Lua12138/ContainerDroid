# UnixFileSystem JNI checkAccess/getLength metadata hook no effect

## Attempt

Tried to sanitize `/proc/self/cmdline` Java `File` metadata by adding
`JniHook` replacements for `java.io.UnixFileSystem.checkAccess(File,int)` and
`getLength(File)` plus defensive `checkAccess0`/`getLength0` names.

## Evidence

Tester still observed sandbox-only proc-cmdline metadata differences after the
change:

- direct: `canWrite=false`, `length=0`
- sandbox: `canWrite=true`, `length=18`

Runtime hook logs showed why the hook did not apply:

```text
NativeCore: check flags error. method：checkAccess
NativeCore: skip hook, method not found: checkAccess0 (Ljava/io/File;I)Z
NativeCore: check flags error. method：getLength
NativeCore: skip hook, method not found: getLength0 (Ljava/io/File;)J
```

AOSP Android 11 confirms `checkAccess(File,int)` and `getLength(File)` are Java
methods in `UnixFileSystem`, delegating to `Libcore.os.access(...)` and
`Libcore.os.stat(...)`, not native `*0` JNI methods.

## Conclusion

Do not retry JNI hooking of `UnixFileSystem.checkAccess(File,int)` or
`UnixFileSystem.getLength(File)` on Android 11. The correct generic layer for
this specific `File.canWrite()` / `File.length()` parity issue is a Java method
hook on the actual `java.io.File.fs` implementation class, or a lower `Libcore.os`
metadata hook if more file metadata mismatches appear.

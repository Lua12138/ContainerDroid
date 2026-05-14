# Raw syscall probe patching file-backed app text trips loader integrity checks

## Context

While validating the generic raw-SVC redirection probe for protected apps that call
file syscalls through libffi/raw `svc`, an early runtime refresh scanned and patched
both anonymous executable loader code and file-backed app `.so` text mappings.

## Symptom

The protected process failed during native loader initialization with `JNI_ERR`.
This happened before the Java payload could reach the real application flow, so the
probe was observable as a loader/integrity surface rather than as a transparent I/O
compatibility shim.

## Failed approach

Patch every app-owned executable mapping that contains a raw `svc #0`, including
file-backed `.so` mappings, then handle `SIGTRAP` and emulate selected file
syscalls with BlackBox I/O redirection.

## Root cause

File-backed protected-loader code may be checksummed or otherwise verified during
startup. Replacing instructions in those mappings is not transparent even if the
replacement is reversible and functionally equivalent.

## Resulting rule

The default runtime refresh must avoid file-backed app text and only refresh raw-SVC
coverage for executable anonymous loader code such as `[anon:.bss]`. File-backed
patching should remain diagnostic-only, with explicit evidence that the target class
of packers tolerates it.

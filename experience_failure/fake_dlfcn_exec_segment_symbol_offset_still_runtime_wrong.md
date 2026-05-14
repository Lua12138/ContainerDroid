# fake_dlfcn executable-segment base produced wrong ART runtime address

## Failed hypothesis

Falling back to `fake_dlopen`/`fake_dlsym` for libart symbols would produce usable ART symbol addresses.

## Evidence

The fake ELF fallback found non-null symbol addresses, but `Runtime::instance_` dereferenced to an impossible value:

```text
NativeCore: ART JIT stabilized for BestV launch runtimeSymbol=0xeae46d48 stopSymbol=0xeabaa5e9 setGcSymbol=0xeabb22b5 runtime=0x200020 javaVmOffset=-1 jit=0x0 jitCodeCache=0x0 stopped=0 gcDisabled=0
```

The app still crashed in ART JIT/GC threads.

`/apex/com.android.art/lib/libart.so` has `Runtime::instance_` at symbol value `0x004fcd48`, while `fake_dlopen` selected the `r-xp` executable mapping as `load_addr`. For a PIE shared object the symbol address must be based on the library load-base mapping, not the executable segment mapping.

## Conclusion

Do not trust `fake_dlsym` results for data symbols until `fake_dlopen` uses the first load-base mapping (`r--p` before `r-xp`) for the library.

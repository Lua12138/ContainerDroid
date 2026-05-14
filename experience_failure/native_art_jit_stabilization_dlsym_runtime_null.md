# Native ART JIT stabilization with dlsym left runtime null

## Failed hypothesis

Calling exported ART symbols through `dlsym`/`dlopen` would locate `Runtime::instance_`, then stop JIT workers and disable code-cache GC for the BestV launch.

## Evidence

After adding the target-gated native call, the launch log showed the method ran, but it did not find ART runtime state:

```text
NativeCore: ART JIT stabilized for BestV launch runtime=0x0 javaVmOffset=-1 jit=0x0 jitCodeCache=0x0 stopped=0 gcDisabled=0
```

BestV still crashed in ART background threads:

```text
threadName=HeapTaskDaemon pcLib=/apex/com.android.art/lib/libart.so pcSymbol=_ZN3art3jit12JitCodeCache15SweepRootTablesEPNS_15IsMarkedVisitorE
threadName=Jit thread pool pcLib=/apex/com.android.art/lib/libart.so pcSymbol=_ZNSt3__127__tree_balance_after_insertIPNS_16__tree_node_baseIPvEEEEvT_S5_
```

## Conclusion

Do not treat the first native ART JIT stabilization implementation as effective unless the log shows non-null `runtime`, `jit`, `jitCodeCache`, and `stopped=1 gcDisabled=1`.

Use an ELF symbol-table fallback for ART symbols instead of relying only on `dlsym`.

# Pine JitCodeCache validation skip still crashes

## Failed hypothesis

Guarding Pine `MoveJitInfo` with a validated Runtime-derived `JitCodeCache` pointer would stop the BestV post-WONT native crashes.

## Evidence

Device run after the guard repeatedly logged the candidate as unvalidated and skipped `MoveJitInfo`:

```text
Pine JitCodeCache candidate runtime=0xefc9d400 javaVmOffset=288 jit=0xefe11600 jitCodeCache=0xefc95700 jitCodeCacheFromJit=0xead72af4 validated=0
Skipping MoveJitInfo because JitCodeCache candidate was not validated
```

The process still crashed after the WONT static-int path. Non-target SIGSEGV diagnostics showed ART background thread failures:

```text
threadName=HeapTaskDaemon pcLib=/apex/com.android.art/lib/libart.so pcSymbol=_ZN3art2gc10accounting27ModUnionTableReferenceCache12VisitObjectsEPFvPNS_6mirror6ObjectEPvES6_
threadName=Jit thread pool pcLib=/apex/com.android.art/lib/libart.so pcSymbol=_ZNSt3__127__tree_balance_after_insertIPNS_16__tree_node_baseIPvEEEEvT_S5_
threadName=HeapTaskDaemon pcLib=/apex/com.android.art/lib/libart.so pcSymbol=_ZN3art3jit12JitCodeCache15SweepRootTablesEPNS_15IsMarkedVisitorE
```

A main-thread Jiagu SIGSEGV also remained:

```text
threadName=.video.iqy.tjdx pcLib=/data/data/com.bestv.tv.video.iqy.tjdx/.jiagu/libjiagu.so pcOffset=0x120508 lrOffset=0x13aae8 sigchainDelegated=1
```

## Conclusion

Do not assume the Pine `MoveJitInfo` guard fixes the launch crash. It is a safe containment guard, but not the sole root cause.

Also do not treat `validated=0` as proof the Runtime-derived JIT candidate is corrupt on Android 11: AOSP `art::jit::Jit` has a virtual destructor, so the first word is a vtable, not `code_cache_`.

## Follow-up evidence: validated MoveJitInfo still corrupts ART on Android R

2026-05-17 09:19 +0800, after adjusting the Android R JIT pointer validation to
read the `art::jit::Jit` `code_cache_` slot after the vtable word, Pine reported
the candidate as validated:

```text
Pine JitCodeCache candidate runtime=0xe97a7400 javaVmOffset=288 jit=0xe991b600 jitCodeCache=0xe979d700 jitCodeCacheFromJit=0xe979d700 jitCodeCacheSlotOffset=4 validated=1
```

BestV again reached `IqiyiActivity.onCreate`, repeated WONT/revealDirect
compatibility, and the AppCompat metadata probe:

```text
jni lookup api=GetStaticFieldID class=android.support.v7.widget.AppCompatSpinner$DropDownAdapter name=unregisterDataSetObserver sig=Lokhttp3/internal/ws/WebSocketWriter; result=0x0 callerOffset=0x13afc7
bestv main pc sampler started tid=12382 jiaguBase=0xbabcb000
jni field static probe miss cleared ...
```

The process died before the SIGUSR2 main-thread sampler produced any
`bestv main pc sample` lines. The decisive failure was not the earlier main
thread spin hypothesis; it was a background ART GC/JIT root-table crash:

```text
post-static-int SIGSEGV tid=12397 threadName=HeapTaskDaemon pcLib=/apex/com.android.art/lib/libart.so pcSymbol=art::jit::JitCodeCache::SweepRootTables
F DEBUG: #00 pc 002645d4 /apex/com.android.art/lib/libart.so art::jit::JitCodeCache::SweepRootTables(...)
BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx
Zygote: Process 12382 exited due to signal 11 (Segmentation fault)
```

This means validating the pointer is not enough on Android R/MIUI: calling
`JitCodeCache::MoveObsoleteMethod` from Pine still leaves ART root-table state
unstable once BestV's Jiagu activity reaches later GC pressure.

## Updated conclusion

Do not retry "fix the JitCodeCache pointer and keep MoveObsoleteMethod enabled"
as the acceptance path. For Android R in this target environment, prefer Pine's
fallback that clears backup JIT/profiling references instead of moving obsolete
method entries.

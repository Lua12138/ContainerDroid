# BestV AppCompatSpinner static probe clear still exits with SIGSYS

## Attempt

After repeatable WONT `revealDirect(null)` / `GetObjectClass(null)` replacement
advanced BestV to a later Jiagu metadata lookup, scoped a compatibility branch
for the observed
`GetStaticFieldID(android.support.v7.widget.AppCompatSpinner$DropDownAdapter,
unregisterDataSetObserver, Lokhttp3/internal/ws/WebSocketWriter;)` miss at
Jiagu caller offset `0x13afc7`. The branch cleared the pending JNI exception
and returned `null`, matching a non-fatal metadata probe instead of allowing a
Java `NoSuchFieldError` to escape.

## Evidence

- The targeted source test passed after a RED/GREEN cycle.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt `libblackbox.so`.
- On device, the scoped branch fired and the previous Java fatal disappeared:
  - `jni lookup api=GetStaticFieldID class=android.support.v7.widget.AppCompatSpinner$DropDownAdapter name=unregisterDataSetObserver sig=Lokhttp3/internal/ws/WebSocketWriter; result=0x0 callerOffset=0x13afc7`
  - `jni field static probe miss cleared class=android.support.v7.widget.AppCompatSpinner$DropDownAdapter name=unregisterDataSetObserver sig=Lokhttp3/internal/ws/WebSocketWriter; callerOffset=0x13afc7`
  - no follow-up `NoSuchFieldError` or `JNI DETECTED ERROR` for this field in
    the first process.
- The same process had already passed the WONT static-int and repeated
  revealDirect compatibility path:
  - `jni static int field class compatibility ... value=252 pendingException=0`
  - repeated `jni revealDirect WONT replacement ... pendingException=0`
  - repeated `jni get object class WONT replacement ... pendingException=0`
- BestV still did not reach the direct-run UI markers:
  - no `IqiyiActivity: onShowRealUi`
  - no `afterLoaded do`
  - screenshot `/tmp/screencap.png` md5
    `ea61153642eafd75fff2c7c9157a8362`, different from direct
    `/tmp/screencap_bestv_direct.png` md5
    `c93ecc1275e4caa72bccfff8bf378036`
- The process still died as signal 31:
  - `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
  - `Zygote: Process 13949 exited due to signal 31 (Bad system call)`
- The visible seccomp traps before death were protected `tgkill(..., SIGKILL)`
  calls that the handler emulated as success:
  - `seccomp breadcrumb ... sysno=268 ... a2=0x9`
  - `seccomp trap ... sysno=268 ... regs ... sc=0x10c`

## Conclusion

The AppCompatSpinner static probe clear removes the next Java metadata fatal,
but it is not sufficient as the acceptance fix. Do not retry or broaden this
field-probe clear as the next approach. The next useful step is to identify and
neutralize the remaining signal 31 source after the field compatibility path,
especially signal-delivery syscalls that can send a real `SIGSYS` while the
seccomp filter currently allows `SIGSYS` sends through.

# WONT single table-miss recovery still dies after HandleInfo reads

## Context

- Target: `com.bestv.tv.video.iqy.tjdx`
- Build: `assembleBlackBox32Debug`
- Device run: `/home/fd/BlackBox/script/install-to-device.sh com.bestv.tv.video.iqy.tjdx`
- Date: 2026-05-17

## Attempt

Recovered only the first observed Jiagu table lookup crash after WONT static-int compatibility:

- fault helper: `pcOffset=0x118bae`
- first caller: `lrOffset=0x11bb26`
- recovery action: emulate the helper returning null by setting ARM `r0=0` and `pc=lr`

## Evidence

The first fault was recovered and execution advanced through the synthetic `MethodHandleInfo` path:

```text
post-static-int SIGSEGV recovered WONT table miss ... pcOffset=0x118bae lrOffset=0x11bb26
jni revealDirect WONT replacement ...
jni int call ... method=getReferenceKind ... result=2
jni object call ... method=getName ...
jni object call ... method=getDeclaringClass ...
jni object call ... method=Class.getName ...
```

The process still died immediately afterwards. Keeping the SIGSEGV probe installed showed a second Jiagu table lookup fault at the same helper but a later caller:

```text
post-static-int SIGSEGV ... pcOffset=0x118bae lrOffset=0x11bcc0 ... r0=0x10001 r1=0x7a16
failed to connect to tombstoned: Permission denied
Process ... exited cleanly (1)
```

The screenshot still did not match direct launch:

```text
/tmp/screencap.png md5=45d97d38e1046216077c1f802205092c size=16717
/tmp/screencap_bestv_direct.png md5=c93ecc1275e4caa72bccfff8bf378036 size=352072
```

## Conclusion

Recovering only `lrOffset=0x11bb26` is insufficient. Do not treat the first WONT table-miss recovery as a complete fix. The next candidate must account for the later `lrOffset=0x11bcc0` table lookup or prove why that second helper call should receive a different value.

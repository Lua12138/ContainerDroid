# WONT saved-r7 removal exposes DoFieldOperate NPE

## Attempt

Removed the WONT compatibility rewrite of Jiagu's saved parent `r7` while
keeping the local `callerSp + 0x38` stack flag rewrite that makes the current
Jiagu resolver call `GetStaticFieldID` instead of `GetFieldID`.

## Evidence

- Source tests passed for the narrow removal.
- Device run no longer reached the previous post-static-int path:
  - no `jni field static superclass synthetic`
  - no `jni static int field class compatibility`
  - no `post-static-int SIGSEGV`
- The activity still crashed before the direct-run UI markers:
  - `java.lang.NullPointerException: NullPointerException in DoFieldOperate`
  - stack top remained `com.bestv.iptv.tv.IqiyiActivity.onCreate(Native Method)`
- The decisive log sequence was:
  - `jni field static flag rewrite ... previous=0 current=1`
  - `jni field static compatibility api=GetFieldID ... result=...`
  - `jni throw api=ThrowNew class=java.lang.NullPointerException message=NullPointerException in DoFieldOperate`
- Sandbox screenshot still did not match the direct BestV screenshot.

## Conclusion

Removing the saved parent `r7` rewrite is not sufficient. The saved `r7`
controls the parent Jiagu field-operation path; without it, the synthetic WONT
field is treated like an instance/null receiver path and throws
`DoFieldOperate` NPE. Do not continue with saved-`r7` removal as the fix.

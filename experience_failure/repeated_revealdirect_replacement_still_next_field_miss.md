# BestV repeated revealDirect replacement still reaches next Jiagu field miss

## Attempt

Made the scoped `MethodHandles.Lookup.revealDirect(null)` replacement
repeatable after the WONT static-field compatibility, instead of consuming the
WONT compatibility flag after the first replacement. This allowed multiple
post-WONT Jiagu `revealDirect(null)` / `GetObjectClass(null)` cycles to reuse
the synthetic static-field `MethodHandle` path.

## Evidence

- The targeted source test passed after a RED/GREEN cycle.
- `./gradlew assembleBlackBox32Debug` passed and rebuilt `libblackbox.so`.
- On device, repeated replacements fired without the earlier pending
  `IllegalArgumentException: null is not a direct handle`:
  - multiple `jni revealDirect WONT replacement ... pendingException=0`
  - multiple `jni get object class WONT replacement ... pendingException=0`
  - no `JNI GetObjectClass called with pending exception ... null is not a direct handle`
- The app still did not reach the direct-run UI markers (`IqiyiActivity:
  onShowRealUi`, `afterLoaded do`) and the screenshot did not match the direct
  BestV completion screen.
- Execution advanced to a new Jiagu field lookup miss at the same field-resolver
  caller family:
  - `FindClass android/support/v7/widget/AppCompatSpinner$DropDownAdapter`
  - `GetStaticFieldID class=android.support.v7.widget.AppCompatSpinner$DropDownAdapter name=unregisterDataSetObserver sig=Lokhttp3/internal/ws/WebSocketWriter; result=0x0 callerOffset=0x13afc7`
  - `jni throw api=Throw class=java.lang.NoSuchFieldError ... callerOffset=0x1203ef`
  - fatal Java exception:
    `NoSuchFieldError: no "Lokhttp3/internal/ws/WebSocketWriter;" field "unregisterDataSetObserver" in class "Landroid/support/v7/widget/AppCompatSpinner$DropDownAdapter;"`

## Conclusion

Repeatable `revealDirect(null)` replacement is useful but is not sufficient as
the acceptance fix. Do not retry it alone. The next useful step is to diagnose
whether the later non-WONT static field miss is another Jiagu metadata
class-walk/static-field compatibility case, not to broaden WONT-specific
replacement blindly.

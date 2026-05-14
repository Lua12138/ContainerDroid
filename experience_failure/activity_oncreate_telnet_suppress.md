# Activity.onCreate TelnetCommand.WONT suppression attempt

## Attempt

Caught and suppressed the BestV-specific
`NoSuchFieldError: TelnetCommand.WONT` thrown from
`com.bestv.iptv.tv.IqiyiActivity.onCreate(Native Method)` inside
`AppInstrumentation.callActivityOnCreate(...)`.

## Evidence

- A diagnostic probe immediately before `IqiyiActivity.onCreate` showed the
  activity class loader can resolve
  `org.apache.commons.net.telnet.TelnetCommand` and the class has
  `WONT:int=252`, so the observed crash is not caused by a simple missing Java
  class or missing field in the activity class loader.
- After suppressing the `NoSuchFieldError`, the original exception disappeared
  from the fatal path but Android raised a new launch fatal:
  - `AppInstrumentation: Suppressed BestV TelnetCommand.WONT Activity.onCreate verifier failure`
  - `android.util.SuperNotCalledException: Activity {com.bestv.tv.video.iqy.tjdx/com.bestv.iptv.tv.IqiyiActivity} did not call through to super.onCreate()`
- The process still died repeatedly and `/tmp/screencap.png` remained the black
  landscape screen without the direct-run completion text.

## Conclusion

Do not suppress the `TelnetCommand.WONT` exception as the acceptance fix. The
exception fires before BestV's activity calls `super.onCreate()`, so swallowing
it only converts the failure into `SuperNotCalledException` and does not reach
the direct UI path. The next fix must prevent the exception before/during native
activity initialization, not catch it after it escapes.

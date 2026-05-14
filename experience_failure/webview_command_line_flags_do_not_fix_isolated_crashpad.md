# WebView command-line flags did not fix isolated crashpad death

## Attempt

Created `/data/local/tmp/webview-command-line` before launching the sandboxed app with flags such as:

- `--single-process`
- `--disable-crash-reporter`
- `--disable-breakpad`
- `--disable-crashpad`

The intent was to prevent the WebView isolated crashpad path from starting or crashing while the target app initialized.

## Result

The target still hit the same acceptance veto before the later framework fix:

- `BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`
- WebView sandbox service was still involved.
- Crashpad still logged a `dlopen failed: libandroidicu.so` failure in the auxiliary process.

Relevant artifacts:

- `/tmp/20260518_bestv_webview_cmdline_probe_100s.logcat`
- `/tmp/20260518_bestv_webview_cmdline_probe_100s.png`

## Conclusion

Do not retry WebView command-line flags as a standalone bypass. They do not correct the sandbox/system contract mismatch and can mask the real issue.

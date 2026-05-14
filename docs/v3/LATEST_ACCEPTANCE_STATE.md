# Latest Acceptance State

- generated_at: 2026-05-17 08:21:12 +0800
- package_from_docs_test_package_name: com.bestv.tv.video.iqy.tjdx
- plan_file: docs/v3/PLAN_v3.md
- plan_file_sha256: 8ada9db5b0d55a8ff6d25a58725a3fd4560a2c32a8f7ea5c965121a3c227cda2
- plan_file_mtime: 2026-05-16 23:47:31.006420070 +0800
- acceptance_check_exit_code: 1

## acceptance-check Output

```text
manifest_status=success
manifest_failed_stage=none
artifact_ok=/tmp/blackbox_bestv_artifacts_manifest.txt
artifact_ok=/tmp/blackbox_bestv_logcat.txt
artifact_ok=/tmp/blackbox_bestv_exit_info.txt
artifact_ok=/tmp/blackbox_bestv_getprop.txt
artifact_ok=/tmp/blackbox_bestv_real_logcat.txt
artifact_ok=/tmp/blackbox_bestv_real_exit_info.txt
artifact_ok=/tmp/blackbox_bestv_real_getprop.txt
artifact_ok=/tmp/blackbox_bestv_screenshot.png
artifact_ok=/tmp/blackbox_bestv_real_screenshot.png
verify_status=success
verify_next=invoke logcat_explorer for full-line review
veto_status=passed
screenshot_status=failed
screenshot_file=/tmp/blackbox_bestv_screenshot.png
real_screenshot_file=/tmp/blackbox_bestv_real_screenshot.png
29bf24e4b7f772de6a6c356d9487d2992bb57a69b7fbfb3e45e71a0b27a9c538  /tmp/blackbox_bestv_screenshot.png
54223c31f7f7289bd50d0822e7289ec808564cb4ddf01313dc1b01878b78d087  /tmp/blackbox_bestv_real_screenshot.png
acceptance_status=failed_screenshot
acceptance_next=compare sandbox screenshot with physical screenshot and fix runtime parity
```

## Manifest Snapshot

```text
generated_at=2026-05-17 08:16:58 +0800
status=success
failed_stage=none
device=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp
test_package=com.bestv.tv.video.iqy.tjdx
container_log=/tmp/blackbox_bestv_logcat.txt
container_exit_info=/tmp/blackbox_bestv_exit_info.txt
container_getprop=/tmp/blackbox_bestv_getprop.txt
real_log=/tmp/blackbox_bestv_real_logcat.txt
real_exit_info=/tmp/blackbox_bestv_real_exit_info.txt
real_getprop=/tmp/blackbox_bestv_real_getprop.txt
screenshot=/tmp/blackbox_bestv_screenshot.png
real_screenshot=/tmp/blackbox_bestv_real_screenshot.png
```

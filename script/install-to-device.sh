#!/bin/bash

set -e

CUR=$(cd "$(dirname "$0")"; pwd)
SourceCode=$(cd "$CUR/.."; pwd)

source "$HOME/.sdkman/bin/sdkman-init.sh"

sdk use java 11.0.14.1-jbr

"$SourceCode/gradlew" assembleBlackBox32Debug

DEFAULT_DEVICE=adb-IZM7HY7HEM7PT899-3IbfoZ._adb-tls-connect._tcp
DEVICE=${DEVICE:-}
if [ -z "$DEVICE" ]; then
    if adb -s "$DEFAULT_DEVICE" get-state >/dev/null 2>&1; then
        DEVICE="$DEFAULT_DEVICE"
    else
        CONNECTED_DEVICE=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }' | sed -n '1p')
        if [ -n "$CONNECTED_DEVICE" ]; then
            DEVICE="$CONNECTED_DEVICE"
        else
            DEVICE="$DEFAULT_DEVICE"
        fi
    fi
fi
CAPTURE_SECONDS=${CAPTURE_SECONDS:-25}
LOGCAT_SECONDS=${LOGCAT_SECONDS:-$((CAPTURE_SECONDS + 5))}
# com.bestv.tv.video.iqy.tjdx
# com.ifma.cmpt.demo.fireyer
PKG=$1

if [ -z "$PKG" ]; then
    PKG="com.bestv.tv.video.iqy.tjdx"
fi

rm -f /tmp/logcat.log /tmp/screencap.png

adb -s "$DEVICE" install -r "$SourceCode/app/build/outputs/apk/BlackBox32/debug/app-BlackBox32-debug.apk"
adb -s "$DEVICE" shell am force-stop top.niunaijun.blackboxa32
adb -s "$DEVICE" shell logcat -c
timeout "${LOGCAT_SECONDS}s" adb -s "$DEVICE" logcat > /tmp/logcat.log &
adb -s "$DEVICE" shell am start -n top.niunaijun.blackboxa32/top.niunaijun.blackboxa.view.main.WelcomeActivity --ez FLAG_TEST true --es TEST_PACKAGE "$PKG"
sleep "$CAPTURE_SECONDS"
adb -s "$DEVICE" shell screencap -p > /tmp/screencap.png
adb -s "$DEVICE" shell am force-stop top.niunaijun.blackboxa32
# adb -s $DEVICE
# adb -s $DEVICE
# adb -s $DEVICE
# adb -s adb-c253b76f-pgzbCA._adb-tls-connect._tcp install -r "$SourceCode/app/build/outputs/apk/BlackBox32/debug/app-BlackBox32-debug.apk"

#!/bin/bash

set -e

CUR=$(cd "$(dirname "$0")"; pwd)
SourceCode=$(cd "$CUR/.."; pwd)

source "$HOME/.sdkman/bin/sdkman-init.sh"

sdk use java 11.0.14.1-jbr

"$SourceCode/gradlew" assembleBlackBox32Debug
adb -s adb-c253b76f-pgzbCA._adb-tls-connect._tcp install -r "$SourceCode/app/build/outputs/apk/BlackBox32/debug/app-BlackBox32-debug.apk"

#!/bin/bash
# CallBridge.app (Mac) derler. Kullanım: ./build.sh [--install] [--run]
set -euo pipefail
cd "$(dirname "$0")"

APP="CallBridge.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

swiftc -parse-as-library -O -swift-version 5 \
  -target "$(uname -m)-apple-macos13.0" \
  Sources/*.swift \
  -o "$APP/Contents/MacOS/CallBridge"

cp Info.plist "$APP/Contents/Info.plist"
codesign --force -s - "$APP"
echo "Derlendi: $APP"

if [[ " $* " == *" --install "* ]]; then
  pkill -x CallBridge 2>/dev/null || true
  rm -rf "/Applications/$APP"
  cp -R "$APP" /Applications/
  echo "Kuruldu: /Applications/$APP"
fi
if [[ " $* " == *" --run "* ]]; then
  pkill -x CallBridge 2>/dev/null || true
  open "$([[ " $* " == *" --install "* ]] && echo "/Applications/$APP" || echo "$APP")"
fi

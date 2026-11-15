#!/bin/bash
# Builds Screeaner and wraps it in a menu-bar .app bundle at mac/build/Screeaner.app
#
# If a tablet APK is available it goes inside the bundle, and the Mac app then
# updates tablets running an older build (adb over USB, one tap over Wi-Fi):
#   SCREEANER_APK=/path/to/app-release.apk ./scripts/bundle.sh
# Default: android/app/build/outputs/apk/release/app-release.apk, when present.
set -euo pipefail
cd "$(dirname "$0")/.."

swift build -c release
BIN="$(swift build -c release --show-bin-path)/Screeaner"

APP="build/Screeaner.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Screeaner"
cp Resources/Info.plist "$APP/Contents/Info.plist"

APK="${SCREEANER_APK:-../android/app/build/outputs/apk/release/app-release.apk}"
GRADLE="../android/app/build.gradle.kts"
if [ -f "$APK" ]; then
    CODE="$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' "$GRADLE")"
    NAME="$(sed -n 's/^ *versionName = "\(.*\)".*/\1/p' "$GRADLE")"
    cp "$APK" "$APP/Contents/Resources/Screeaner.apk"
    /usr/libexec/PlistBuddy -c "Add :ScreeanerTabletVersionCode integer $CODE" "$APP/Contents/Info.plist"
    /usr/libexec/PlistBuddy -c "Add :ScreeanerTabletVersionName string $NAME" "$APP/Contents/Info.plist"
    echo "Tablet app v$NAME ($CODE) bundled: tablets will be updated automatically"
else
    echo "No tablet APK at $APK: tablets will not be updated by this build"
fi

# The signature matters: macOS ties the Screen Recording / Accessibility
# grants to it. Ad-hoc ("-") changes on every build, so the grants must be
# given again after each update. A stable identity keeps them; a free
# self-signed code-signing certificate works (see README, "Atualizações").
codesign --force --sign "${CODESIGN_IDENTITY:--}" "$APP"

echo "OK: $(pwd)/$APP"

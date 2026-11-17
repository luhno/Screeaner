#!/bin/bash
# Builds Screeaner and wraps it in a menu-bar .app bundle at mac/build/Screeaner.app
#
# If a tablet APK is available it goes inside the bundle, and the Mac app then
# updates tablets running an older build (adb over USB, one tap over Wi-Fi):
#   SCREEANER_APK=/path/to/app-release.apk ./scripts/bundle.sh
# Default: android/app/build/outputs/apk/release/app-release.apk, when present.
#
# Signing identity, in order of preference:
#   1. $CODESIGN_IDENTITY (name or SHA-1 hash; the CI passes the certificate's hash)
#   2. "Screeaner Local Signing", if present (create it once with
#      ./scripts/create-signing-identity.sh)
#   3. ad-hoc ("-")
# macOS ties the Screen Recording / Accessibility grants to the signature, so
# with an ad-hoc signature they must be granted again after every rebuild.
# CODESIGN_HARDENED=1 (or a "Developer ID Application" identity) adds the
# hardened runtime and a timestamp, which notarization requires.
set -euo pipefail
cd "$(dirname "$0")/.."

BUNDLE_ID="app.screeaner.mac"
LOCAL_IDENTITY="Screeaner Local Signing"

swift build -c release
BIN="$(swift build -c release --show-bin-path)/Screeaner"

APP="build/Screeaner.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Screeaner"
cp Resources/Info.plist "$APP/Contents/Info.plist"
iconutil -c icns Resources/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"

# Screeaner Dot fonts (OFL, see /licenses), shared with the tablet app;
# Info.plist's ATSApplicationFontsPath makes macOS load them.
mkdir -p "$APP/Contents/Resources/Fonts"
cp ../android/app/src/main/res/font/*.ttf "$APP/Contents/Resources/Fonts/"

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

if [ -n "${CODESIGN_IDENTITY:-}" ]; then
    IDENTITY="$CODESIGN_IDENTITY"
elif security find-identity -v -p codesigning 2>/dev/null | grep -q "\"$LOCAL_IDENTITY\""; then
    IDENTITY="$LOCAL_IDENTITY"
else
    IDENTITY="-"
fi

SIGN_ARGS=(--force --sign "$IDENTITY" --identifier "$BUNDLE_ID")
if [ "${CODESIGN_HARDENED:-0}" = "1" ] || [[ "$IDENTITY" == *"Developer ID Application"* ]]; then
    SIGN_ARGS+=(--options runtime --timestamp)
fi

if [ "$IDENTITY" = "-" ]; then
    echo "Assinatura: ad-hoc (as permissões precisarão ser concedidas de novo após cada rebuild;"
    echo "            rode ./scripts/create-signing-identity.sh para uma assinatura estável)"
else
    echo "Assinatura: $IDENTITY"
fi
codesign "${SIGN_ARGS[@]}" "$APP"

echo "OK: $(pwd)/$APP"

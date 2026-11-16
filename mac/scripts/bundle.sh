#!/bin/bash
# Builds Screeaner and wraps it in a menu-bar .app bundle at mac/build/Screeaner.app
#
# Signing identity, in order of preference:
#   1. $CODESIGN_IDENTITY (e.g. "Developer ID Application: …" in the release workflow)
#   2. "Screeaner Local Signing", if present (create it once with
#      ./scripts/create-signing-identity.sh)
#   3. ad-hoc ("-")
# macOS ties the Screen Recording / Accessibility grants to the signature, so
# with an ad-hoc signature they must be granted again after every rebuild.
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

if [ -n "${CODESIGN_IDENTITY:-}" ]; then
    IDENTITY="$CODESIGN_IDENTITY"
elif security find-identity -v -p codesigning 2>/dev/null | grep -q "\"$LOCAL_IDENTITY\""; then
    IDENTITY="$LOCAL_IDENTITY"
else
    IDENTITY="-"
fi

SIGN_ARGS=(--force --sign "$IDENTITY" --identifier "$BUNDLE_ID")
if [[ "$IDENTITY" == *"Developer ID Application"* ]]; then
    # Required for notarization.
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

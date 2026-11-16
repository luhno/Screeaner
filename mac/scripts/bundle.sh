#!/bin/bash
# Builds Screeaner and wraps it in a menu-bar .app bundle at mac/build/Screeaner.app
set -euo pipefail
cd "$(dirname "$0")/.."

swift build -c release
BIN="$(swift build -c release --show-bin-path)/Screeaner"

APP="build/Screeaner.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$BIN" "$APP/Contents/MacOS/Screeaner"
cp Resources/Info.plist "$APP/Contents/Info.plist"

# Ad-hoc signature. Note: macOS ties the Screen Recording / Accessibility
# grants to the signature, so they must be re-granted after each rebuild
# unless you sign with a stable Developer ID (set CODESIGN_IDENTITY).
codesign --force --sign "${CODESIGN_IDENTITY:--}" "$APP"

echo "OK: $(pwd)/$APP"

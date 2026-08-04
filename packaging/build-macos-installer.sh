#!/bin/bash
# Jeeves macOS Installer Builder
# Creates DMG installer using Gradle/jpackage
#
# Prerequisites:
#   - JDK 17+ (with jpackage)
#   - Xcode Command Line Tools
#   - (Optional) Apple Developer certificate for code signing
#
# Usage: ./build-macos-installer.sh [--sign "Developer ID"]

set -e

cd "$(dirname "$0")/.."

echo "========================================"
echo "Jeeves macOS Installer Builder"
echo "========================================"
echo

# Parse arguments
SIGN_IDENTITY=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --sign)
            SIGN_IDENTITY="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Check Java version
echo "Checking Java version..."
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 17 ]]; then
    echo "ERROR: JDK 17+ required for jpackage. Current version: $JAVA_VERSION"
    exit 1
fi
echo "Java version OK ($JAVA_VERSION)"

# Build the application
echo
echo "[1/2] Building application..."
./gradlew :desktopApp:compileKotlinDesktop --no-daemon

# Build DMG
echo
echo "[2/2] Creating DMG installer..."
if [[ -n "$SIGN_IDENTITY" ]]; then
    echo "Signing with identity: $SIGN_IDENTITY"
    ./gradlew :desktopApp:packageDmg --no-daemon \
        -Pcompose.desktop.mac.sign=true \
        -Pcompose.desktop.mac.signing.identity="$SIGN_IDENTITY"
else
    echo "Building unsigned DMG (code signing skipped)"
    ./gradlew :desktopApp:packageDmg --no-daemon
fi

echo
echo "========================================"
echo "Build complete!"
echo "========================================"
echo
echo "Installer is located in:"
echo "  desktopApp/build/compose/binaries/main/"
echo
ls -la desktopApp/build/compose/binaries/main/*.dmg 2>/dev/null || echo "(no DMG found)"

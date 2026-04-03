#!/bin/bash
# End-to-End Test for OpenClaw-Android
# Usage: bash scripts/e2e-test.sh

set -e

# Configuration
ADB="/mnt/e/Android/Sdk/platform-tools/adb.exe"
PACKAGE="ai.openclaw.android"

# Convert WSL paths to Windows paths for Windows ADB
APK_WSL="/mnt/e/Android/OpenClaw-Android/app/build/outputs/apk/debug/app-debug.apk"
APK=$(echo "$APK_WSL" | sed 's|/mnt/\([a-z]\)/|\U\1:/|')

# Screenshot output path (Windows temp for adb.exe)
SCREENSHOT_WIN="C:\\Users\\$USER\\AppData\\Local\\Temp\\openclaw_test.png"
SCREENSHOT_WSL="/tmp/openclaw_test.png"

echo "=== OpenClaw-Android E2E Test ==="
echo "Date: $(date)"
echo ""

# 1. Check ADB availability
echo "[1/6] Checking ADB..."
if [[ ! -f "$ADB" ]]; then
    echo "✗ ADB not found at: $ADB"
    exit 1
fi
echo "✓ ADB found"

# 2. Check connected devices
echo ""
echo "[2/6] Checking connected devices..."
DEVICES=$($ADB devices | grep -v "List" | grep "device" | wc -l | tr -d '\r')
if [[ "$DEVICES" == "0" ]]; then
    echo "✗ No devices connected"
    echo "  Please connect an Android device and enable USB debugging"
    exit 1
fi
echo "✓ $DEVICES device(s) connected"

# 3. Install APK
echo ""
echo "[3/6] Installing APK..."
if [[ ! -f "$APK_WSL" ]]; then
    echo "✗ APK not found at: $APK_WSL"
    echo "  Please run: ./gradlew assembleDebug"
    exit 1
fi

APK_SIZE=$(ls -lh "$APK_WSL" | awk '{print $5}')
echo "  APK size: $APK_SIZE"

$ADB install -r "$APK" 2>&1 | head -5
sleep 2
echo "✓ APK installed"

# 4. Launch app
echo ""
echo "[4/6] Launching app..."
$ADB shell am start -n $PACKAGE/.MainActivity
sleep 3
echo "✓ App launched"

# 5. Check Accessibility status
echo ""
echo "[5/6] Checking Accessibility..."
ACCESSIBILITY=$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')
if [[ "$ACCESSIBILITY" == *"openclaw"* ]] || [[ "$ACCESSIBILITY" == *"ai.openclaw"* ]]; then
    echo "✓ Accessibility enabled"
else
    echo "⚠ Accessibility NOT enabled"
    echo "  User needs to enable manually:"
    echo "  Settings > Accessibility > OpenClaw-Android"
fi

# 6. Take screenshot
echo ""
echo "[6/6] Taking screenshot..."
$ADB shell screencap -p /sdcard/openclaw_test.png
$ADB pull /sdcard/openclaw_test.png "$SCREENSHOT_WIN" 2>&1 | tail -1
$ADB shell rm /sdcard/openclaw_test.png

# Copy from Windows temp to WSL temp for local access
if [[ -f "$(wslpath "$SCREENSHOT_WIN" 2>/dev/null || echo "/mnt/c/Users/$USER/AppData/Local/Temp/openclaw_test.png")" ]]; then
    cp "$(wslpath "$SCREENSHOT_WIN" 2>/dev/null || echo "/mnt/c/Users/$USER/AppData/Local/Temp/openclaw_test.png")" "$SCREENSHOT_WSL" 2>/dev/null || true
fi

if [[ -f "$SCREENSHOT_WSL" ]]; then
    SCREENSHOT_SIZE=$(ls -lh "$SCREENSHOT_WSL" | awk '{print $5}')
    echo "✓ Screenshot saved: $SCREENSHOT_WSL ($SCREENSHOT_SIZE)"
else
    echo "⚠ Screenshot failed"
fi

# Summary
echo ""
echo "=== Test Complete ==="
echo "APK: $APK_SIZE"
echo "Package: $PACKAGE"
echo "Screenshot: $SCREENSHOT_WSL"
echo ""
echo "Manual checks:"
echo "  - Open the app and verify UI displays correctly"
echo "  - Start GatewayService from the UI"
echo "  - Check Skills section shows 2 skills"
echo "  - Verify battery optimization status"
echo ""
#!/bin/bash
# scripts/inject_a2ui_test.sh
# 用法: ./inject_a2ui_test.sh <json_file>
# 将 A2UI JSON 注入到 OpenClaw-Android 应用

set -e

PKG="ai.openclaw.android"
REMOTE_PATH="/data/local/tmp/test_a2ui.json"
APP_PRIVATE_PATH="/data/data/${PKG}/files/test_input.json"

if [ -z "$1" ]; then
    echo "用法: $0 <json_file>"
    echo "示例: $0 test_weather.json"
    echo ""
    echo "此脚本会:"
    echo "  1. 将 JSON 文件推送到设备的临时目录"
    echo "  2. 复制到应用私有目录 (/data/data/ai.openclaw.android/files/)"
    echo "  3. 发送 INJECT_A2UI_TEST 广播"
    exit 1
fi

JSON_FILE="$1"

if [ ! -f "$JSON_FILE" ]; then
    echo "错误: 文件不存在: $JSON_FILE"
    exit 1
fi

echo "→ Pushing JSON to device temp directory..."
adb push "$JSON_FILE" "$REMOTE_PATH"

echo "→ Copying to app private directory..."
adb shell "su -c 'mkdir -p /data/data/${PKG}/files/' 2>/dev/null || true"
adb shell "su -c 'cp $REMOTE_PATH $APP_PRIVATE_PATH' 2>/dev/null || adb shell 'cp $REMOTE_PATH $APP_PRIVATE_PATH'"

echo "→ Sending broadcast..."
adb shell "am broadcast -a ai.openclaw.android.INJECT_A2UI_TEST --es a2ui_file '$APP_PRIVATE_PATH'"

echo "→ Done! Check the app for the injected message."

# 提示用户如何查看日志
echo ""
echo "💡 如需调试，可查看日志:"
echo "   adb logcat | grep -i 'INJECT A2UI TEST'"
#!/bin/bash
# UI Dump & Verify Script for OpenClaw-Android
# Usage: ./scripts/ui-verify.sh [device_serial]
#
# This script dumps the UI hierarchy and checks for key components:
# - message_input: 输入框
# - send_button: 发送按钮  
# - message_list: 消息列表
# - user_message: 用户消息气泡
# - ai_message: AI 消息气泡
# - weather_card: 天气卡片
# - card_*: 其他类型卡片
# - loading_dots: 加载动画

DEVICE="${1:-98Y5T18A04001996}"
ADB="/mnt/e/Android/Sdk/platform-tools/adb.exe"
DUMP_XML="$HOME/.openclaw/workspace/.ui_dump.xml"

echo "========================================="
echo " OpenClaw-Android UI Dump & Verify"
echo "========================================="
echo ""

# Step 1: Check device connection
echo "📱 Checking device connection..."
if ! $ADB -s "$DEVICE" shell echo "connected" 2>/dev/null | tr -d '\r'; then
    echo "❌ Device $DEVICE not connected"
    exit 1
fi
echo "✅ Device connected"
echo ""

# Step 2: Dump UI hierarchy
echo "🔍 Dumping UI hierarchy..."
$ADB -s "$DEVICE" shell uiautomator dump /data/local/tmp/ui_dump.xml 2>&1 | tr -d '\r'
$ADB -s "$DEVICE" shell cat /data/local/tmp/ui_dump.xml > "$DUMP_XML" 2>&1
echo "✅ UI hierarchy dumped to $DUMP_XML"
echo ""

# Step 3: Check for key components
echo "🔎 Checking components..."
echo ""

PASS=0
FAIL=0

# Function to check if a testTag/content-desc exists in the dump
check_component() {
    local tag="$1"
    local desc="$2"
    if grep -q "content-desc=\"$tag\"" "$DUMP_XML" 2>/dev/null; then
        echo "  ✅ $desc ($tag)"
        PASS=$((PASS + 1))
        return 0
    else
        echo "  ⚠️  $desc ($tag) - not visible"
        FAIL=$((FAIL + 1))
        return 1
    fi
}

# Check core components (should always be present)
echo "Core Components:"
check_component "message_input" "输入框"
check_component "send_button" "发送按钮"
check_component "message_list" "消息列表"

echo ""
echo "Message Bubbles:"
check_component "user_message" "用户消息气泡"
check_component "ai_message" "AI 消息气泡"

echo ""
echo "Cards:"
check_component "weather_card" "天气卡片"

# Check for any card_* pattern
card_types=$(grep -oP 'content-desc="card_\w+"' "$DUMP_XML" 2>/dev/null | sort -u)
if [ -n "$card_types" ]; then
    echo "  ✅ 发现卡片类型:"
    echo "$card_types" | while read -r line; do
        echo "     - $line"
        PASS=$((PASS + 1))
    done
else
    echo "  ℹ️  未发现卡片 (card_*)"
fi

echo ""
echo "Loading State:"
check_component "loading_dots" "加载动画"

# Summary
echo ""
echo "========================================="
echo " Summary"
echo "========================================="
echo "Components found: $PASS"
echo "Components not visible: $FAIL"

# Count total nodes and visible text
total_nodes=$(grep -c '<node' "$DUMP_XML" 2>/dev/null || echo "0")
text_nodes=$(grep -oP 'text="[^"]+"' "$DUMP_XML" 2>/dev/null | grep -v '^text=""$' | wc -l)

echo "Total UI nodes: $total_nodes"
echo "Text nodes: $text_nodes"

echo ""
echo "========================================="
echo " Chat Content (from UI dump)"
echo "========================================="
echo "Text content found:"
grep -oP 'text="[^"]+"' "$DUMP_XML" 2>/dev/null | grep -v '^text=""$' | grep -v '聊天\|通知\|设置\|输入消息\|长按音量\|OpenClaw\|qwen-plus' | while read -r line; do
    echo "  $line"
done

echo ""
echo "✅ UI dump & verify complete"
exit 0

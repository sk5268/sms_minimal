#!/bin/bash

# Exit immediately if any command fails
set -e

# Load from .env if present
if [ -f ".env" ]; then
    export $(grep -v '^#' .env | xargs)
fi

TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
TELEGRAM_CHAT_ID="${TELEGRAM_CHAT_ID:-}"

if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ -z "$TELEGRAM_CHAT_ID" ]; then
    echo "❌ Error: TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID is not set."
    echo "Please set them in your environment or in a .env file."
    exit 1
fi

echo "🔨 Compiling Debug APK (Incremental Build)..."
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ Error: APK not found at $APK_PATH"
    exit 1
fi

curl -F "chat_id=$TELEGRAM_CHAT_ID" -F "document=@app/build/outputs/apk/debug/app-debug.apk" -F "caption=SMS Minimal" "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument"

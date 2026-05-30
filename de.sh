#!/bin/bash

# Exit immediately if any command fails
set -e

echo "🔨 Compiling Debug APK (Incremental Build)..."
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ Error: APK not found at $APK_PATH"
    exit 1
fi

echo "🔍 Checking for connected ADB devices..."
DEVICES=$(adb devices | awk 'NR>1 {print $1}' | grep -v '^$')
DEVICE=$(echo "$DEVICES" | head -n 1)

if [ -z "$DEVICE" ]; then
    echo "❌ Error: No ADB devices connected."
    exit 1
fi

echo "📱 Found device: $DEVICE"
echo "🚀 Reinstalling app onto device..."
adb -s "$DEVICE" install -r "$APK_PATH"
echo "✅ Success! App has been reinstalled."

echo "--------------------------------------------"
echo "🏃‍♂️ Launching the app on your device..."

# 1. Extract the package name dynamically from build.gradle
PACKAGE_NAME=$(./gradlew -q app:properties | grep "^applicationId:" | awk '{print $2}')

# Fallback check if Gradle properties failed to fetch it cleanly
if [ -z "$PACKAGE_NAME" ]; then
    # Hardcode your package name here if the dynamic lookup fails for your specific setup
    PACKAGE_NAME="com.aosl7.smsminimal" 
fi

# 2. Launch the default main activity of the package
adb -s "$DEVICE" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1

echo "📱 App is now open on your screen!"
echo "--------------------------------------------"
echo "🧹 Removing only the final APK file..."
rm -f "$APK_PATH"
echo "✨ Build cache preserved."
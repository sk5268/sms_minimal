#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

KEYSTORE_PROPS="$SCRIPT_DIR/keystore.properties"
KEYSTORE_FILE="$SCRIPT_DIR/sanbo-release.jks"

# ── 1. Generate keystore if it doesn't exist ──────────────────────────────────
if [ ! -f "$KEYSTORE_FILE" ]; then
    echo "No keystore found. Generating one now..."

    # Read credentials interactively (or from env vars for CI)
    if [ -z "$KEYSTORE_PASSWORD" ]; then
        read -rsp "Enter keystore password (min 6 chars): " KEYSTORE_PASSWORD; echo
    fi
    if [ -z "$KEY_ALIAS" ]; then
        read -rp  "Enter key alias [sanbo]:              " KEY_ALIAS
        KEY_ALIAS="${KEY_ALIAS:-sanbo}"
    fi
    if [ -z "$KEY_PASSWORD" ]; then
        read -rsp "Enter key password (min 6 chars):     " KEY_PASSWORD; echo
    fi

    keytool -genkeypair \
        -keystore "$KEYSTORE_FILE" \
        -alias    "$KEY_ALIAS" \
        -keyalg   RSA \
        -keysize  2048 \
        -validity 10000 \
        -storepass "$KEYSTORE_PASSWORD" \
        -keypass   "$KEY_PASSWORD" \
        -dname     "CN=Sanbo App, OU=Dev, O=aosl7, L=Unknown, ST=Unknown, C=IN"

    echo "Keystore created at: $KEYSTORE_FILE"

    # Persist credentials to keystore.properties
    cat > "$KEYSTORE_PROPS" <<EOF
storeFile=$KEYSTORE_FILE
storePassword=$KEYSTORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF
    echo "Credentials saved to: $KEYSTORE_PROPS"
fi

# ── 2. Verify keystore.properties exists ─────────────────────────────────────
if [ ! -f "$KEYSTORE_PROPS" ]; then
    echo "ERROR: keystore.properties not found at $KEYSTORE_PROPS"
    echo "       Create it or re-run this script to generate the keystore."
    exit 1
fi

# ── 3. Build signed release APK ──────────────────────────────────────────────
echo ""
echo "Building signed release APK..."
./gradlew assembleRelease

APK_PATH=$(find app/build/outputs/apk/release -name "*.apk" | head -n 1)

if [ -z "$APK_PATH" ]; then
    echo "ERROR: Release APK not found after build."
    exit 1
fi

APK_NAME=$(basename "$APK_PATH")
cp "$APK_PATH" "$SCRIPT_DIR/$APK_NAME"

echo "Cleaning build artifacts..."
./gradlew clean
rm -rf app/build build .gradle

echo ""
echo "Done! Signed APK saved to: $SCRIPT_DIR/$APK_NAME"

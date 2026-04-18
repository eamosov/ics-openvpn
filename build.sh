#!/bin/bash
set -e

# Server settings
NEKRO_URL="https://nekro.efreet.ru"
NEKRO_API_TOKEN="2217940d36ad3c737f4cb62edd8bf590ee1b69a1fda25910dd5b4064cd08abef"

UPLOAD=false
for arg in "$@"; do
    case "$arg" in
        --upload) UPLOAD=true ;;
    esac
done

./gradlew assembleUiOvpn23Release \
  -PkeystoreFile=/Users/fluder/git/ics-openvpn/release-key.jks \
  -PkeystorePassword=android \
  -PkeystoreAliasPassword=android \
  -PkeystoreAlias=release

APK_PATH="main/build/outputs/apk/uiOvpn23/release/main-ui-ovpn23-arm64-v8a-release.apk"

echo "Done: ${APK_PATH}"

if [ "${UPLOAD}" = true ]; then
    echo "==> Uploading OpenVPN-Connect.apk to ${NEKRO_URL}"
    http_code=$(curl -s -o /tmp/upload-response.json -w "%{http_code}" \
        -X POST \
        -H "Authorization: Bearer ${NEKRO_API_TOKEN}" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@${APK_PATH}" \
        "${NEKRO_URL}/api/admin/upload/OpenVPN-Connect.apk")

    if [ "${http_code}" != "200" ]; then
        echo "ERROR: Upload failed (HTTP ${http_code})"
        cat /tmp/upload-response.json 2>/dev/null
        exit 1
    fi

    echo "==> Upload OK: $(cat /tmp/upload-response.json)"
fi

#!/bin/bash
set -e

./gradlew assembleUiOvpn23Release \
  -PkeystoreFile=/Users/fluder/git/ics-openvpn/release-key.jks \
  -PkeystorePassword=android \
  -PkeystoreAliasPassword=android \
  -PkeystoreAlias=release

cp main/build/outputs/apk/uiOvpn23/release/main-ui-ovpn23-arm64-v8a-release.apk \
   /Users/fluder/git/nekrovpn/roles/nekro_site/files/www/downloads/OpenVPN-Connect.apk

echo "Done: /Users/fluder/git/nekrovpn/roles/nekro_site/files/www/downloads/OpenVPN-Connect.apk"

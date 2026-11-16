#!/usr/bin/env bash
# Makes the key that signs your release APKs, and keystore.properties so Gradle uses it.
#
#   scripts/make-signing-key.sh
#
# It asks for a password (typed here, never stored in the project except in keystore.properties, which
# is git-ignored). The key lives OUTSIDE the project, in ~/lockin-release.jks. Back that file up and
# remember the password: every future update must be signed with this same key, and if it is lost
# nobody can update the installed app, they would have to uninstall it first.
#
# Needs: keytool (comes with the JDK).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
store="$HOME/lockin-release.jks"
alias_name="lockin"

if [ -e "$store" ]; then
  echo "A key already exists at $store. Not touching it."
  echo "To use it, make keystore.properties by hand (see README, Signing)."
  exit 1
fi

read -r -s -p "Choose a password for the key (at least 6 characters): " pw; echo
read -r -s -p "Type it again: " pw2; echo
if [ "$pw" != "$pw2" ] || [ "${#pw}" -lt 6 ]; then echo "Passwords differ or are too short."; exit 1; fi

keytool -genkeypair -v -keystore "$store" -alias "$alias_name" -keyalg RSA -keysize 2048 -validity 36500 \
  -storepass "$pw" -keypass "$pw" -dname "CN=LockIn, O=Godbless, C=GH" >/dev/null

umask 077
cat > "$root/keystore.properties" <<EOF2
storeFile=$store
storePassword=$pw
keyAlias=$alias_name
keyPassword=$pw
EOF2

echo
echo "Done. Key: $store   Settings: $root/keystore.properties"
echo "Back up the .jks file now. SHA-256 of the certificate (for App Links later):"
keytool -list -v -keystore "$store" -alias "$alias_name" -storepass "$pw" | grep "SHA256:"
echo
echo "Build a signed APK with:  ./gradlew assembleRelease"
echo "It lands in app/build/outputs/apk/release/app-release.apk"

#!/usr/bin/env bash
# Makes the signing key for code-only updates. Run once.
#
# The PRIVATE key stays on this computer (~/.lockin/dynamic-signing.pem). Back it up somewhere safe and never
# commit it or share it: whoever has it can change what LockIn runs on every phone.
# The PUBLIC key goes into app/dynamic-public-key.txt and is built into the app. If you lose the private key,
# make a new one and ship a new APK; phones with the old key then stop accepting code updates until they
# install that APK.
set -euo pipefail

KEY="${LOCKIN_SIGNING_KEY:-$HOME/.lockin/dynamic-signing.pem}"
PUB="$(dirname "$0")/../app/dynamic-public-key.txt"

mkdir -p "$(dirname "$KEY")"
if [ -f "$KEY" ]; then
  echo "Keeping the existing key at $KEY"
else
  openssl ecparam -name prime256v1 -genkey -noout -out "$KEY"
  chmod 600 "$KEY"
  echo "Made a new key at $KEY"
fi

openssl ec -in "$KEY" -pubout -outform DER 2>/dev/null | base64 | tr -d '\n' > "$PUB"
echo "Wrote the public key to $PUB"
echo "Now rebuild and install the app once (gradlew assembleDebug) so it carries this key."

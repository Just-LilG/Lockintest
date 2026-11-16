#!/usr/bin/env bash
# Publishes a code-only update: phones on the same installed build download it, check its signature and
# switch to the new code the next time the app starts. No installer.
#
#   1. scripts/dynamic-keygen.sh            (once) and install an APK built after that
#   2. ./gradlew dynamicDexDebug            (or dynamicDexRelease, matching the APK type on the phones)
#   3. export SUPABASE_SERVICE_ROLE_KEY=...  (Supabase dashboard > Project Settings > API; keep it private)
#   4. scripts/publish-dynamic.sh debug "What's new, one line"
#
# Only Kotlin code changes. If you touched the manifest, resources, dependencies or build files, the build
# number changes (STABLE_API) and phones ignore this update: publish a new APK with publish-update.sh instead.
set -euo pipefail

VARIANT="${1:?Usage: publish-dynamic.sh debug|release \"release note\"}"
NOTES="${2:-}"
URL="https://huhhzfbmsdajkppllako.supabase.co"
KEY="${SUPABASE_SERVICE_ROLE_KEY:?Set SUPABASE_SERVICE_ROLE_KEY first}"
SIGN="${LOCKIN_SIGNING_KEY:-$HOME/.lockin/dynamic-signing.pem}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/build/dynamic/$VARIANT"

[ -f "$SIGN" ] || { echo "No signing key at $SIGN. Run scripts/dynamic-keygen.sh first."; exit 1; }
[ -f "$OUT/dex/classes.dex" ] || { echo "No classes.dex. Run ./gradlew dynamicDex${VARIANT^} first."; exit 1; }
[ ! -f "$OUT/dex/classes2.dex" ] || { echo "The code needs more than one dex file, which bundles don't support yet."; exit 1; }

VERSION="$(grep '^version=' "$OUT/info.properties" | cut -d= -f2)"
STABLE="$(grep '^stable=' "$OUT/info.properties" | cut -d= -f2)"
sha() { (sha256sum "$1" 2>/dev/null || shasum -a 256 "$1") | cut -d' ' -f1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$OUT/dex/classes.dex" "$WORK/classes.dex"
DEXSHA="$(sha "$WORK/classes.dex")"
DEXSIZE="$(wc -c < "$WORK/classes.dex" | tr -d ' ')"

python3 - "$WORK/manifest.json" "$VARIANT" "$VERSION" "$STABLE" "$DEXSHA" "$DEXSIZE" <<'PY'
import json, sys
path, variant, version, stable, sha, size = sys.argv[1:7]
json.dump({
    "payloadType": "full", "variant": variant,
    "dynamicVersion": int(version), "stableApiVersion": int(stable),
    "targetDexSha256": sha, "targetSize": int(size),
    "dexFiles": [{"name": "classes.dex", "payload": "classes.dex", "targetSha256": sha, "targetSize": int(size)}],
    "hashes": {"classes.dex": sha},
}, open(path, "w"), separators=(",", ":"))
PY

# ECDSA P-256 over the manifest bytes; the manifest carries the dex checksum.
openssl dgst -sha256 -sign "$SIGN" -out "$WORK/signature.der" "$WORK/manifest.json"

BUNDLE="$WORK/bundle.zip"
(cd "$WORK" && zip -q -X "$BUNDLE" manifest.json classes.dex signature.der)
BSHA="$(sha "$BUNDLE")"
BSIZE="$(wc -c < "$BUNDLE" | tr -d ' ')"
NAME="lockin-dyn-$VARIANT-$VERSION.zip"

echo "Publishing $VARIANT code update $VERSION (bundle $BSIZE bytes, build $STABLE)"
curl -fsS -X POST "$URL/storage/v1/object/updates/$NAME" \
  -H "Authorization: Bearer $KEY" -H "x-upsert: true" \
  -H "Content-Type: application/octet-stream" --data-binary @"$BUNDLE" > /dev/null

# Merge into latest.json, keeping the APK entry and the other build type's entry.
CURRENT="$(curl -fsS "$URL/storage/v1/object/public/updates/latest.json?t=$(date +%s)" 2>/dev/null || echo '{}')"
JSON="$(python3 - "$CURRENT" "$VARIANT" "$VERSION" "$STABLE" "$URL/storage/v1/object/public/updates/$NAME" "$BSHA" "$BSIZE" "$NOTES" <<'PY'
import json, sys
cur, variant, version, stable, url, sha, size, notes = sys.argv[1:9]
try:
    j = json.loads(cur)
except Exception:
    j = {}
j.setdefault("dynamic", {})[variant] = {
    "dynamicVersion": int(version), "stableApiVersion": int(stable),
    "url": url, "sha256": sha, "size": int(size), "notes": notes,
}
print(json.dumps(j))
PY
)"
curl -fsS -X POST "$URL/storage/v1/object/updates/latest.json" \
  -H "Authorization: Bearer $KEY" -H "x-upsert: true" -H "cache-control: max-age=60" \
  -H "Content-Type: application/json" --data "$JSON" > /dev/null

echo "Done. Phones on this build offer the update the next time LockIn opens."

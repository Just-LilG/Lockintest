#!/usr/bin/env bash
# Publishes a build so installed phones offer it as an update.
#
#   1. Raise versionCode in app/build.gradle.kts (every release must be higher than the last) and build the APK.
#   2. export SUPABASE_SERVICE_ROLE_KEY=...   (Supabase dashboard > Project Settings > API; keep it private)
#   3. scripts/publish-update.sh app/build/outputs/apk/debug/app-debug.apk "What's new, one line"
#
# Add MANDATORY=1 to hide the "Later" button for this release.
# The APK must be signed with the same key as the version already on the phones, or Android refuses it.
set -euo pipefail

APK="${1:?Usage: publish-update.sh path/to/app.apk \"release note\"}"
NOTES="${2:-}"
URL="https://huhhzfbmsdajkppllako.supabase.co"
KEY="${SUPABASE_SERVICE_ROLE_KEY:?Set SUPABASE_SERVICE_ROLE_KEY first}"
GRADLE="$(dirname "$0")/../app/build.gradle.kts"

CODE="$(grep -E 'versionCode *=' "$GRADLE" | head -1 | grep -oE '[0-9]+')"
NAME="$(grep -E 'versionName *=' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
SHA="$( (sha256sum "$APK" 2>/dev/null || shasum -a 256 "$APK") | cut -d' ' -f1)"
SIZE="$(wc -c < "$APK" | tr -d ' ')"
FILE="lockin-${CODE}.apk"

echo "Publishing LockIn $NAME (code $CODE, $SIZE bytes)"

curl -fsS -X POST "$URL/storage/v1/object/updates/$FILE" \
  -H "Authorization: Bearer $KEY" -H "x-upsert: true" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" > /dev/null

# Keep any code-only updates already listed in latest.json.
CURRENT="$(curl -fsS "$URL/storage/v1/object/public/updates/latest.json?t=$(date +%s)" 2>/dev/null || echo '{}')"
JSON="$(python3 - "$CURRENT" "$CODE" "$NAME" "$NOTES" "$URL/storage/v1/object/public/updates/$FILE" "$SHA" "$SIZE" "${MANDATORY:-0}" <<'PY'
import json, sys
cur, c, n, notes, url, sha, size, mand = sys.argv[1:9]
try:
    j = json.loads(cur)
except Exception:
    j = {}
j.update({"versionCode": int(c), "versionName": n, "notes": notes, "url": url,
          "sha256": sha, "size": int(size), "mandatory": mand == "1"})
print(json.dumps(j))
PY
)"

# Uploaded last, so phones never see a release whose APK isn't there yet.
curl -fsS -X POST "$URL/storage/v1/object/updates/latest.json" \
  -H "Authorization: Bearer $KEY" -H "x-upsert: true" -H "cache-control: max-age=60" \
  -H "Content-Type: application/json" --data "$JSON" > /dev/null

echo "Done. Phones on an older build will offer the update the next time LockIn opens."

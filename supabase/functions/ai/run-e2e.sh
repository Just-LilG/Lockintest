#!/bin/sh
# Runs the real ai function under node against a fake Groq and a fake database (no network, no keys): the checks in
# e2e/run.mjs. Needs node 22 or newer. Run it from anywhere:  sh supabase/functions/ai/run-e2e.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
for f in mathjson.ts calc.ts routing.ts agent.ts; do cp "$HERE/$f" "$TMP/"; done
cp "$HERE/e2e/stub-supabase.ts" "$HERE/e2e/run.mjs" "$TMP/"
sed 's#import { createClient } from "npm:@supabase/supabase-js@2";#import { createClient } from "./stub-supabase.ts";#' "$HERE/index.ts" > "$TMP/index.ts"
cd "$TMP" && node --experimental-strip-types run.mjs 2>&1 | grep -v ExperimentalWarning | grep -v '^ai passage\|^ai digest\|^groq '
rm -rf "$TMP"

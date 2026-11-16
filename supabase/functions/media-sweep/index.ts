// Deletes the photos and voice notes the server no longer needs to hold (see 20261116_chat_media.sql).
//
// Called every hour by pg_cron (job "media-sweep"). It asks media_due() what is due:
//   - a photo / voice note the recipient has saved on their phone more than 6 hours ago,
//   - one nobody opened within 7 days (crew voice: 30 days),
//   - files of messages that were deleted,
// removes those files through Storage's API (deleting rows from storage.objects would leave the bytes behind),
// and tells the database they are gone. It needs no secret and takes no input: everything it can do is
// "delete what is already due", so calling it early or often only tidies sooner. It answers with counts only.
//
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided to every function automatically.

import { createClient } from "npm:@supabase/supabase-js@2";

const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

let lastRun = 0;

Deno.serve(async () => {
  // A second call within a minute has nothing new to do.
  if (Date.now() - lastRun < 60_000) return json({ skipped: true });
  lastRun = Date.now();

  const { data, error } = await admin.rpc("media_due", { p_limit: 100 });
  if (error) return json({ error: error.message }, 500);
  const rows = (data ?? []) as { kind: string; ref: number; bucket: string; name: string }[];
  if (rows.length === 0) return json({ removed: 0 });

  // Remove per bucket; only what Storage accepted is marked as gone.
  const byBucket = new Map<string, string[]>();
  for (const r of rows) byBucket.set(r.bucket, [...(byBucket.get(r.bucket) ?? []), r.name]);
  const okBuckets = new Set<string>();
  let removed = 0;
  for (const [bucket, names] of byBucket) {
    const { error: e } = await admin.storage.from(bucket).remove(names);
    if (e) { console.error("remove failed", bucket, e.message); continue; }
    okBuckets.add(bucket);
    removed += names.length;
  }

  const done = rows.filter((r) => okBuckets.has(r.bucket));
  const { error: e2 } = await admin.rpc("media_done", {
    p_msg: done.filter((r) => r.kind === "msg").map((r) => r.ref),
    p_crew: done.filter((r) => r.kind === "crew").map((r) => r.ref),
    p_trash: done.filter((r) => r.kind === "trash").map((r) => `${r.bucket}/${r.name}`),
  });
  if (e2) return json({ removed, error: e2.message }, 500);
  return json({ removed });
});

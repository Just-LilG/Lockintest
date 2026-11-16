// Sends a push to your partner's phone after you send a chat message or a nudge.
//
// The app calls this right after the message is saved, with the new message's id:
//   POST /functions/v1/push   { "messageId": 123 }   (Authorization: the signed-in person's token)
//
// It checks that the caller really sent that message a moment ago, finds the other person in the
// partnership, and sends a message to each of their phones through Firebase Cloud Messaging (v1).
// Events that only need to be seen go out as real notifications (Android shows them even while LockIn
// sleeps); events that make the phone act go out as data only, for the app's own code.
//
// Secret needed (Supabase dashboard > Edge Functions > Secrets):
//   FIREBASE_SERVICE_ACCOUNT  the whole JSON file from Firebase > Project settings > Service accounts.
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided to every function automatically.

import { createClient } from "npm:@supabase/supabase-js@2";

const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const headers = { "Content-Type": "application/json" };
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers });

// ---- Talking to Google ------------------------------------------------------------------------

type Account = { project_id: string; client_email: string; private_key: string };

let account: Account | null = null;
let signingKey: CryptoKey | null = null;
let cached: { token: string; expires: number } | null = null;

function loadAccount(): Account | null {
  if (account) return account;
  let raw = (Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "").trim();
  if (!raw) return null;
  // Forgiving about how it was pasted: wrapped in quotes, or with the key's line breaks written as "\\n".
  if (raw.startsWith('"') && raw.endsWith('"')) {
    try { raw = JSON.parse(raw); } catch { /* keep as is */ }
  }
  try {
    const a = JSON.parse(raw);
    if (!a.project_id || !a.client_email || !a.private_key) return null;
    a.private_key = String(a.private_key).replace(/\\n/g, "\n");
    account = a;
    return a;
  } catch {
    return null;
  }
}

function base64url(input: string | Uint8Array): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function importKey(pem: string): Promise<CryptoKey> {
  if (signingKey) return signingKey;
  const body = pem.replace(/-----BEGIN PRIVATE KEY-----/, "").replace(/-----END PRIVATE KEY-----/, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  signingKey = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return signingKey;
}

/** A short-lived Google access token, signed with the service account's key and reused until it nears expiry. */
async function googleToken(a: Account): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.expires - 60 > now) return cached.token;

  const head = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(JSON.stringify({
    iss: a.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const key = await importKey(a.private_key);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claims}`)));
  const assertion = `${head}.${claims}.${base64url(sig)}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!res.ok) throw new Error(`google_token_${res.status}`);
  const j = await res.json();
  cached = { token: j.access_token, expires: now + (j.expires_in ?? 3600) };
  return cached.token;
}

// ---- What to say ------------------------------------------------------------------------------

const EVENT_WORDS: Record<string, string> = {
  nudge: "nudged you to get back to it",
  asked_unlock: "asked you to unlock",
  emergency_release: "used the emergency release",
  unlock_denied: "said not yet to unlocking you",
  joined: "paired with you",
  sharing_on: "started sharing app usage",
  sharing_off: "stopped sharing app usage",
  unpair_asked: "asked to unpair. Open LockIn to answer",
  unpair_declined: "said not now to unpairing",
};

const PROTECT_WORDS: Record<string, string> = {
  admin: "switch off LockIn's device admin",
  uninstall: "uninstall or stop LockIn",
  overlay: "turn off \"display over other apps\"",
  usage: "turn off app-time access",
  guard: "switch off LockIn's guard",
  signout: "sign out of LockIn",
};

function minutes(m: number): string {
  if (!Number.isFinite(m) || m <= 0) return "a few minutes";
  return m < 60 ? `${m} min` : m % 60 === 0 ? `${m / 60} h` : `${Math.floor(m / 60)} h ${m % 60} min`;
}

// The warnings that void a lock (must match _void_kind in the database).
const VOIDS = ["admin_off", "overlay_off", "usage_off", "guard_off", "safe_mode", "time_changed"];

function lockTamperWords(a: string, b: string): string {
  if (a === "usage_off") return "turned off LockIn's app-time access during the lock you set";
  if (a === "guard_off") return "switched off LockIn's protection during the lock you set";
  if (a === "silent") return `LockIn on their phone stopped checking in ${minutes(Number(b))} ago, during the lock you set`;
  if (a === "admin_off") return "switched off LockIn's device admin during the lock you set";
  if (a === "overlay_off") return "turned off LockIn's \"display over other apps\" during the lock you set";
  if (a === "stopped") return `had LockIn stopped for ${minutes(Number(b))} during the lock you set`;
  if (a === "safe_mode") return `restarted into safe mode for ${minutes(Number(b))} during the lock you set`;
  if (a === "emergency_dial") return "opened the emergency dialer during the lock you set";
  if (a === "time_changed") return "changed the phone's clock during the lock you set";
  return "tried to get around the lock you set";
}

/** Some events carry details after a "|", like limit_set|Instagram|30. */
function words(kind: string, body: string): string {
  if (kind === "text") return body.length > 160 ? body.slice(0, 157) + "..." : body;
  const [code, a, b] = body.split("|");
  if (code === "summit") return Number(a) > 0 ? `climbed ${minutes(Number(a))} today and reached their goal` : "reached their goal today";
  if (code === "limit_set") return `limited ${a} to ${minutes(Number(b))} a day`;
  if (code === "limit_removed") return `lifted the ${a} limit`;
  if (code === "allow_set") return `allowed ${a} during your locks`;
  if (code === "allow_removed") return `took ${a} off your allowed apps`;
  if (code === "lock_set") return `locked your phone for ${minutes(Number(a))}`;
  if (code === "lock_lifted") return "unlocked your phone";
  if (code === "lock_extended") return `added ${minutes(Number(a))} to your lock`;
  if (code === "plan_set") return `planned a session: ${a}`;
  if (code === "plan_canceled") return `canceled the session: ${a}`;
  if (code === "lock_repeat") return `set a repeating ${minutes(Number(a))} lock on you. Open LockIn to see when`;
  if (code === "lock_repeat_off") return "removed a repeating lock";
  if (code === "game_invite") {
    if (a === "ludo") return "challenged you to Ludo. Tap to roll";
    return a === "wd" ? "challenged you to Word Duel. Tap to play" : "sent you a game of tic-tac-toe. Tap to play";
  }
  if (code === "quiz_challenge") {
    // quiz_challenge|<base64 of {"t": book title, "c": [[q, a], ...]}>
    let n = 0, t = "";
    try {
      const j = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(String(a).replace(/-/g, "+").replace(/_/g, "/")), (ch) => ch.charCodeAt(0))));
      n = Array.isArray(j?.c) ? j.c.length : 0;
      t = String(j?.t ?? "").slice(0, 40);
    } catch { /* an old or broken one: say it plainly */ }
    return `sent you a ${n > 0 ? `${n}-card ` : ""}quiz${t ? ` on ${t}` : ""}. Tap to play`;
  }
  if (code === "quiz_score") return `scored ${Number(a) || 0} of ${Number(b) || 0} on your quiz`;
  if (code === "game_move") {
    // game_move|ttt[|won] for tic-tac-toe, game_move|wd|<set|solved|failed|next> for Word Duel.
    // game_move|ludo|<turn|won|lost|again> for Ludo.
    if (a === "ludo") {
      if (b === "turn") return "rolled. Your turn at Ludo";
      if (b === "won") return "won at Ludo. Tap for a rematch";
      if (b === "lost") return "got beaten: you won at Ludo";
      if (b === "again") return "set up another game of Ludo. Tap to roll";
      return "made a move in Ludo";
    }
    if (a === "wd") {
      if (b === "set") return "set a secret word for you. Tap to guess";
      if (b === "solved") return "solved your word. Tap to see how";
      if (b === "failed") return "ran out of tries on your word. Tap to see it";
      if (b === "next") return "started a new round. Tap to play";
      return "made a move in Word Duel";
    }
    return b === "won" ? "won the game. Tap to see how" : "played their turn. Your move";
  }
  if (code === "lock_together_left") return "left the lock together early. You're still locked until it ends";
  // Lock hardening: the locked phone tells the locker when something tried to get around the lock.
  // Switching LockIn off on their phone needs your OK: protect_asked|<what>, protect_allowed|<what>, protect_declined|<what>.
  if (code === "protect_asked" || code === "protect_allowed" || code === "protect_declined") {
    const what = PROTECT_WORDS[a] ?? "change something on their phone";
    if (code === "protect_asked") return `wants to ${what}. Open LockIn to allow or say not yet`;
    if (code === "protect_allowed") return `allowed you to ${what} for 10 minutes`;
    return `said not yet to you wanting to ${what}`;
  }
  if (code === "lock_tamper") {
    const said = lockTamperWords(a, b);
    // A protection switched off for real voids the lock: its hours are not counted (the server does that).
    return VOIDS.includes(a) ? `${said}. That session won't count` : said;
  }
  // The same, with no lock running: protect_tamper|<what>
  if (code === "protect_tamper") {
    if (a === "admin_off") return "switched off LockIn's device admin";
    if (a === "overlay_off") return "turned off \"display over other apps\" for LockIn";
    if (a === "usage_off") return "turned off LockIn's app-time access";
    if (a === "guard_off") return "switched off LockIn's protection";
    if (a === "safe_mode") return "restarted their phone in safe mode, where LockIn can't run";
    return "tampered with LockIn's protection";
  }
  return EVENT_WORDS[code] ?? "sent you something";
}

// ---- How it shows ------------------------------------------------------------------------------

// What kind of notification an event is. Mirrors NotifyPrefs.kindOf in the app.
function kindOf(event: string): string {
  if (!event) return "messages";
  if (event === "nudge") return "nudges";
  if (event.startsWith("game_")) return "games";
  if (event.startsWith("plan_")) return "plans";
  if (event.startsWith("limit_")) return "limits";
  if (event.startsWith("lock_") || event.startsWith("protect_") || ["asked_unlock", "unlock_denied", "emergency_release"].includes(event)) return "locks";
  return "messages";
}

// Events that only need to be seen. These go out as real notifications, which Android shows by itself straight
// away, even when the phone is dozing or LockIn has been put to sleep by the battery saver. Events that make the
// phone do something (a lock starting, a limit) stay data only, so LockIn's own code handles them.
const DISPLAY = new Set([
  "", "nudge", "asked_unlock", "protect_asked", "protect_allowed", "protect_declined", "unlock_denied", "emergency_release", "lock_tamper", "protect_tamper", "lock_together_left",
  "joined", "sharing_on", "sharing_off", "unpair_asked", "unpair_declined",
  "plan_set", "plan_canceled", "lock_repeat_off", "allow_set", "allow_removed", "game_invite", "game_move",
  "summit", "quiz_challenge", "quiz_score",
]);

const SOUNDS = new Set(["message", "fah", "oh_no", "pikachu", "rain_hush", "sneeze", "whistle"]);

// Must match the channel ids the app creates (Push.kt / MessageSound.kt).
function channelFor(kind: string, sound: string): string {
  if (kind === "messages") return `lockin_m2_${sound}`;
  // "partner_nudges2" carries LockIn's own Nudge sound (the app creates it); an app without it falls back to the default channel.
  if (kind === "nudges") return "partner_nudges2";
  if (kind === "plans") return "partner_plans";
  if (kind === "limits") return "partner_limits";
  // The Games channel carries the game-request sound (the app creates it).
  if (kind === "games") return "partner_games";
  return "partner_locks";
}

function headline(event: string, name: string): string {
  if (event === "nudge") return `${name} nudged you`;
  if (event === "asked_unlock") return `${name} asked to be unlocked`;
  if (event === "protect_asked") return `${name} needs your OK`;
  if (event === "lock_tamper") return `Heads up: ${name}'s lock`;
  if (event === "protect_tamper") return `Heads up: ${name}'s protection`;
  if (event === "lock_together_left") return `${name} left early`;
  if (event === "summit") return `${name} reached the summit \u{1F3D4}\u{FE0F}`;
  return name;
}

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** "0:07" */
const clip = (ms: number) => { const s = Math.round(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };

// ---- The request ------------------------------------------------------------------------------

Deno.serve(async (req) => {
  if (req.method !== "POST" && req.method !== "GET") return reply({ error: "method_not_allowed" }, 405);

  // Who is asking. Checked here, not by the gateway, so it works with any kind of project key.
  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!jwt) return reply({ error: "unauthorized" }, 401);
  const { data: auth, error: authError } = await admin.auth.getUser(jwt);
  if (authError || !auth.user) return reply({ error: "unauthorized" }, 401);
  const me = auth.user.id;

  // A signed-in GET says whether the Firebase secret is set up correctly, without sending anything.
  if (req.method === "GET") {
    const found = loadAccount();
    return reply({ configured: found !== null, project: found?.project_id ?? null });
  }

  let messageId = 0;
  let crewMessageId = 0;
  try {
    const b = await req.json();
    messageId = Number(b.messageId ?? 0);
    crewMessageId = Number(b.crewMessageId ?? 0);
  } catch { /* handled below */ }

  const a = loadAccount();
  if (!a) return reply({ error: "push_not_configured" }, 503);

  // A crew message goes to everyone else in the crew.
  if (Number.isSafeInteger(crewMessageId) && crewMessageId > 0) return await crewPush(me, crewMessageId, a);
  if (!Number.isSafeInteger(messageId) || messageId <= 0) return reply({ error: "bad_request" }, 400);

  // The message must be mine and fresh, so this cannot be used to replay old messages.
  const { data: msg } = await admin
    .from("messages")
    .select("id, partnership_id, sender, kind, body, created_at, voice, voice_ms, media")
    .eq("id", messageId)
    .maybeSingle();
  if (!msg || msg.sender !== me) return reply({ error: "not_found" }, 404);
  if (Date.now() - new Date(msg.created_at).getTime() > 120_000) return reply({ sent: 0, reason: "stale" });

  const { data: p } = await admin.from("partnerships").select("owner, partner").eq("id", msg.partnership_id).maybeSingle();
  if (!p || !p.partner || (p.owner !== me && p.partner !== me)) return reply({ error: "not_found" }, 404);
  const to = p.owner === me ? p.partner : p.owner;

  const [{ data: tokens }, { data: profile }, { data: them }] = await Promise.all([
    admin.from("device_tokens").select("token").eq("user_id", to),
    admin.from("profiles").select("display_name, message_sound").eq("id", me).maybeSingle(),
    admin.from("profiles").select("notify_off").eq("id", to).maybeSingle(),
  ]);
  if (!tokens || tokens.length === 0) return reply({ sent: 0, reason: "no_devices" });

  const title = (profile?.display_name || "").trim() || "Your partner";
  const data = {
    type: "chat",
    title,
    // A voice message reads as one, with its length.
    body: msg.voice ? `🎤 Voice message (${clip(Number(msg.voice_ms) || 0)})`
      : msg.media ? `📷 ${msg.body && msg.body !== "Photo" ? msg.body : "Photo"}`
      : words(msg.kind, msg.body),
    partnership: String(msg.partnership_id),
    message: String(msg.id),
    // For events, which one (nudge, limit_set, ...), so the phone can react, such as refreshing its limits.
    event: msg.kind === "event" ? String(msg.body).split("|")[0] : "",
    // The whole event text, so the phone can word times in its own time zone (plan_set|Calculus|1794527647).
    detail: msg.kind === "event" ? String(msg.body) : "",
    // The sender's chosen message sound, so their partner's phone plays it (Fah, Whistle, ...).
    sound: String(profile?.message_sound || "message"),
  };

  // A shown notification for display events, unless they switched that kind off. Tapping it opens the right
  // place: the data keys become extras on the launch intent ("lockin.open").
  const event = data.event;
  const kind = kindOf(event);
  const muted = ((them?.notify_off as string[] | null) ?? []).includes(kind);
  const sound = SOUNDS.has(data.sound) ? data.sound : "message";
  const show = DISPLAY.has(event) && !muted;
  const open = kind === "messages" && !event ? "chat"
    : ["asked_unlock", "lock_tamper", "lock_together_left"].includes(event) ? "lock"
    : kind === "plans" || event.startsWith("unpair") ? "partner"
    : event === "summit" ? "party" : "chat";
  const shownBody = event === "nudge" ? "Time to lock in."
    : event === "asked_unlock" ? "Let them out now, or say not yet."
    : event === "summit" ? `${cap(data.body)}. Open LockIn to watch their party.`
    : cap(data.body);
  const payload = (token: string) => ({
    token,
    data: { ...data, "lockin.open": open, shown: show ? "1" : "0" },
    ...(show ? { notification: { title: headline(event, title), body: shownBody } } : {}),
    android: {
      priority: "HIGH",
      ttl: "3600s",
      ...(show ? {
        notification: {
          channel_id: channelFor(kind, sound),
          // For phones older than Android 8, which have no channels: the sound by its file name in res/raw.
          sound: kind === "messages" ? `sound_${sound}` : kind === "nudges" ? "sound_nudge" : kind === "games" ? "sound_game_request" : "default",
          icon: "ic_stat_lockin",
          color: "#C6F432",
          notification_priority: "PRIORITY_HIGH",
          default_vibrate_timings: true,
          visibility: "PRIVATE",
          // One notification per kind, the newest replacing the last, like the app's own.
          tag: `lockin_${kind}`,
        },
      } : {}),
    },
  });

  let access: string;
  try {
    access = await googleToken(a);
  } catch (e) {
    return reply({ error: "google_auth_failed", detail: String(e) }, 502);
  }

  let sent = 0;
  const dead: string[] = [];
  await Promise.all(tokens.map(async ({ token }) => {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${a.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: payload(token),
      }),
    });
    if (res.ok) sent++;
    else if (res.status === 404) dead.push(token); // the app was uninstalled or the token rotated
    else console.error("fcm", res.status, await res.text());
  }));

  if (dead.length) await admin.from("device_tokens").delete().in("token", dead);
  return reply({ sent });
});

// ---- Crews ------------------------------------------------------------------------------------

/** Sends [payload] to every token, removing tokens Google says are gone. Returns how many were accepted. */
async function sendAll(a: Account, tokens: string[], payload: (token: string) => unknown): Promise<number> {
  let access: string;
  try {
    access = await googleToken(a);
  } catch (e) {
    console.error("google_auth_failed", String(e));
    return 0;
  }
  let sent = 0;
  const dead: string[] = [];
  await Promise.all(tokens.map(async (token) => {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${a.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
      body: JSON.stringify({ message: payload(token) }),
    });
    if (res.ok) sent++;
    else if (res.status === 404) dead.push(token);
    else console.error("fcm", res.status, await res.text());
  }));
  if (dead.length) await admin.from("device_tokens").delete().in("token", dead);
  return sent;
}

function crewWords(event: string, detail: string, name: string, crew: string): { title: string; body: string; kind: string } | null {
  const [, a, b] = detail.split("|");
  if (event === "session_start") {
    const mins = Number(a);
    const at = Number(b) * 1000;
    const later = at - Date.now() > 60_000 ? `Starts in ${minutes(Math.round((at - Date.now()) / 60_000))}.` : "Starting now.";
    return { title: `${crew}: ${name} started a ${minutes(mins)} session`, body: `${later} Tap to join, or let it go.`, kind: "locks" };
  }
  if (event === "session_left") return { title: crew, body: `${name} left the session early, ${minutes(Number(a))} before the end.`, kind: "locks" };
  if (event === "session_canceled") return { title: crew, body: `${name} called off the session.`, kind: "locks" };
  if (event === "goal_set") return null;
  if (event === "goal_hit") return { title: crew, body: "The crew hit its weekly goal. Nice work.", kind: "messages" };
  if (event === "poke") return { title: crew, body: `${name} is waiting for you on the rope. Tap to join.`, kind: "locks" };
  if (event === "cheer") return { title: crew, body: b === "back" ? `${name} says get back on the rope.` : `${name} is cheering you on.`, kind: "messages" };
  if (event === "crew_joined") return { title: crew, body: `${name} joined the crew.`, kind: "messages" };
  if (event === "crew_left") return { title: crew, body: `${name} left the crew.`, kind: "messages" };
  if (event === "ludo_open") return { title: crew, body: `${name} opened a Ludo table. Tap to grab a seat.`, kind: "messages" };
  return null; // session_join, crew_made: shown in the crew chat, no push.
}

async function crewPush(me: string, id: number, a: Account): Promise<Response> {
  const { data: msg } = await admin
    .from("crew_messages")
    .select("id, crew_id, sender, kind, body, created_at, voice, voice_ms")
    .eq("id", id)
    .maybeSingle();
  if (!msg || msg.sender !== me) return reply({ error: "not_found" }, 404);
  if (Date.now() - new Date(msg.created_at).getTime() > 120_000) return reply({ sent: 0, reason: "stale" });

  const [{ data: crew }, { data: members }, { data: profile }] = await Promise.all([
    admin.from("crews").select("name").eq("id", msg.crew_id).maybeSingle(),
    admin.from("crew_members").select("user_id").eq("crew_id", msg.crew_id).neq("user_id", me),
    admin.from("profiles").select("display_name, message_sound").eq("id", me).maybeSingle(),
  ]);
  let others = (members ?? []).map((m) => m.user_id as string);
  // Pokes and cheers are for one person only: "poke|<user>" and "cheer|<user>|<kind>".
  const aimed = msg.kind === "event" ? String(msg.body).split("|") : [];
  if (aimed[0] === "poke" || aimed[0] === "cheer") others = others.filter((u) => u === aimed[1]);
  if (others.length === 0) return reply({ sent: 0, reason: "alone" });

  const name = (profile?.display_name || "").trim() || "Someone";
  const crewName = (crew?.name || "").trim() || "Your crew";
  const sound = SOUNDS.has(String(profile?.message_sound)) ? String(profile?.message_sound) : "message";
  const event = msg.kind === "event" ? String(msg.body).split("|")[0] : "";
  const said = msg.kind === "text"
    ? {
      title: `${name} · ${crewName}`,
      body: msg.voice ? `🎤 Voice message (${clip(Number(msg.voice_ms) || 0)})`
        : String(msg.body).length > 160 ? String(msg.body).slice(0, 157) + "..." : String(msg.body),
      kind: "messages",
    }
    : crewWords(event, String(msg.body), name, crewName);
  if (!said) return reply({ sent: 0, reason: "quiet_event" });

  const [{ data: tokens }, { data: prefs }] = await Promise.all([
    admin.from("device_tokens").select("user_id, token").in("user_id", others),
    admin.from("profiles").select("id, notify_off").in("id", others),
  ]);
  const off = new Map<string, string[]>((prefs ?? []).map((p: { id: string; notify_off: string[] | null }) => [p.id, p.notify_off ?? []]));

  const data = {
    type: "crew",
    title: said.title,
    body: said.body,
    crew: crewName,
    message: String(msg.id),
    event,
    detail: msg.kind === "event" ? String(msg.body) : "",
    sound,
    // A chat line (or a Ludo table) opens the crew chat; session news opens the Crew tab.
    "lockin.open": msg.kind === "text" || event.startsWith("ludo") ? "crew_chat" : "crew",
  };
  // Grouped by person, since each person may have switched a kind off.
  let sent = 0;
  const byUser = new Map<string, string[]>();
  for (const t of tokens ?? []) byUser.set(t.user_id, [...(byUser.get(t.user_id) ?? []), t.token]);
  await Promise.all([...byUser.entries()].map(async ([user, list]) => {
    const show = !(off.get(user) ?? []).includes(said.kind);
    sent += await sendAll(a, list, (token) => ({
      token,
      data: { ...data, shown: show ? "1" : "0" },
      ...(show ? { notification: { title: said.title, body: said.body } } : {}),
      android: {
        priority: "HIGH",
        ttl: "3600s",
        ...(show ? {
          notification: {
            channel_id: said.kind === "messages" ? `lockin_m2_${sound}` : "partner_locks",
            sound: said.kind === "messages" ? `sound_${sound}` : "default",
            icon: "ic_stat_lockin",
            color: "#C6F432",
            notification_priority: "PRIORITY_HIGH",
            default_vibrate_timings: true,
            visibility: "PRIVATE",
            tag: said.kind === "messages" ? "lockin_crew" : "lockin_crew_session",
          },
        } : {}),
      },
    }));
  }));
  return reply({ sent });
}

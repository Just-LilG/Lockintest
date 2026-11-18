// LockIn's study helper. The app sends pictures of the textbook pages you read; this asks an AI for a quiz, an
// explanation or flashcards about exactly those pages, and returns tidy JSON.
//
//   POST /functions/v1/ai   (Authorization: the signed-in person's token)
//   { "task": "quiz" | "explain" | "cards" | "simplify" | "passage" | "warm",
//     "pages": [{ "book": "Biology", "page": 42, "image": "<base64 jpeg>" }, ...],   // 1 to 12
//     "count": 6,              // quiz/cards: how many (optional)
//     "simpler": false,        // explain: say it more simply (optional)
//     "card": { "q": "...", "a": "..." },   // simplify: a flashcard someone keeps missing (pages optional, 0 or 1)
//     "snip": "<base64 jpeg>",   // passage: the part of the page the student selected (pages: that one page)
//     "mode": "explain" | "simpler" | "example" | "ask",   // passage (optional, explain by default)
//     "question": "...",         // passage, mode "ask": the student's question about the selection
//     "prior": "..." }           // passage: what was already said about this selection, so a follow-up doesn't repeat it
//   "warm" does no AI work: the app sends it when someone opens the AI sheet, so the function is awake, the keys are
//   loaded and the connection to Groq is open by the time the real request arrives.
//
// How a request is answered, fastest first:
//   1. Groq (about a second). Quizzes and flashcards are split into 2 to 4 parts that Groq writes AT THE SAME TIME
//      (writing is what takes the time, so three parts of 3 questions take about as long as one question), and put
//      back together. Each part goes to the next key in turn; a key that is rate-limited is left alone until Groq
//      says it is ready, and the part moves to another key straight away.
//   2. Google Gemini, when Groq fails, returns too little, or is merely slow: if Groq hasn't answered after
//      HEDGE_MS, Gemini is started as well and whichever gives a usable answer first wins (the other is cancelled).
//
// Maths: every task is told to write formulas as LaTeX between dollar signs ($...$ inline, $$...$$ on its own line), which
// the app draws as real maths (study/MathText.kt). LaTeX is full of backslashes, so replies go through mathjson.ts: a
// single \frac inside a JSON string would otherwise turn into a form feed or make the whole reply unparseable.
//
// Groq keys: the GROQ_API_KEY secret (comma-separated) plus the "groq", "groq2", "groq3", "groq4" rows of
// private.ai_keys (read through public.ai_key(), which only the service role may call). GROQ_MODEL optionally
// overrides the vision model.
//
// Secrets (Supabase dashboard > Edge Functions > Secrets):
//   GEMINI_API_KEY   from Google AI Studio (aistudio.google.com > Get API key). Never in the app.
//   GEMINI_MODEL     optional, tried first; defaults to gemini-3.8-flash (then gemini-3.5-flash-lite and gemini-flash-latest).
//   AI_DAILY_LIMIT   optional, AI calls per person per day, defaults to 40.
//   AI_HEDGE_MS      optional, how long Groq gets alone before Gemini joins in, defaults to 9000.

import { createClient } from "npm:@supabase/supabase-js@2";
import { cleanMathText, repairJsonBackslashes } from "./mathjson.ts";

const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
const headers = { "Content-Type": "application/json" };
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers });
const sleep = (ms: number) => new Promise<void>((ok) => setTimeout(ok, ms));

type Page = { book: string; page: number; image: string; label?: string };
type Passage = { mode: string; question: string; prior: string; book: string; page: number };
type Card = { q: string; a: string };
type Won = { result: any; via: "groq" | "gemini" };

const MAX_PAGES = 12;
const MAX_IMAGE = 700_000; // base64 characters per page (~500 KB)
const TOTAL_MS = 100_000; // the whole request; the app waits 115 s
const HEDGE_MS = Math.max(2_000, Number(Deno.env.get("AI_HEDGE_MS") || 9_000));

// ---- What to ask -------------------------------------------------------------------------------

// How every answer writes maths. The app draws $...$ and $$...$$ as typeset maths; plain "x^2" or "a/b" would show as
// typed. Doubled backslashes are for the JSON the model replies in (mathjson.ts repairs the ones it forgets).
const MATH = String.raw`MATHS FORMAT (the app draws it as real, typeset maths):
- Write every formula, equation, expression and maths symbol in LaTeX between dollar signs: $...$ inside a sentence, and $$...$$ alone on its own line for a main equation or a worked step.
- Never write maths as plain text. Not x^2, a/b, sqrt(x), *, <=, pi. Write $x^2$, $\frac{a}{b}$, $\sqrt{x}$, $\cdot$, $\le$, $\pi$.
- Put even one variable or number that is part of the maths in dollars: "the coefficient of $x^2$", "when $a = 3$". Ordinary words and units go outside the dollars, or inside \text{...}.
- Use plain LaTeX only: \frac{}{}, ^, _, \sqrt{}, \sqrt[n]{}, \left( ... \right), \sum, \int, \lim, \pm, \times, \cdot, \div, \le, \ge, \ne, \approx, \to, \infty, Greek letters (\alpha, \theta, \pi, \Delta ...), \vec{}, \bar{}, \hat{}, \overline{}, \begin{pmatrix}...\end{pmatrix}, \begin{cases}...\end{cases}, \begin{aligned}...\end{aligned}, \text{...}.
- A real dollar amount is written \$5.
- In a worked solution, give each step on its own $$...$$ line with one short sentence of words between steps.
- The reply is JSON, so every backslash inside a string must be doubled: write \\frac so the JSON holds \frac.`;

const RULES = `You are LockIn's study helper for a student.
Use ONLY what is on the textbook pages shown. Do not add outside facts. If a page is blank, a cover, a contents
page or unreadable, ignore it. Write in clear, friendly, simple English. Each page image is introduced by a line
"Page N of <book>"; when you say where something is, use that N.
${MATH}`;

const ORDINAL = ["first", "second", "third", "fourth"];

function passagePrompt(x: Passage): string {
  const base = `You are LockIn's study helper. A student reading the textbook "${x.book}" selected part of page ${x.page} and wants help with it.
The FIRST image is exactly what they selected: a word, a phrase, a sentence or two, an equation, a table cell or part of a figure.
The SECOND image is the whole page, so you can see where the selection sits and what the page is teaching.
Read the selection exactly as printed and put it in "text" (for an equation or any maths, write it as LaTeX between dollar signs, like "$v = u + at$"; plain words stay plain).
Explain it AS THIS BOOK USES IT: if the word has an everyday meaning and a special meaning in this subject, give the subject meaning.
You may use general knowledge to make the meaning clear, but stay true to what the page says. Plain, friendly English; short sentences.
If the selection is an equation, say what each symbol stands for and what the equation tells you.
If you cannot read the selection, set "text" to "" and "answer" to one sentence asking the student to select it again a little larger.
${MATH}`;
  const prior = x.prior ? `\nAlready said to the student about this selection (don't repeat it): ${x.prior}` : "";
  if (x.mode === "ask") {
    return `${base}${prior}
The student asks: "${x.question}"
Answer that question about the selection in "answer" (2 to 5 sentences, direct, using the page where it helps). Fill "text". Leave "meaning", "context" and "example" as "" and "terms" as [].`;
  }
  if (x.mode === "example") {
    return `${base}${prior}
Give ONE new, different, concrete example that makes the selection click, in "answer" (2 to 4 sentences). A worked example with numbers if it is maths or science. Fill "text". Leave the other fields empty ("" or []).`;
  }
  const simple = x.mode === "simpler";
  return `${base}${prior}
${simple ? "Say it as simply as you can, as if to a 12-year-old, with everyday words and no jargon.\n" : ""}"meaning": what the selection means, in ${simple ? "2 very simple" : "2 or 3"} sentences.
"context": what job it does on this page or in this topic (why the book says it here), in 1 or 2 sentences.
"example": one concrete everyday example (or a tiny worked example) in 1 or 2 sentences.
"terms": up to 3 harder words inside the selection, each {"term", "meaning"} with a meaning of a few words. [] if none.
"answer": "".`;
}

function promptFor(task: string, count: number, simpler: boolean, card?: Card, part?: [number, number], passage?: Passage): string {
  if (task === "passage" && passage) return passagePrompt(passage);
  if (task === "simplify") {
    return `You are LockIn's study helper. A student keeps getting this flashcard wrong:
Question: ${card?.q ?? ""}
Answer: ${card?.a ?? ""}
Rewrite it so it is easier to learn, without changing what it teaches. If a textbook page is shown, stay true to it.
"q": a clearer, more specific question (one short sentence).
"a": the answer, as short as it can be while still right (a few words, or one short sentence).
"hint": one memory hook (a mnemonic, an image or an everyday comparison) in one short sentence.
${MATH}`;
  }
  // When the same pages are written up by several helpers at once, each is given its own stretch of them.
  const share = part && part[1] > 1
    ? `\nYou are writing part ${part[0]} of ${part[1]} of this set; other helpers write the other parts from the same material at the same time. Take your ideas from the ${ORDINAL[part[0] - 1] ?? "next"} ${part[1] === 2 ? "half" : part[1] === 3 ? "third" : "quarter"} of what is on the page(s), top to bottom, so nothing repeats.`
    : "";
  if (task === "quiz") {
    return `${RULES}${share}
Write ${count} multiple-choice questions that check real understanding of the most important ideas on these pages
(not trivia like page titles). Each has exactly 4 options, one clearly correct. Mix easy and harder ones. Spread
them across the pages. For each, give "answer" (0-3, the index of the correct option), "why" (one short sentence
explaining the right answer, pointing at what the page says) and "page" (the N of the page it comes from).
Make the wrong options believable. Don't use "all of the above" or "none of the above".`;
  }
  if (task === "cards") {
    return `${RULES}${share}
Make ${count} flashcards for the most important facts, terms and ideas on these pages. "q" is a short question
or term, "a" a short answer (one or two sentences). "page" is the N of the page it comes from.`;
  }
  return `${RULES}
Explain the page${simpler ? " as simply as you can, as if to a 12-year-old, with everyday words" : ""}. Keep it tight.
"summary": two sentences on what this page is about.
"points": the 3 to 5 key ideas, each one short sentence${simpler ? ", very simple" : ""}.
"example": one concrete everyday example that makes the main idea click (one or two sentences).
"check": one quick question the student can ask themselves to see if they understood.`;
}

/** For Groq's JSON mode, which has no schema: the shape, spelled out. */
function shapeFor(task: string): string {
  if (task === "passage") return `Reply with JSON only: {"text": string, "meaning": string, "context": string, "example": string, "terms": [{"term": string, "meaning": string}], "answer": string}`;
  if (task === "simplify") return `Reply with JSON only: {"q": string, "a": string, "hint": string}`;
  if (task === "quiz") return `Reply with JSON only: {"questions": [{"q": string, "options": [4 strings], "answer": 0-3, "why": string, "page": number}]}`;
  if (task === "cards") return `Reply with JSON only: {"cards": [{"q": string, "a": string, "page": number}]}`;
  return `Reply with JSON only: {"summary": string, "points": [strings], "example": string, "check": string}`;
}

/** The first JSON object in a reply (tolerates code fences or stray words around it). */
function parseJson(text: string): any {
  const t = repairJsonBackslashes(text.replace(/<think>[\s\S]*?<\/think>/g, "").trim());
  try { return JSON.parse(t); } catch { /* look inside */ }
  const a = t.indexOf("{"), b = t.lastIndexOf("}");
  if (a >= 0 && b > a) return JSON.parse(t.slice(a, b + 1));
  throw new Error("no_json");
}

/** Gives up when either the request's own clock or the other provider's win says so. */
function within(ms: number, signal?: AbortSignal): AbortSignal {
  const t = AbortSignal.timeout(Math.max(500, ms));
  return signal ? AbortSignal.any([t, signal]) : t;
}

// ---- Groq --------------------------------------------------------------------------------------

// Several Groq keys can be kept. Parts of a request take turns across them; a key that is rate-limited sits out
// until Groq says it is ready (its Retry-After), and a refused key (401/403) sits out for ten minutes.
let groqKeys: string[] = [];
let groqKeysAt = 0;
let groqTurn = 0;
const coolUntil = new Map<string, number>();

async function getGroqKeys(): Promise<string[]> {
  // Re-read every ten minutes, so a key changed in the database is picked up without a redeploy.
  if (groqKeys.length && Date.now() - groqKeysAt < 600_000) return groqKeys;
  const env = (Deno.env.get("GROQ_API_KEY") ?? "").split(",").map((k) => k.trim()).filter(Boolean);
  const rows = await Promise.all(["groq", "groq2", "groq3", "groq4"].map(async (n) => {
    try {
      const { data } = await admin.rpc("ai_key", { p_name: n });
      return typeof data === "string" ? data.trim() : "";
    } catch {
      return "";
    }
  }));
  const found = [...new Set([...env, ...rows.filter(Boolean)])];
  if (found.length) { groqKeys = found; groqKeysAt = Date.now(); }
  return groqKeys;
}

/** The keys in the order to try: ready ones first (starting at this part's turn), then the soonest to be ready. */
function keyOrder(keys: string[], first: number): string[] {
  const now = Date.now();
  const turn = keys.map((_, i) => keys[(first + i) % keys.length]);
  const ready = turn.filter((k) => (coolUntil.get(k) ?? 0) <= now);
  const waiting = turn.filter((k) => (coolUntil.get(k) ?? 0) > now).sort((a, b) => coolUntil.get(a)! - coolUntil.get(b)!);
  return [...ready, ...waiting];
}

const GROQ_MODELS = [...new Set([Deno.env.get("GROQ_MODEL") || "qwen/qwen3.8-27b", "qwen/qwen3.6-27b"])];
const GROQ_MAX_IMAGES = 5;
const GROQ_TRY_MS = 16_000; // one attempt; a stuck one moves on to the next key
const GROQ_BUDGET_MS = 28_000; // all the attempts for one part
const GROQ_MAX_WAIT_MS = 3_500; // longest a part will wait for a rate-limited key before using another route

// What each model has been seen to accept, learned on the way (a 400 that names a setting switches it off).
const caps = new Map<string, { effort?: boolean; json?: boolean }>();
const capsOf = (m: string) => caps.get(m) ?? caps.set(m, {}).get(m)!;

/** One Groq request for one part: the prompt and up to 5 pages. Null if it didn't answer with usable JSON. */
async function groqOnce(
  keys: string[],
  first: number,
  prompt: string,
  pages: Page[],
  temperature: number,
  maxTokens: number,
  signal: AbortSignal,
): Promise<any | null> {
  const content: unknown[] = [{ type: "text", text: prompt }];
  for (const p of pages) {
    content.push({ type: "text", text: p.label ?? `Page ${p.page} of ${p.book}` });
    content.push({ type: "image_url", image_url: { url: `data:image/jpeg;base64,${p.image}` } });
  }
  const messages = [{ role: "user", content: pages.length ? content : prompt }];
  const deadline = Date.now() + GROQ_BUDGET_MS;
  const order = keyOrder(keys, first);
  models: for (const model of GROQ_MODELS) {
    const cap = capsOf(model);
    // What to send for this model. A 400 switches a setting off for the rest of this request only; it is
    // remembered for next time (in `cap`) once a request with that setting off has actually worked.
    let useEffort = cap.effort !== false;
    let useJson = cap.json !== false;
    for (const key of order) {
      const wait = (coolUntil.get(key) ?? 0) - Date.now();
      if (wait > 0) {
        // Every key is resting: a short wait is still faster than another provider, a long one is not.
        if (wait > GROQ_MAX_WAIT_MS || Date.now() + wait > deadline) continue;
        await sleep(wait);
      }
      for (let attempt = 0; attempt < 3; attempt++) {
        if (signal.aborted) return null;
        const left = deadline - Date.now();
        if (left < 2_500) return null;
        let r: Response;
        try {
          r = await fetch("https://api.groq.com/openai/v1/chat/completions", {
            method: "POST",
            headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
            body: JSON.stringify({
              model,
              temperature,
              max_completion_tokens: maxTokens,
              reasoning_format: "hidden",
              // Reading a page needs no long "thinking"; models that don't know the setting teach us once (below).
              ...(useEffort ? { reasoning_effort: "none" } : {}),
              ...(useJson ? { response_format: { type: "json_object" } } : {}),
              messages,
            }),
            signal: within(Math.min(GROQ_TRY_MS, left), signal),
          });
        } catch (e) {
          // Stuck or unreachable: another key may be fine, and Gemini is on its way if this drags on.
          if (!signal.aborted) console.error("groq fetch", model, "key" + (keys.indexOf(key) + 1), String(e));
          break;
        }
        if (r.ok) {
          try {
            const out = await r.json();
            const got = parseJson(out?.choices?.[0]?.message?.content ?? "");
            cap.effort = useEffort;
            cap.json = useJson;
            return got;
          } catch (e) {
            console.error("groq parse", model, String(e));
            break;
          }
        }
        const why = (await r.text().catch(() => "")).slice(0, 300);
        console.error("groq", model, "key" + (keys.indexOf(key) + 1), r.status, why);
        if (r.status === 400) {
          // Find which setting this model doesn't take, switch it off, and ask again.
          if (useEffort && (/reasoning_effort/i.test(why) || !useJson)) { useEffort = false; continue; }
          if (useJson) { useJson = false; continue; }
          if (useEffort) { useEffort = false; continue; }
          continue models;
        }
        if (r.status === 429) {
          const ra = Number(r.headers.get("retry-after"));
          coolUntil.set(key, Date.now() + Math.min(60_000, (Number.isFinite(ra) && ra > 0 ? ra : 5) * 1000));
          break;
        }
        if (r.status === 401 || r.status === 403) { coolUntil.set(key, Date.now() + 600_000); break; }
        if (r.status >= 500) break; // a bad moment at Groq: no point waiting on this key, the next one (or Gemini) gets a go
        continue models; // an unknown model or the like: the next model
      }
    }
  }
  return null;
}

type Shard = { count: number; pages: Page[]; part?: [number, number] };

/**
 * How to split a quiz or a set of cards so it is written in parallel: about 3 questions (4 cards) a part, at most
 * 4 parts, at most 5 pages a part. With enough pages each part gets its own pages; with fewer, every part reads
 * them all and is told which stretch to draw from.
 */
function plan(task: string, count: number, pages: Page[]): Shard[] {
  if (task === "explain" || task === "simplify" || task === "passage") return [{ count, pages: pages.slice(0, GROQ_MAX_IMAGES) }];
  const per = task === "quiz" ? 3 : 4;
  const n = Math.max(1, Math.min(4, count, Math.max(Math.ceil(count / per), Math.ceil(pages.length / GROQ_MAX_IMAGES))));
  const shares = Array.from({ length: n }, (_, i) => Math.floor(count / n) + (i < count % n ? 1 : 0));
  if (n === 1) return [{ count, pages: pages.slice(0, GROQ_MAX_IMAGES) }];
  if (pages.length >= n) {
    // Contiguous runs of pages, as even as they go.
    const out: Shard[] = [];
    let at = 0;
    for (let i = 0; i < n; i++) {
      const size = Math.floor(pages.length / n) + (i < pages.length % n ? 1 : 0);
      out.push({ count: shares[i], pages: pages.slice(at, at + size).slice(0, GROQ_MAX_IMAGES) });
      at += size;
    }
    return out;
  }
  return shares.map((c, i) => ({ count: c, pages, part: [i + 1, n] as [number, number] }));
}

const tokensFor = (task: string, count: number) =>
  task === "quiz" ? count * 520 + 600 : task === "cards" ? count * 240 + 500 : task === "simplify" ? 1000 : task === "passage" ? 1500 : 2100;

const norm = (s: unknown) => String(s ?? "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim().slice(0, 70);

/**
 * The whole task on Groq: the parts run side by side and are put back together, interleaved so the questions are
 * spread across the pages, with repeats dropped. Null if Groq can't do (enough of) it.
 */
async function groqTask(task: string, count: number, simpler: boolean, card: Card | undefined, pages: Page[], signal: AbortSignal, passage?: Passage): Promise<any | null> {
  const keys = await getGroqKeys();
  if (keys.length === 0) return null;
  const turn = groqTurn++;
  const temp = task === "explain" || task === "simplify" ? 0.5 : task === "passage" ? 0.35 : 0.4;
  const shards = plan(task, count, pages);
  const parts = await Promise.all(shards.map((s, i) =>
    groqOnce(keys, turn + i, promptFor(task, s.count, simpler, card, s.part, passage) + "\n" + shapeFor(task), s.pages, temp, tokensFor(task, s.count), signal)
      .catch((e) => { console.error("groq part", String(e)); return null; })
  ));
  if (shards.length === 1) return parts[0];
  const field = task === "quiz" ? "questions" : "cards";
  const lists = parts.filter((x) => x).map((x: any) => (Array.isArray(x?.[field]) ? x[field] : []));
  const seen = new Set<string>();
  const merged: unknown[] = [];
  for (let i = 0; lists.some((l) => i < l.length); i++) {
    for (const l of lists) {
      if (i >= l.length) continue;
      const k = norm((l[i] as any)?.q);
      if (k && seen.has(k)) continue;
      seen.add(k);
      merged.push(l[i]);
    }
  }
  // A few fewer than asked for is still a quiz, and quicker than starting again elsewhere.
  if (merged.length < Math.max(2, Math.ceil(count * 0.6))) return null;
  return { [field]: merged };
}

// ---- Gemini ------------------------------------------------------------------------------------

const STR = { type: "STRING" };
const INT = { type: "INTEGER" };

function schemaFor(task: string) {
  if (task === "passage") {
    return {
      type: "OBJECT",
      properties: {
        text: STR, meaning: STR, context: STR, example: STR, answer: STR,
        terms: { type: "ARRAY", items: { type: "OBJECT", properties: { term: STR, meaning: STR }, required: ["term", "meaning"] } },
      },
      required: ["text", "meaning", "context", "example", "terms", "answer"],
    };
  }
  if (task === "simplify") {
    return { type: "OBJECT", properties: { q: STR, a: STR, hint: STR }, required: ["q", "a", "hint"] };
  }
  if (task === "quiz") {
    return {
      type: "OBJECT",
      properties: {
        questions: {
          type: "ARRAY",
          items: {
            type: "OBJECT",
            properties: { q: STR, options: { type: "ARRAY", items: STR }, answer: INT, why: STR, page: INT },
            required: ["q", "options", "answer", "why", "page"],
          },
        },
      },
      required: ["questions"],
    };
  }
  if (task === "cards") {
    return {
      type: "OBJECT",
      properties: {
        cards: {
          type: "ARRAY",
          items: { type: "OBJECT", properties: { q: STR, a: STR, page: INT }, required: ["q", "a", "page"] },
        },
      },
      required: ["cards"],
    };
  }
  return {
    type: "OBJECT",
    properties: { summary: STR, points: { type: "ARRAY", items: STR }, example: STR, check: STR },
    required: ["summary", "points", "example", "check"],
  };
}

/** Gemini's last HTTP status, for choosing the error to give if nothing answers. */
let geminiStatus = 0;

/**
 * The task on Gemini: the newest flash model first, then a lighter one, then the "latest" alias (Google retires and
 * overloads models). Null if none answers before the request's time is up or [signal] says the other provider won.
 */
async function geminiTask(
  key: string,
  task: string,
  count: number,
  simpler: boolean,
  card: Card | undefined,
  pages: Page[],
  startedAt: number,
  signal: AbortSignal,
  passage?: Passage,
): Promise<any | null> {
  const parts: unknown[] = [{ text: promptFor(task, count, simpler, card, undefined, passage) }];
  for (const p of pages) {
    parts.push({ text: p.label ?? `Page ${p.page} of ${p.book}` });
    parts.push({ inline_data: { mime_type: "image/jpeg", data: p.image } });
  }
  const models = [...new Set([Deno.env.get("GEMINI_MODEL") || "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-flash-latest"])];
  const body0 = {
    contents: [{ role: "user", parts }],
    generationConfig: {
      temperature: task === "explain" || task === "simplify" ? 0.5 : task === "passage" ? 0.35 : 0.4,
      responseMimeType: "application/json",
      responseSchema: schemaFor(task),
    },
  };
  // Reading pages needs no long "thinking": switch it off to answer in seconds. Some models refuse that setting (400),
  // so those are asked again without it.
  const fast = JSON.stringify({ ...body0, generationConfig: { ...body0.generationConfig, thinkingConfig: { thinkingBudget: 0 } } });
  const plain = JSON.stringify(body0);
  let lastStatus = 0;
  const ask = async (m: string, payload: string): Promise<Response | null> => {
    const left = TOTAL_MS - (Date.now() - startedAt);
    if (left < 8_000 || signal.aborted) return null;
    try {
      const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(m)}:generateContent`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": key },
        body: payload,
        signal: within(Math.min(35_000, left), signal),
      });
      if (r.ok) return r;
      lastStatus = r.status;
      console.error("gemini", m, r.status, (await r.text()).slice(0, 300));
    } catch (e) {
      lastStatus = 0;
      if (!signal.aborted) console.error("gemini fetch", m, String(e));
    }
    return null;
  };
  for (const m of models) {
    let r = await ask(m, fast);
    if (!r && lastStatus === 400) r = await ask(m, plain);
    if (!r && (lastStatus === 429 || lastStatus === 503 || lastStatus === 500 || lastStatus === 502 || lastStatus === 504)) {
      await sleep(1200);
      r = await ask(m, fast);
    }
    if (!r) continue;
    try {
      const out = await r.json();
      const text = out?.candidates?.[0]?.content?.parts?.map((p: any) => p?.text ?? "").join("") ?? "";
      return JSON.parse(repairJsonBackslashes(text));
    } catch {
      lastStatus = 0;
      continue;
    }
  }
  geminiStatus = lastStatus;
  return null;
}

// ---- Checking what comes back ------------------------------------------------------------------

function clean(task: string, raw: any, pages: Page[], count: number) {
  const known = new Set(pages.map((p) => p.page));
  const pageOf = (n: unknown) => (known.has(Number(n)) ? Number(n) : pages[0]?.page ?? 1);
  // Text the student reads can hold LaTeX, so it is cleaned for that (mathjson.ts) and given a little more room.
  const s = (v: unknown, max = 400) => cleanMathText(v, Math.round(max * 1.3));
  if (task === "simplify") return { q: s(raw?.q, 300), a: s(raw?.a, 300), hint: s(raw?.hint, 300) };
  if (task === "passage") {
    return {
      text: s(raw?.text, 400),
      meaning: s(raw?.meaning, 700),
      context: s(raw?.context, 500),
      example: s(raw?.example, 500),
      terms: (Array.isArray(raw?.terms) ? raw.terms : []).slice(0, 4)
        .map((t: any) => ({ term: s(t?.term, 60), meaning: s(t?.meaning, 200) }))
        .filter((t: any) => t.term && t.meaning),
      answer: s(raw?.answer, 900),
    };
  }
  if (task === "quiz") {
    const qs = (Array.isArray(raw?.questions) ? raw.questions : [])
      .filter((q: any) => Array.isArray(q?.options) && q.options.length === 4 && Number(q.answer) >= 0 && Number(q.answer) <= 3 && s(q.q))
      .slice(0, count)
      .map((q: any) => ({ q: s(q.q, 300), options: q.options.map((o: unknown) => s(o, 160)), answer: Number(q.answer), why: s(q.why), page: pageOf(q.page) }));
    return { questions: qs };
  }
  if (task === "cards") {
    const cs = (Array.isArray(raw?.cards) ? raw.cards : [])
      .filter((c: any) => s(c?.q) && s(c?.a))
      .slice(0, count)
      .map((c: any) => ({ q: s(c.q, 200), a: s(c.a, 400), page: pageOf(c.page) }));
    return { cards: cs };
  }
  return {
    summary: s(raw?.summary, 600),
    points: (Array.isArray(raw?.points) ? raw.points : []).slice(0, 6).map((p: unknown) => s(p, 300)).filter(Boolean),
    example: s(raw?.example, 500),
    check: s(raw?.check, 300),
  };
}

const isEmpty = (task: string, result: any) =>
  task === "quiz" ? result.questions.length === 0
    : task === "cards" ? result.cards.length === 0
    : task === "simplify" ? !result.q || !result.a
    : task === "passage" ? !result.meaning && !result.answer
    : !result.summary;

/** The first of these to come back with something, or null if every one comes back empty-handed. */
function firstNonNull<T>(ps: Promise<T | null>[]): Promise<T | null> {
  return new Promise((resolve) => {
    let left = ps.length;
    if (left === 0) resolve(null);
    for (const p of ps) {
      p.then((v) => { if (v) resolve(v); else if (--left === 0) resolve(null); }, () => { if (--left === 0) resolve(null); });
    }
  });
}

// ---- Who is asking -----------------------------------------------------------------------------

// A token already checked a moment ago needn't be checked again (a quiz is often followed by cards).
const knownUsers = new Map<string, { id: string; until: number }>();
async function userOf(jwt: string): Promise<string | null> {
  const hit = knownUsers.get(jwt);
  if (hit && hit.until > Date.now()) return hit.id;
  const { data, error } = await admin.auth.getUser(jwt);
  if (error || !data?.user) return null;
  knownUsers.set(jwt, { id: data.user.id, until: Date.now() + 90_000 });
  if (knownUsers.size > 300) knownUsers.delete(knownUsers.keys().next().value as string);
  return data.user.id;
}

// ---- The request -------------------------------------------------------------------------------

Deno.serve(async (req) => {
  if (req.method !== "POST") return reply({ error: "method_not_allowed" }, 405);
  const startedAt = Date.now();
  geminiStatus = 0;
  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!jwt) return reply({ error: "unauthorized" }, 401);

  let body: any;
  try {
    body = await req.json();
  } catch {
    return reply({ error: "bad_request" }, 400);
  }
  const task = String(body?.task ?? "");

  // The app's "get ready": wake up, load the keys and open the connection to Groq. No allowance used.
  if (task === "warm") {
    const keys = await getGroqKeys();
    if (keys[0]) {
      fetch("https://api.groq.com/openai/v1/models", { headers: { Authorization: `Bearer ${keys[0]}` }, signal: AbortSignal.timeout(4_000) })
        .then((r) => r.body?.cancel()).catch(() => {});
    }
    return reply({ ok: true, keys: keys.length });
  }

  if (!["quiz", "explain", "cards", "simplify", "passage"].includes(task)) return reply({ error: "bad_request" }, 400);
  const card: Card | undefined = task === "simplify"
    ? { q: String(body?.card?.q ?? "").trim().slice(0, 500), a: String(body?.card?.a ?? "").trim().slice(0, 500) }
    : undefined;
  if (card && (!card.q || !card.a)) return reply({ error: "bad_request" }, 400);
  const pages: Page[] = (Array.isArray(body?.pages) ? body.pages : [])
    .filter((p: any) => typeof p?.image === "string" && p.image.length > 100 && p.image.length <= MAX_IMAGE)
    .slice(0, task === "explain" || task === "simplify" || task === "passage" ? 1 : MAX_PAGES)
    .map((p: any) => ({ book: String(p.book ?? "Your book").slice(0, 80), page: Math.max(1, Math.round(Number(p.page) || 1)), image: p.image }));
  if (pages.length === 0 && task !== "simplify") return reply({ error: "no_pages" }, 400);
  // A selection: its crop goes first (labelled as such), the whole page after it for context.
  let passage: Passage | undefined;
  if (task === "passage") {
    const snip = typeof body?.snip === "string" ? body.snip : "";
    if (snip.length < 100 || snip.length > MAX_IMAGE) return reply({ error: "bad_request" }, 400);
    const mode = ["explain", "simpler", "example", "ask"].includes(String(body?.mode)) ? String(body.mode) : "explain";
    const question = String(body?.question ?? "").replace(/\s+/g, " ").trim().slice(0, 300);
    if (mode === "ask" && !question) return reply({ error: "bad_request" }, 400);
    const pg = pages[0];
    passage = { mode, question, prior: String(body?.prior ?? "").replace(/\s+/g, " ").trim().slice(0, 1500), book: pg.book, page: pg.page };
    pages.unshift({ book: pg.book, page: pg.page, image: snip, label: `THE SELECTED PART (cut from page ${pg.page} of ${pg.book})` });
    pages[1] = { ...pg, label: `The whole page ${pg.page} of ${pg.book}, for context` };
  }
  const count = Math.min(Math.max(Math.round(Number(body?.count) || 6), 3), 10);
  const simpler = !!body?.simpler;

  // Who, and which Groq keys, at the same time.
  const gemini = Deno.env.get("GEMINI_API_KEY") ?? "";
  const [uid, keys] = await Promise.all([userOf(jwt), getGroqKeys()]);
  if (!uid) return reply({ error: "unauthorized" }, 401);
  // Gemini is the fallback; with neither provider set up there's nothing to ask.
  if (!gemini && keys.length === 0) return reply({ error: "ai_not_configured" }, 503);
  const limit = Number(Deno.env.get("AI_DAILY_LIMIT") || 40);

  // Groq starts straight away, while the daily allowance is being counted (over the limit is the rare case; the
  // work is then simply dropped).
  const ctl = new AbortController();
  const finish = (raw: any, via: "groq" | "gemini"): Won | null => {
    if (!raw) return null;
    const result = clean(task, raw, pages, count);
    if (isEmpty(task, result)) { if (via === "gemini") sawEmpty = true; return null; }
    return { result, via };
  };
  let sawEmpty = false;
  const groqP: Promise<Won | null> = groqTask(task, count, simpler, card, pages, ctl.signal, passage)
    .then((raw) => finish(raw, "groq"))
    .catch((e) => { console.error("groq task", String(e)); return null; });
  const geminiP = (): Promise<Won | null> => gemini
    ? geminiTask(gemini, task, count, simpler, card, pages, startedAt, ctl.signal, passage)
      .then((raw) => finish(raw, "gemini"))
      .catch((e) => { console.error("gemini task", String(e)); return null; })
    : Promise.resolve(null);

  const { data: take, error: takeError } = await admin.rpc("ai_take", { p_user: uid, p_limit: limit });
  if (takeError) { ctl.abort(); return reply({ error: "server" }, 500); }
  if (!take?.ok) { ctl.abort(); return reply({ error: "daily_limit", limit }, 429); }

  // Groq alone for a while; Gemini joins after HEDGE_MS, or at once if Groq has already given up.
  let timer: number | undefined;
  const hedge = new Promise<"hedge">((ok) => { timer = setTimeout(() => ok("hedge"), HEDGE_MS) as unknown as number; });
  const early = await Promise.race([groqP, hedge]);
  clearTimeout(timer);
  let won: Won | null;
  let gemini1: Promise<Won | null> | null = null;
  if (early && early !== "hedge") {
    won = early;
  } else if (early === "hedge") {
    gemini1 = geminiP();
    won = await firstNonNull([groqP, gemini1]);
  } else {
    gemini1 = geminiP();
    won = await gemini1;
  }
  ctl.abort(); // whoever is still working has lost

  const ms = Date.now() - startedAt;
  if (won) {
    console.log("ai", task, won.via, pages.length, "pages", ms, "ms", early === "hedge" ? "(hedged)" : "");
    return reply({ ...won.result, used: take.used, limit: take.limit, via: won.via, ms });
  }
  console.error("ai", task, "failed after", ms, "ms; gemini status", geminiStatus);
  // Nothing readable on the pages (Gemini read them and found nothing): not an error, and the allowance stays spent.
  if (sawEmpty) return reply({ error: "nothing_found" }, 422);
  await admin.rpc("ai_refund", { p_user: uid });
  const busy = !gemini || geminiStatus === 429 || geminiStatus === 503;
  return reply({ error: busy ? "ai_busy" : "ai_failed" }, busy ? 503 : 502);
});

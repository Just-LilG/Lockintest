import assert from "node:assert/strict";
let handler;
globalThis.Deno = { env: { get: (k) => ({ SUPABASE_URL: "http://x", SUPABASE_SERVICE_ROLE_KEY: "k", AI_HEDGE_MS: "60000" })[k] }, serve: (h) => { handler = h; } };
const calls = [];            // every request the server made to "Groq"
let script = [];             // what the fake Groq answers, in order
globalThis.fetch = async (url, init) => {
  const body = JSON.parse(init.body);
  calls.push({ url: String(url), body });
  const next = script.shift();
  const out = typeof next === "function" ? next(body) : next;
  if (!out) return new Response("no script", { status: 500 });
  return new Response(JSON.stringify(out), { status: 200, headers: { "content-type": "application/json" } });
};
await import("./index.ts");
const img = "A".repeat(200);
const say = (content) => ({ choices: [{ message: { content: JSON.stringify(content) } }] });
const passage = (q, mode = "ask", extra = {}) => handler(new Request("http://x/ai", {
  method: "POST", headers: { Authorization: "Bearer jwt" },
  body: JSON.stringify({ task: "passage", mode, question: q, snip: img, pages: [{ book: "Physics 9", page: 12, image: img }], ...extra }),
}));
const text = (c) => { const m = c.body.messages[0].content; return Array.isArray(m) ? m[0].text : m; };

// 1. THE COMPLAINT: a general question is told it may leave the book, and is told how to label the answer
script = [say({ text: "", meaning: "", context: "", example: "", terms: [], answer: "**Beyond your book:** Rainbows form when..." })];
let r = await passage("how do rainbows form?");
let j = await r.json();
assert.equal(r.status, 200); assert.match(j.answer, /Beyond your book/);
let p = text(calls[0]);
assert.match(p, /not a limit on what you may say/); assert.match(p, /answer it properly from your own knowledge/);
assert.match(p, /Beyond your book:/); assert.match(p, /From your book \(page N\):/);
assert.doesNotMatch(p, /Use ONLY what is on the textbook pages/);
assert.equal(calls[0].body.reasoning_effort, "none"); // a plain question stays fast
console.log("1 general question ok");

// 2. the book's text, neighbours, related passages, notes and weak spots reach the prompt (and are capped)
calls.length = 0;
script = [say({ text: "F = ma", meaning: "", context: "", example: "", terms: [], answer: "From your book (page 12): ..." })];
await passage("what is mass?", "ask", { ctx: { ptext: "Newton's second law: F = ma. ".repeat(400), refs: [{ page: 9, text: "Mass is the amount of matter in an object, measured in kilograms." }], notes: "Forces and motion.", weak: ["What is net force?"] } });
p = text(calls[0]);
assert.match(p, /TEXT OF PAGE 12/); assert.match(p, /page 9: Mass is the amount of matter/); assert.match(p, /NOTES ABOUT THIS BOOK/); assert.match(p, /KEEPS MISSING/);
assert.ok(p.length < 14000, "prompt stays bounded: " + p.length);
console.log("2 book context ok");

// 3. a calculation: thinking on, a calculator offered, tool call answered, reply used
calls.length = 0;
script = [
  { choices: [{ message: { content: "", tool_calls: [{ id: "t1", function: { name: "calc", arguments: '{"expression":"(3.5*12)/4"}' } }] } }] },
  say({ text: "", meaning: "", context: "", example: "", terms: [], answer: "**From your book (page 12):** the result is $\\frac{3.5 \\times 12}{4} = 10.5$." }),
  say({ ok: true, problem: "" }), // the independent judge agrees
];
r = await passage("calculate (3.5 * 12) / 4", "ask");
j = await r.json();
assert.match(j.answer, /10\.5/);
assert.equal(calls[0].body.tools[0].function.name, "calc"); assert.equal(calls[0].body.reasoning_effort, "default");
assert.equal(calls[1].body.messages.at(-1).role, "tool"); assert.equal(calls[1].body.messages.at(-1).content, "10.5");
assert.equal(calls[0].body.response_format, undefined);
console.log("3 calculator ok");

// 4. an answer with a wrong sum is asked for again, with the problem named, and the corrected one is kept
calls.length = 0;
const wrong = say({ text: "", meaning: "", context: "", example: "", terms: [], answer: "So $12 + 5 = 18$ and we are done." });
const right = say({ text: "", meaning: "", context: "", example: "", terms: [], answer: "So $12 + 5 = 17$ and we are done." });
script = [
  // quantitative question: calc loop first replies with no tool call (a wrong answer), then the redo goes the calc way too
  wrong, right,
];
r = await passage("what is 12 + 5?", "ask");
j = await r.json();
assert.match(j.answer, /= 17/);
assert.match(text(calls[1]), /A PREVIOUS TRY HAD A PROBLEM: the equation \$12 \+ 5 = 18\$ is not true/);
console.log("4 refine ok");

// 5. an answer that is right is not asked for again
calls.length = 0;
script = [right, say({ ok: true, problem: "" })];
r = await passage("what is 12 + 5?", "ask"); j = await r.json();
assert.match(j.answer, /= 17/);
// one call for the answer, one for the independent judge (a calculation), no redo
assert.equal(calls.length, 2, "answer + judge only");
console.log("5 no needless redo ok");

// 6. the judge finds a wrong result that has no checkable equation: the answer is redone
calls.length = 0;
script = [
  say({ text: "", meaning: "", context: "", example: "", terms: [], answer: "The speed is 40 m/s." }),   // answer
  say({ ok: false, problem: "Speed is 120 m / 4 s = 30 m/s, not 40." }),                                   // judge
  say({ text: "", meaning: "", context: "", example: "", terms: [], answer: "The speed is 30 m/s." }),   // redo
];
r = await passage("a car goes 120 m in 4 s, how fast is it?", "ask"); j = await r.json();
assert.match(j.answer, /30 m\/s/); assert.match(text(calls[2]), /Speed is 120 m \/ 4 s = 30 m\/s, not 40/);
console.log("6 judge ok");

// 7. an easy mode never pays for a second look
calls.length = 0;
script = [say({ text: "x", meaning: "It means...", context: "c", example: "e", terms: [], answer: "" })];
r = await passage("", "explain"); j = await r.json();
assert.equal(calls.length, 1); assert.equal(calls[0].body.reasoning_effort, "none");
console.log("7 easy stays easy ok");

// 8. hint ladder and quick check
calls.length = 0;
script = [say({ text: "", meaning: "", context: "", example: "", terms: [], answer: "Think about..." })];
await passage("", "hint", { level: 1 }); assert.match(text(calls[0]), /Level 1 of 3, a small nudge/);
calls.length = 0; script = [say({ text: "", meaning: "", context: "", example: "", terms: [], answer: "Step..." })];
await passage("", "hint", { level: 3 }); assert.match(text(calls[0]), /Level 3 of 3, the full worked solution/);
calls.length = 0; script = [say({ text: "", meaning: "", context: "", example: "", terms: [], answer: "Quick check: ..." })];
await passage("", "check"); assert.match(text(calls[0]), /beginning with "Quick check:"/);
console.log("8 hint + check ok");

// 9. notes for a book, from text only
calls.length = 0;
script = [say({ summary: "A physics book about forces and motion.", glossary: [{ term: "force", meaning: "a push or a pull" }, { term: "", meaning: "dropped" }] })];
r = await handler(new Request("http://x/ai", { method: "POST", headers: { Authorization: "Bearer jwt" }, body: JSON.stringify({ task: "digest", book: "Physics 9", texts: [{ page: 3, text: "A force is a push or a pull that changes motion. ".repeat(4) }, { page: 40, text: "Energy is the ability to do work. ".repeat(5) }] }) }));
j = await r.json();
assert.equal(r.status, 200); assert.match(j.summary, /forces and motion/); assert.deepEqual(j.glossary, [{ term: "force", meaning: "a push or a pull" }]);
assert.match(text(calls[0]), /\[page 40\] Energy is/);
r = await handler(new Request("http://x/ai", { method: "POST", headers: { Authorization: "Bearer jwt" }, body: JSON.stringify({ task: "digest", texts: [] }) }));
assert.equal(r.status, 400);
console.log("9 digest ok");

// 10. old clients (no new fields) still work exactly as before
calls.length = 0;
script = [say({ text: "x", meaning: "m", context: "c", example: "e", terms: [], answer: "" })];
r = await passage("", "explain"); assert.equal(r.status, 200);
console.log("10 old client ok");
console.log("ALL SERVER END-TO-END CHECKS PASSED");

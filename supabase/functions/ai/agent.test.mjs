import { calcLoop, judgeProblem, judgePrompt, CALC_TOOL } from "./agent.ts";
import assert from "node:assert/strict";
const parse = (t) => JSON.parse(t);
const reply = (message) => ({ ok: true, json: async () => ({ choices: [{ message }] }) });
const seen = [];
const scripted = (steps) => async (body) => { seen.push(JSON.parse(JSON.stringify(body))); const s = steps.shift(); return typeof s === "function" ? s(body) : s; };

// 1. the model calls the calculator twice (one step with two calls), then answers with what it was told
{
  const chat = scripted([
    reply({ content: "", tool_calls: [{ id: "a", function: { name: "calc", arguments: '{"expression":"12*12"}' } }, { id: "b", function: { name: "calc", arguments: '{"expression":"sqrt(2)^2"}' } }] }),
    reply({ content: '{"answer":"144 and 2"}' }),
  ]);
  const out = await calcLoop(chat, { model: "m", messages: [{ role: "user", content: "hi" }] }, parse);
  assert.deepEqual(out, { answer: "144 and 2" });
  assert.equal(seen[0].tools[0].function.name, "calc"); assert.equal(seen[0].tool_choice, "auto");
  const tools = seen[1].messages.filter((m) => m.role === "tool");
  assert.deepEqual(tools.map((t) => [t.tool_call_id, t.content]), [["a", "144"], ["b", "2"]]);
  assert.equal(seen[1].messages[1].role, "assistant"); assert.equal(seen[1].messages[1].tool_calls.length, 2);
}
// 2. a model that never stops calling gets no tools on the last step, so a reply always comes
{
  seen.length = 0;
  const call = reply({ content: "", tool_calls: [{ id: "x", function: { arguments: '{"expression":"1+1"}' } }] });
  const chat = scripted([call, call, call, reply({ content: '{"answer":"ok"}' })]);
  const out = await calcLoop(chat, { model: "m", messages: [{ role: "user", content: "hi" }] }, parse, 4);
  assert.deepEqual(out, { answer: "ok" }); assert.equal(seen.length, 4); assert.equal(seen[3].tools, undefined);
}
// 3. failures give null (the caller asks the ordinary way)
assert.equal(await calcLoop(async () => ({ ok: false, json: async () => ({}) }), { messages: [] }, parse), null);
assert.equal(await calcLoop(scripted([reply({ content: "not json" })]), { messages: [] }, parse), null);
assert.equal(await calcLoop(scripted([reply({ content: "" })]), { messages: [] }, parse), null);
// 4. bad tool arguments do not break the loop
{
  seen.length = 0;
  const chat = scripted([reply({ content: "", tool_calls: [{ id: "q", function: { arguments: "{broken" } }] }), reply({ content: '{"answer":"fine"}' })]);
  assert.deepEqual(await calcLoop(chat, { messages: [{ role: "user", content: "x" }] }, parse), { answer: "fine" });
  assert.match(seen[1].messages.at(-1).content, /^error/);
}
// 5. the judge
assert.equal(judgeProblem({ ok: true, problem: "" }), null);
assert.equal(judgeProblem({ ok: false, problem: "The total should be 18, not 17." }), "The total should be 18, not 17.");
assert.equal(judgeProblem({ ok: false, problem: "" }), null);
assert.equal(judgeProblem(null), null); assert.equal(judgeProblem("nope"), null);
assert.match(judgePrompt("What is 2+2?", "It is 5"), /ok.*false ONLY/s);
assert.ok(CALC_TOOL.function.parameters.required.includes("expression"));
console.log("agent ok");

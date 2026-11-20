/**
 * The "second look" machinery, kept apart from the request handler so it can be tested without a network:
 * a calculator the model can call mid-answer, and the independent check that reads a calculation again.
 */
import { calcTool } from "./calc.ts";

export const CALC_TOOL = {
  type: "function",
  function: {
    name: "calc",
    description: "Exact arithmetic. Pass one expression with numbers, + - * / ^, parentheses, sqrt, sin, cos, tan, ln, log, exp, abs, pi, e. Use it for every calculation instead of working it out in your head.",
    parameters: { type: "object", properties: { expression: { type: "string", description: "for example (3.5*12)/4 + sqrt(49)" } }, required: ["expression"] },
  },
};

export type ChatFn = (body: Record<string, unknown>) => Promise<{ ok: boolean; json: () => Promise<any> }>;

/**
 * A chat that may call the calculator. [chat] sends one request (the caller adds the key, the timeout and the URL);
 * the loop answers each tool call with [calcTool] and asks again, at most [maxSteps] times, the last without tools so a
 * reply always comes. Null when a request fails or the final reply cannot be read: the caller then asks the ordinary way.
 */
export async function calcLoop(chat: ChatFn, body0: Record<string, unknown>, parse: (text: string) => any, maxSteps = 5): Promise<any | null> {
  const messages = [...(body0.messages as any[])];
  for (let step = 0; step < maxSteps; step++) {
    const last = step === maxSteps - 1;
    const r = await chat({ ...body0, messages, ...(last ? {} : { tools: [CALC_TOOL], tool_choice: "auto" }) });
    if (!r.ok) return null;
    const out = await r.json();
    const msg = out?.choices?.[0]?.message;
    const calls = msg?.tool_calls;
    if (Array.isArray(calls) && calls.length && !last) {
      messages.push({ role: "assistant", content: msg.content ?? "", tool_calls: calls });
      for (const c of calls.slice(0, 6)) {
        let result: string;
        try { result = calcTool(JSON.parse(c?.function?.arguments ?? "{}").expression); } catch { result = "error: bad arguments"; }
        messages.push({ role: "tool", tool_call_id: c?.id, content: result });
      }
      continue;
    }
    try { return parse(msg?.content ?? ""); } catch { return null; }
  }
  return null;
}

/** The prompt for an independent re-derivation of an answer to a calculation. */
export function judgePrompt(question: string, answer: string): string {
  return `You are a strict checker of maths and science answers. A student asked: "${question.slice(0, 300)}"
The answer given to them was:
"""
${answer.slice(0, 2500)}
"""
Work the problem out yourself, step by step, then compare. Reply JSON {"ok": boolean, "problem": string}.
"ok" is false ONLY if the answer contains a wrong calculation, a wrong formula or a wrong final result. Different wording, a different method or a missing step is fine.
"problem" says in one sentence what is wrong and what the right value is, or "" when ok is true.`;
}

/** The problem a judge found, or null when it found none (or said nothing usable). */
export function judgeProblem(raw: any): string | null {
  if (!raw || raw.ok !== false) return null;
  const p = String(raw.problem ?? "").replace(/\s+/g, " ").trim();
  return p.length >= 8 ? p.slice(0, 300) : null;
}

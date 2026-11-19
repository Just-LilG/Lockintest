import { repairJsonBackslashes as fix, unmangleMath, cleanMathText } from "./mathjson.ts";
import assert from "node:assert/strict";

const parse = (raw) => JSON.parse(fix(raw));
// 1. single backslash (what models actually do) — \f \t \b \r \n are the dangerous ones
assert.equal(parse(String.raw`{"a":"$\frac{a}{b}$"}`).a, String.raw`$\frac{a}{b}$`);
assert.equal(parse(String.raw`{"a":"$\theta + \beta \times \right) \nu \neq \nabla$"}`).a, String.raw`$\theta + \beta \times \right) \nu \neq \nabla$`);
assert.equal(parse(String.raw`{"a":"$\sqrt{x} \alpha \pi \sum \int \left( x \right) \pm \le \ge \cdot \infty \vec{v} \bar{x} \text{hi}$"}`).a,
             String.raw`$\sqrt{x} \alpha \pi \sum \int \left( x \right) \pm \le \ge \cdot \infty \vec{v} \bar{x} \text{hi}$`);
// 2. already doubled (what the prompt asks for) is untouched
assert.equal(parse(String.raw`{"a":"$\\frac{a}{b} \\theta$"}`).a, String.raw`$\frac{a}{b} \theta$`);
// 3. real escapes survive: newline, tab (not before a letter), quote, unicode, slash
assert.equal(parse(String.raw`{"a":"one\ntwo\n\nthree \"q\" \u00e9 a\/b x\t y"}`).a, "one\ntwo\n\nthree \"q\" é a/b x\t y");
// 4. newline followed by a word that merely starts like a command is still a newline
assert.equal(parse(String.raw`{"a":"line\nnext one\nnote"}`).a, "line\nnext one\nnote");
assert.equal(parse(String.raw`{"a":"x\neq y"}`).a, String.raw`x\neq y`);
// 5. money and odd escapes do not break parsing
assert.equal(parse(String.raw`{"a":"costs \$5 \(x\) \[y\] \% \& \_ \{ \}"}`).a, String.raw`costs \$5 \(x\) \[y\] \% \& \_ \{ \}`);
// 6. \u that is not a unicode escape: \upsilon, \underline, \uparrow
assert.equal(parse(String.raw`{"a":"\upsilon \underline{x} \uparrow"}`).a, String.raw`\upsilon \underline{x} \uparrow`);
// 7. matrices: \\ rows (doubled in JSON) and \begin / \end survive
assert.equal(parse(String.raw`{"a":"\begin{pmatrix}1&2\\3&4\end{pmatrix}"}`).a, String.raw`\begin{pmatrix}1&2\3&4\end{pmatrix}`.replace("2\\3", "2\\3"));
// 8. a reply that was ALREADY parsed with the damage in it comes back
const damaged = "$\x0crac{a}{b} \x09heta \x08eta \x0dight$ \nu";
assert.equal(unmangleMath(damaged), String.raw`$\frac{a}{b} \theta \beta \right$ ` + "\\nu");
// 9. clipping never leaves a formula open
assert.equal(cleanMathText("Use $x^2 + 1$ and $y^2 + 2$ now", 22), "Use $x^2 + 1$ and");
assert.equal(cleanMathText("Costs \\$5 and \\$6", 100), "Costs \\$5 and \\$6");
// 9b. clipping never leaves a code fence open; \textcolor (a \t escape lookalike) survives the repair
assert.equal(cleanMathText("Intro\n```plot\ny = x^2\nx: -1..1\n```\nmore text here", 30), "Intro");
assert.equal(cleanMathText("Intro\n```plot\ny = x^2\n```", 100), "Intro\n```plot\ny = x^2\n```");
assert.equal(parse(String.raw`{"a":"$\textcolor{lime}{a}\times b$"}`).a, String.raw`$\textcolor{lime}{a}\times b$`);
// 10. fuzz with well-formed pieces only (no bare quotes, no trailing lone backslash)
const pieces = ["\\\\", '\\"', "\\f", "\\t", "\\n", "\\u", "\\u12", "\\u00e9", "\\x", "\\(", "\\$", "a", "n", " ", "{", "}", "$", "frac", "theta", "\\\\n"];
let valid = 0, repaired = 0;
for (let i = 0; i < 100000; i++) {
  let body = "";
  for (let k = 0; k < 10; k++) body += pieces[Math.floor(Math.random() * pieces.length)];
  const raw = `{"a":"${body}"}`;
  let original = null;
  try { original = JSON.parse(raw); } catch { /* invalid as written: the repair has to fix it */ }
  let fixed;
  try { fixed = JSON.parse(fix(raw)); } catch (e) { console.log("UNPARSEABLE after repair:", body); process.exit(1); }
  if (original) {
    // already valid JSON must come back identical unless it holds a control escape that LaTeX also claims
    const ambiguous = /\\[bfrt][A-Za-z]|\\n(?:u|eq|abla|otin|ewline|eg|e)(?![A-Za-z])/.test(body.replace(/\\\\/g, ""));
    if (!ambiguous) assert.deepEqual(fixed, original, "changed valid JSON: " + body);
    valid++;
  } else repaired++;
}
console.log("all ok; fuzz valid:", valid, "repaired:", repaired);

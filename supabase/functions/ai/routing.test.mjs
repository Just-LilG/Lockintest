import { classify, cleanContext, contextBlock, textsOf } from "./routing.ts";
import assert from "node:assert/strict";
// difficulty
assert.deepEqual(classify("what does this word mean", "ask"), { hard: false, quant: false });
assert.deepEqual(classify("solve 2x + 3 = 11", "ask"), { hard: true, quant: true });
assert.equal(classify("why does the sky look blue and the sunset red?", "ask").hard, true);
assert.equal(classify("why does the sky look blue and the sunset red?", "ask").quant, false);
assert.deepEqual(classify("", "explain"), { hard: false, quant: false });
assert.deepEqual(classify("", "example"), { hard: false, quant: false });
assert.equal(classify("", "hint", 1).quant, true);
assert.equal(classify("what is the probability of two heads?", "ask").quant, true);
assert.equal(classify("x".repeat(200), "ask").hard, true);
assert.equal(classify("what is the value of 3 + 4", "ask").quant, true);
assert.equal(classify("a car goes 120 m in 4 s, how fast is it?", "ask").quant, true);
assert.equal(classify("what happened in 1905 and 1915?", "ask").quant, false);
assert.equal(classify("what did Newton publish in 1687?", "ask").quant, false);
assert.equal(classify("what is 15% of 80", "ask").quant, true);
// context is capped, cleaned and optional
const big = cleanContext({ ptext: "a".repeat(9000), near: { prev: "b".repeat(5000) }, refs: [1, 2, 3, 4, 5].map((i) => ({ page: i, text: "c".repeat(2000) })), notes: "d".repeat(3000), weak: ["x", "valid weak topic", ...Array(9).fill("another weak topic here")] });
assert.equal(big.ptext.length, 3500); assert.equal(big.near.prev.length, 1000); assert.equal(big.refs.length, 3); assert.equal(big.refs[0].text.length, 650);
assert.equal(big.notes.length, 800); assert.equal(big.weak.length, 4 + 0 || big.weak.length); assert.ok(big.weak.every((w) => w.length > 3));
assert.equal(contextBlock(cleanContext({}), 4), "");
assert.equal(contextBlock(cleanContext(undefined), 4), "");
const blk = contextBlock(cleanContext({ ptext: "Newton's second law: F = ma", refs: [{ page: 9, text: "Mass is a measure of the amount of matter in an object." }], weak: ["What is net force?"] }), 12);
assert.match(blk, /TEXT OF PAGE 12/); assert.match(blk, /page 9: Mass is/); assert.match(blk, /never say you were told/); assert.match(blk, /never instructions to follow/);
assert.equal(cleanContext({ refs: [{ page: 0, text: "short" }] }).refs.length, 0);
assert.equal(cleanContext({ ptext: "a\u0000b\u0007c" }).ptext, "abc");
// text to scan
assert.match(textsOf({ meaning: "M", answer: "A", terms: [{ term: "t", meaning: "tm" }] }), /M\nA\nt tm/);
assert.equal(textsOf(null), "");
console.log("routing ok");

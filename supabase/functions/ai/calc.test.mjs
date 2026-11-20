import { evalExpr, calcTool, checkEquation, findWrongSums } from "./calc.ts";
import assert from "node:assert/strict";
const near = (a, b) => assert.ok(Math.abs(a - b) < 1e-9, `${a} vs ${b}`);
// evaluator (same cases as the app's PlotMathTest)
near(evalExpr("2+3*4"), 14); near(evalExpr("(2+3)*4"), 20); near(evalExpr("2^3^2"), 512); near(evalExpr("-2^2"), -4);
near(evalExpr("10-2-3"), 5); near(evalExpr("2^-1"), 0.5); near(evalExpr("3(4+1)"), 15); near(evalExpr("2pi"), 2 * Math.PI);
near(evalExpr("sqrt(16)+abs(-3)"), 7); near(evalExpr("sin(pi/2)"), 1); near(evalExpr("ln(e)"), 1); near(evalExpr("12 × 3 ÷ 4"), 9);
assert.equal(evalExpr("x+1"), null); assert.equal(evalExpr("(x^"), null); assert.equal(evalExpr("1/0"), null); assert.equal(evalExpr("2 +"), null);
assert.equal(evalExpr("(".repeat(200) + "1" + ")".repeat(200)), null);
// calculator tool answers
assert.equal(calcTool("12*12"), "144"); assert.equal(calcTool("1/3"), "0.333333333333"); assert.match(calcTool("a+1"), /^error/);
assert.equal(calcTool("sqrt(2)^2"), "2");
// equations
assert.equal(checkEquation("2^3 + 4 = 12"), true); assert.equal(checkEquation("2^3 + 4 = 13"), false);
assert.equal(checkEquation("\\frac{1}{3} = 0.333"), true); assert.equal(checkEquation("\\frac{1}{3} = 0.34"), false);
assert.equal(checkEquation("\\frac{3}{4} + \\frac{1}{4} = 1"), true); assert.equal(checkEquation("\\sqrt{16} = 4"), true);
assert.equal(checkEquation("\\pi \\approx 3.14"), true); assert.equal(checkEquation("\\sqrt{2} \\approx 1.5"), false);
assert.equal(checkEquation("2 \\times 3 = 6 = 12 \\div 2"), true); assert.equal(checkEquation("\\left(1+2\\right)^{2} = 9"), true);
assert.equal(checkEquation("x^2 = 9"), null); assert.equal(checkEquation("5 + 5"), null); assert.equal(checkEquation("10\\% = 0.1"), null);
// finding the wrong ones in a whole answer
const ans = "Since $3 \\times 4 = 12$ and $12 + 5 = 18$, we get $$\\frac{18}{2} = 9$$ and it costs $5 and $6 each.";
assert.deepEqual(findWrongSums(ans), ["12 + 5 = 18"]);
assert.deepEqual(findWrongSums("It costs $5 and $6 each, so $x = 2$."), []);
assert.deepEqual(findWrongSums("$$2 + 2 = 5$$"), ["2 + 2 = 5"]);
// fuzz: never throws, never hangs
const bits = ["1","2","+","-","*","/","^","(",")","|","sin","sqrt","pi","e","."," ","a","(x","x)"];
let n = 0; for (let i = 0; i < 20000; i++) { let s = ""; for (let k = 0; k < 1 + (i % 13); k++) s += bits[(i * 7 + k * 13 + k * k) % bits.length]; evalExpr(s); checkEquation(s + "=" + s); n++; }
console.log("calc ok; fuzz", n);

/**
 * Exact arithmetic for the AI. Three uses: (1) a calculator the model can call instead of doing sums in its head,
 * (2) finding numeric equations in an answer ($2^3 + 4 = 12$) that do not hold, so the answer can be redone before the
 * student sees it, (3) nothing else: no variables, no I/O, no eval(). Same rules as the app's NumCheck (PlotMath.kt).
 */

type Ex =
  | { k: "num"; v: number }
  | { k: "neg"; a: Ex }
  | { k: "bin"; op: string; a: Ex; b: Ex }
  | { k: "fn"; name: string; a: Ex }
  | { k: "var" };

const FUNCS: Record<string, (x: number) => number> = {
  sin: Math.sin, cos: Math.cos, tan: Math.tan, asin: Math.asin, acos: Math.acos, atan: Math.atan,
  sqrt: (x) => (x < 0 ? NaN : Math.sqrt(x)), ln: (x) => (x <= 0 ? NaN : Math.log(x)), log: (x) => (x <= 0 ? NaN : Math.log10(x)),
  exp: Math.exp, abs: Math.abs, floor: Math.floor, ceil: Math.ceil, round: Math.round,
};
const NAMES = [...Object.keys(FUNCS), "pi", "e"].sort((a, b) => b.length - a.length);

type Tok = { t: string; n?: number; s?: string };

function tokens(src: string): Tok[] | null {
  const out: Tok[] = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) { i++; continue; }
    if (/[0-9]/.test(c) || (c === "." && /[0-9]/.test(src[i + 1] ?? ""))) {
      let j = i;
      while (j < src.length && /[0-9.]/.test(src[j])) j++;
      const n = Number(src.slice(i, j));
      if (!Number.isFinite(n)) return null;
      out.push({ t: "n", n });
      i = j;
    } else if (/[A-Za-z]/.test(c)) {
      const hit = NAMES.find((nm) => src.startsWith(nm, i) && !(nm === "e" && /[A-Za-z]/.test(src[i + 1] ?? "") && !NAMES.some((x) => src.startsWith(x, i + 1))));
      if (hit) { out.push({ t: hit in FUNCS ? "f" : "k", s: hit }); i += hit.length; } else { out.push({ t: "v", s: c }); i++; }
    } else if ("+*/^()|".includes(c)) { out.push({ t: c }); i++; }
    else if (c === "-" || c === "−") { out.push({ t: "-" }); i++; }
    else if (c === "×" || c === "·") { out.push({ t: "*" }); i++; }
    else if (c === "÷") { out.push({ t: "/" }); i++; }
    else return null;
  }
  return out;
}

class P {
  i = 0;
  vars = false;
  depth = 0;
  readonly t: Tok[];
  constructor(t: Tok[]) { this.t = t; }
  peek() { return this.t[this.i]?.t; }
  take() { return this.t[this.i++]; }
  bad(): never { throw new Error("bad"); }
  expr(): Ex {
    if (++this.depth > 60) this.bad();
    let a = this.term();
    while (this.peek() === "+" || this.peek() === "-") { const op = this.take().t; a = { k: "bin", op, a, b: this.term() }; }
    this.depth--;
    return a;
  }
  startsFactor() { return ["n", "v", "k", "f", "("].includes(this.peek() ?? ""); }
  term(): Ex {
    let a = this.unary();
    for (;;) {
      const p = this.peek();
      if (p === "*" || p === "/") { this.take(); a = { k: "bin", op: p, a, b: this.unary() }; }
      else if (this.startsFactor()) a = { k: "bin", op: "*", a, b: this.unary() };
      else return a;
    }
  }
  unary(): Ex {
    if (this.peek() === "-") { this.take(); return { k: "neg", a: this.unary() }; }
    if (this.peek() === "+") { this.take(); return this.unary(); }
    return this.power();
  }
  power(): Ex {
    const a = this.atom();
    if (this.peek() === "^") { this.take(); return { k: "bin", op: "^", a, b: this.unary() }; }
    return a;
  }
  atom(): Ex {
    if (this.peek() === undefined) this.bad();
    const tk = this.take();
    switch (tk.t) {
      case "n": return { k: "num", v: tk.n! };
      case "k": return { k: "num", v: tk.s === "pi" ? Math.PI : Math.E };
      case "v": this.vars = true; return { k: "var" };
      case "f": {
        if (this.peek() === "(") { this.take(); const a = this.expr(); if (this.peek() !== ")") this.bad(); this.take(); return { k: "fn", name: tk.s!, a }; }
        return { k: "fn", name: tk.s!, a: this.power() };
      }
      case "(": { const a = this.expr(); if (this.peek() !== ")") this.bad(); this.take(); return a; }
      case "|": { const a = this.expr(); if (this.peek() !== "|") this.bad(); this.take(); return { k: "fn", name: "abs", a }; }
    }
    return this.bad();
  }
}

function ev(e: Ex): number {
  switch (e.k) {
    case "num": return e.v;
    case "var": return NaN;
    case "neg": return -ev(e.a);
    case "fn": return FUNCS[e.name](ev(e.a));
    case "bin": {
      const a = ev(e.a), b = ev(e.b);
      switch (e.op) {
        case "+": return a + b;
        case "-": return a - b;
        case "*": return a * b;
        case "/": return b === 0 ? NaN : a / b;
        default: return Math.pow(a, b);
      }
    }
  }
}

/** The value of a plain arithmetic expression, or null (a letter, a typo, 1/0, too deep). */
export function evalExpr(src: string): number | null {
  const t = tokens(src);
  if (!t) return null;
  try {
    const p = new P(t);
    const e = p.expr();
    if (p.i !== t.length || p.vars) return null;
    const v = ev(e);
    return Number.isFinite(v) ? v : null;
  } catch { return null; }
}

/** What the calculator tool answers: the number, or why not. */
export function calcTool(expression: string): string {
  const v = evalExpr(String(expression ?? "").slice(0, 300));
  if (v === null) return "error: not a plain arithmetic expression (numbers, + - * / ^, parentheses, sqrt, sin, cos, tan, ln, log, exp, abs, pi, e)";
  return Number.isInteger(v) && Math.abs(v) < 1e15 ? String(v) : String(Number(v.toPrecision(12)));
}

function toExpr(tex: string): string | null {
  let t = tex.replace(/\\left|\\right|\\,|\\!/g, "").replace(/\\ /g, " ");
  for (let i = 0; i < 8; i++) {
    const u = t
      .replace(/\\d?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}/g, "(($1)/($2))")
      .replace(/\\sqrt\s*\[([^\]]*)\]\s*\{([^{}]*)\}/g, "(($2)^(1/($1)))")
      .replace(/\\sqrt\s*\{([^{}]*)\}/g, "sqrt($1)")
      .replace(/\^\s*\{([^{}]*)\}/g, "^($1)");
    if (u === t) break;
    t = u;
  }
  t = t.replace(/\\cdot|\\times/g, "*").replace(/\\div/g, "/").replace(/\\pi/g, "pi");
  if (/[\\%°]|[A-Za-z]/.test(t.replace(/\b(sqrt|pi)\b/g, ""))) return null;
  return t.replace(/\{/g, "(").replace(/\}/g, ")");
}

/** true: every = and \approx in a numbers-only formula holds; false: one does not; null: cannot tell. */
export function checkEquation(tex: string): boolean | null {
  if (!tex.includes("=") && !tex.includes("\\approx")) return null;
  const sides = tex.split(/=|\\approx/);
  const ops = [...tex.matchAll(/=|\\approx/g)].map((m) => m[0]);
  if (sides.length < 2) return null;
  const vals: number[] = [];
  for (const s of sides) {
    const e = toExpr(s.trim());
    if (!e) return null;
    const v = evalExpr(e);
    if (v === null) return null;
    vals.push(v);
  }
  let ok = true;
  ops.forEach((op, i) => {
    const a = vals[i], b = vals[i + 1];
    const rhs = sides[i + 1].trim();
    let tol: number;
    if (op === "=") {
      tol = 1e-9 * Math.max(1, Math.abs(a), Math.abs(b));
      if (/^-?\d+\.\d+$/.test(rhs)) tol = Math.max(tol, 0.5 * Math.pow(10, -(rhs.split(".")[1].length)) + 1e-12);
    } else tol = 0.01 * Math.max(1, Math.abs(b));
    if (Math.abs(a - b) > tol) ok = false;
  });
  return ok;
}

/** The numeric equations in [text] ($...$ and $$...$$) that do not hold, as written. */
export function findWrongSums(text: string): string[] {
  const out: string[] = [];
  for (const m of text.matchAll(/\$\$([\s\S]+?)\$\$|\$([^\s$](?:[^$\n]*[^\s$])?)\$(?![0-9])/g)) {
    const tex = (m[1] ?? m[2] ?? "").trim();
    if (tex && checkEquation(tex) === false) out.push(tex);
  }
  return out;
}

"""Prototype of study/PlotMath.kt: a small expression evaluator, the ```plot spec, the number checker for $..$ equalities
and the ```flow / cycle / timeline diagram spec with its layout. Mirrors the Kotlin port."""
import math
import re

FUNCS = {
    'sin': math.sin, 'cos': math.cos, 'tan': math.tan, 'asin': math.asin, 'acos': math.acos, 'atan': math.atan,
    'sqrt': math.sqrt, 'ln': math.log, 'log': math.log10, 'exp': math.exp, 'abs': abs, 'floor': math.floor, 'ceil': math.ceil,
}
CONSTS = {'pi': math.pi, 'e': math.e}
NAMES = sorted(list(FUNCS) + list(CONSTS), key=len, reverse=True)


class Bad(Exception):
    pass


def tokens(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c.isspace():
            i += 1
        elif c.isdigit() or (c == '.' and i + 1 < len(s) and s[i + 1].isdigit()):
            j = i
            while j < len(s) and (s[j].isdigit() or s[j] == '.'):
                j += 1
            out.append(('n', float(s[i:j])))
            i = j
        elif c.isalpha():
            for nm in NAMES:
                if s.startswith(nm, i) and not (nm in ('e',) and i + 1 < len(s) and s[i + 1].isalpha() and not any(s.startswith(f, i + 1) for f in NAMES)):
                    out.append(('f' if nm in FUNCS else 'k', nm))
                    i += len(nm)
                    break
            else:
                out.append(('v', c))
                i += 1
        elif c in '+-*/^()':
            out.append((c, c))
            i += 1
        elif c in '−':
            out.append(('-', '-'))
            i += 1
        elif c in '×·':
            out.append(('*', '*'))
            i += 1
        elif c == '÷':
            out.append(('/', '/'))
            i += 1
        elif c == '|':
            out.append(('|', '|'))
            i += 1
        else:
            raise Bad(c)
    return out


class Parser:
    def __init__(self, s):
        self.t = tokens(s)
        self.i = 0
        self.vars = set()

    def peek(self):
        return self.t[self.i][0] if self.i < len(self.t) else None

    def take(self):
        x = self.t[self.i]
        self.i += 1
        return x

    def expr(self):
        a = self.term()
        while self.peek() in ('+', '-'):
            op = self.take()[0]
            b = self.term()
            a = ('+' if op == '+' else '-', a, b)
        return a

    def starts_factor(self):
        p = self.peek()
        return p in ('n', 'v', 'k', 'f', '(')

    def term(self):
        a = self.unary()
        while True:
            p = self.peek()
            if p in ('*', '/'):
                self.take()
                a = (p, a, self.unary())
            elif self.starts_factor():
                a = ('*', a, self.unary())
            else:
                return a

    def unary(self):
        if self.peek() == '-':
            self.take()
            return ('neg', self.unary())
        if self.peek() == '+':
            self.take()
            return self.unary()
        return self.power()

    def power(self):
        a = self.atom()
        if self.peek() == '^':
            self.take()
            return ('^', a, self.unary())
        return a

    def atom(self):
        p = self.peek()
        if p is None:
            raise Bad('end')
        k, v = self.take()
        if k == 'n':
            return ('num', v)
        if k == 'k':
            return ('num', CONSTS[v])
        if k == 'v':
            self.vars.add(v)
            return ('var', v)
        if k == 'f':
            if self.peek() == '(':
                self.take()
                a = self.expr()
                if self.peek() != ')':
                    raise Bad(')')
                self.take()
                return ('fn', v, a)
            return ('fn', v, self.power())
        if k == '(':
            a = self.expr()
            if self.peek() != ')':
                raise Bad(')')
            self.take()
            return a
        if k == '|':
            a = self.expr()
            if self.peek() != '|':
                raise Bad('|')
            self.take()
            return ('fn', 'abs', a)
        raise Bad(k)


def parse_expr(s):
    p = Parser(s)
    n = p.expr()
    if p.i != len(p.t):
        raise Bad('trailing')
    return n, p.vars


def ev(n, env):
    k = n[0]
    if k == 'num':
        return n[1]
    if k == 'var':
        return env[n[1]]
    if k == 'neg':
        return -ev(n[1], env)
    if k == 'fn':
        return FUNCS[n[1]](ev(n[2], env))
    a, b = ev(n[1], env), ev(n[2], env)
    if k == '+':
        return a + b
    if k == '-':
        return a - b
    if k == '*':
        return a * b
    if k == '/':
        return a / b
    if k == '^':
        return math.pow(a, b)
    raise Bad(k)


def safe_ev(n, env):
    try:
        v = ev(n, env)
        return v if isinstance(v, float) or isinstance(v, int) else float('nan')
    except (ZeroDivisionError, ValueError, OverflowError):
        return float('nan')


# ---------------------------------------------------------------- numbers in LaTeX
def latex_to_expr(t):
    t = t.replace('\\left', '').replace('\\right', '').replace('\\,', '').replace('\\!', '').replace('\\ ', ' ')
    for _ in range(8):
        t2 = re.sub(r"\\d?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}", r"((\1)/(\2))", t)
        t2 = re.sub(r"\\sqrt\s*\[([^\]]*)\]\s*\{([^{}]*)\}", r"((\2)^(1/(\1)))", t2)
        t2 = re.sub(r"\\sqrt\s*\{([^{}]*)\}", r"sqrt(\1)", t2)
        t2 = re.sub(r"\^\s*\{([^{}]*)\}", r"^(\1)", t2)
        if t2 == t:
            break
        t = t2
    t = t.replace('\\cdot', '*').replace('\\times', '*').replace('\\div', '/').replace('\\pi', 'pi')
    if re.search(r"\\|%|°|[A-Za-z]{1}", re.sub(r"\b(sqrt|pi)\b", "", t)):
        return None
    return t.replace('{', '(').replace('}', ')')


def decimals(s):
    m = re.search(r"\.(\d+)\s*$", s)
    return len(m.group(1)) if m else 0


def check(tex):
    """True: every = / \\approx in a numbers-only formula holds. False: one does not. None: cannot tell."""
    parts = re.split(r"(=|\\approx)", tex)
    if len(parts) < 3:
        return None
    sides, ops = parts[0::2], parts[1::2]
    vals = []
    for s in sides:
        e = latex_to_expr(s.strip())
        if not e:
            return None
        try:
            n, vs = parse_expr(e)
            if vs:
                return None
            v = safe_ev(n, {})
        except Bad:
            return None
        if v != v or abs(v) == float('inf'):
            return None
        vals.append((v, e))
    ok = True
    for i, op in enumerate(ops):
        a, b = vals[i][0], vals[i + 1][0]
        rhs_src = sides[i + 1].strip()
        if op == '=':
            tol = 1e-9 * max(1.0, abs(a), abs(b))
            if re.fullmatch(r"-?\d+\.\d+", rhs_src):
                tol = max(tol, 0.5 * 10 ** (-decimals(rhs_src)) + 1e-12)
        else:
            tol = 0.01 * max(1.0, abs(b))
        if abs(a - b) > tol:
            ok = False
    return ok


# ---------------------------------------------------------------- plot spec
def nice_step(span, target=5):
    raw = span / target
    p = 10 ** math.floor(math.log10(raw))
    for m in (1, 2, 5, 10):
        if raw <= m * p:
            return m * p
    return 10 * p


class Plot:
    def __init__(self):
        self.curves = []      # (src, node, vars)
        self.x = None
        self.y = None
        self.points = []      # (x, y, label)
        self.params = []      # (name, value, lo, hi)
        self.title = ''


RANGE = r"(-?[\d.]+)\s*(?:\.\.|to|,)\s*(-?[\d.]+)"


def parse_plot(text):
    p = Plot()
    for raw in text.strip().split('\n'):
        line = raw.strip()
        if not line:
            continue
        low = line.lower()
        m = re.match(r"^x\s*:\s*" + RANGE + r"\s*$", low)
        if m:
            p.x = (float(m.group(1)), float(m.group(2)))
            continue
        m = re.match(r"^y\s*:\s*" + RANGE + r"\s*$", low)
        if m:
            p.y = (float(m.group(1)), float(m.group(2)))
            continue
        m = re.match(r"^title\s*:\s*(.+)$", line, re.I)
        if m:
            p.title = m.group(1).strip()
            continue
        m = re.match(r"^points?\s*:\s*(.+)$", line, re.I)
        if m:
            for pt in re.finditer(r"\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)\s*([^;(]*)", m.group(1)):
                p.points.append((float(pt.group(1)), float(pt.group(2)), pt.group(3).strip()))
            continue
        m = re.match(r"^param\s*:\s*([a-z])\s*=\s*(-?[\d.]+)\s*(?:\[\s*" + RANGE + r"\s*\])?\s*$", low)
        if m:
            v = float(m.group(2))
            lo = float(m.group(3)) if m.group(3) else min(-5.0, v - 5)
            hi = float(m.group(4)) if m.group(4) else max(5.0, v + 5)
            if not lo < hi:
                lo, hi = v - 5, v + 5
            v = min(max(v, lo), hi)
            if len(p.params) < 3:
                p.params.append((m.group(1), v, lo, hi))
            continue
        m = re.match(r"^(?:[yf]\d?(?:\(x\))?|g(?:\(x\))?)\s*=\s*(.+)$", line, re.I)
        src = m.group(1) if m else line
        try:
            n, vs = parse_expr(src)
        except Bad:
            return None
        if len(p.curves) < 3:
            p.curves.append((src, n, vs))
    if not p.curves or p.x is None or not (p.x[0] < p.x[1]):
        return None
    names = {n for n, *_ in p.params}
    for _, _, vs in p.curves:
        if not vs <= (names | {'x'}):
            return None
    return p


def sample(p, env_extra, n=240):
    xs = [p.x[0] + (p.x[1] - p.x[0]) * i / (n - 1) for i in range(n)]
    out = []
    for _, node, _ in p.curves:
        ys = []
        for x in xs:
            env = dict(env_extra); env['x'] = x
            ys.append(safe_ev(node, env))
        out.append(ys)
    return xs, out


def auto_y(p, ys_all):
    if p.y:
        return p.y
    vals = [v for ys in ys_all for v in ys if v == v and abs(v) < 1e6]
    vals += [pt[1] for pt in p.points]
    if not vals:
        return (-1.0, 1.0)
    vals.sort()
    lo, hi = vals[int(len(vals) * 0.01)], vals[int(len(vals) * 0.99) - 1 if len(vals) > 3 else -1]
    if hi - lo < 1e-9:
        lo, hi = lo - 1, hi + 1
    pad = (hi - lo) * 0.1
    return (lo - pad, hi + pad)


# ---------------------------------------------------------------- diagrams
class Diagram:
    def __init__(self, kind):
        self.kind = kind
        self.nodes = []
        self.edges = []   # (a, b, label)
        self.items = []   # timeline (when, text)


def parse_diagram(kind, text):
    d = Diagram(kind)
    lines = [l.strip() for l in text.strip().split('\n') if l.strip()]
    if kind == 'timeline':
        for l in lines:
            m = re.match(r"^(.{1,24}?)\s*(?::|\||—|–| - )\s+(.+)$", l)
            d.items.append((m.group(1), m.group(2)) if m else ('', l))
        return d if d.items else None
    def node(label):
        label = label.strip().strip('-•*').strip()
        if label not in d.nodes:
            d.nodes.append(label)
        return d.nodes.index(label)
    for l in lines:
        label = None
        m = re.match(r"^(.*?)\s*:\s*([^>:]+)$", l) if '->' in l and ':' in l.split('->')[-1] else None
        if m:
            l, label = m.group(1), m.group(2).strip()
        parts = [x for x in re.split(r"\s*(?:->|→)\s*", l) if x.strip()]
        if len(parts) == 1 and '->' not in l and '→' not in l:
            node(parts[0])
            continue
        ids = [node(x) for x in parts]
        for a, b in zip(ids, ids[1:]):
            d.edges.append((a, b, label if (a, b) == (ids[-2], ids[-1]) else None))
    if kind == 'cycle' and not d.edges and len(d.nodes) > 1:
        d.edges = [(i, (i + 1) % len(d.nodes), None) for i in range(len(d.nodes))]
    if not d.nodes or len(d.nodes) > 12:
        return None
    return d


def layers(d):
    n = len(d.nodes)
    out = [[] for _ in range(n)]
    for a, b, _ in d.edges:
        out[a].append(b)
    layer = [0] * n
    state = [0] * n
    back = set()
    def dfs(u):
        state[u] = 1
        for v in out[u]:
            if state[v] == 1:
                back.add((u, v))
            elif state[v] == 0:
                dfs(v)
        state[u] = 2
    for u in range(n):
        if state[u] == 0:
            dfs(u)
    changed = True
    for _ in range(n + 1):
        changed = False
        for a, b, _ in d.edges:
            if (a, b) in back:
                continue
            if layer[b] < layer[a] + 1:
                layer[b] = layer[a] + 1
                changed = True
        if not changed:
            break
    rows = {}
    for i, l in enumerate(layer):
        rows.setdefault(l, []).append(i)
    return [rows[k] for k in sorted(rows)], back


def layout_flow(d, avail, size_for, gap_x=14.0, gap_y=34.0):
    rows, back = layers(d)
    rects = {}
    y = 0.0
    for row in rows:
        k = len(row)
        maxw = (avail - gap_x * (k - 1)) / k
        sizes = [size_for(d.nodes[i], maxw) for i in row]
        h = max(s[1] for s in sizes)
        total = sum(s[0] for s in sizes) + gap_x * (k - 1)
        x = (avail - total) / 2
        for i, (w, hh) in zip(row, sizes):
            rects[i] = (x, y + (h - hh) / 2, w, hh)
            x += w + gap_x
        y += h + gap_y
    return rects, y - gap_y, back


def layout_cycle(d, avail, size_for):
    n = len(d.nodes)
    maxw = avail * 0.40
    sizes = [size_for(t, maxw) for t in d.nodes]
    r = max(avail / 2 - max(s[0] for s in sizes) / 2, 40)
    ry = r * 0.9 if n > 2 else r
    cx = avail / 2
    cy = ry + max(s[1] for s in sizes) / 2
    rects = {}
    for i, (w, h) in enumerate(sizes):
        a = -math.pi / 2 + 2 * math.pi * i / n
        rects[i] = (cx + r * math.cos(a) - w / 2, cy + ry * math.sin(a) - h / 2, w, h)
    return rects, cy * 2

"""Reference prototype of the LockIn math engine: LaTeX subset -> AST -> boxes of drawing ops.
Coordinates: x right, y DOWN from the baseline (so "up" is negative). Mirrors the Kotlin port 1:1."""
import re

ORD, OP, BIN, REL, OPEN, CLOSE, PUNCT = range(7)
D, T, S, SS = range(4)
SCALE = [1.0, 1.0, 0.7, 0.5]
AXIS = 0.25
RT = 0.05

GREEK = {
    'alpha': 'α', 'beta': 'β', 'gamma': 'γ', 'delta': 'δ', 'epsilon': 'ϵ', 'varepsilon': 'ε', 'zeta': 'ζ', 'eta': 'η',
    'theta': 'θ', 'vartheta': 'ϑ', 'iota': 'ι', 'kappa': 'κ', 'lambda': 'λ', 'mu': 'μ', 'nu': 'ν', 'xi': 'ξ', 'pi': 'π',
    'varpi': 'ϖ', 'rho': 'ρ', 'varrho': 'ϱ', 'sigma': 'σ', 'varsigma': 'ς', 'tau': 'τ', 'upsilon': 'υ', 'phi': 'ϕ',
    'varphi': 'φ', 'chi': 'χ', 'psi': 'ψ', 'omega': 'ω',
}
GREEK_UP = {'Gamma': 'Γ', 'Delta': 'Δ', 'Theta': 'Θ', 'Lambda': 'Λ', 'Xi': 'Ξ', 'Pi': 'Π', 'Sigma': 'Σ', 'Upsilon': 'Υ',
            'Phi': 'Φ', 'Psi': 'Ψ', 'Omega': 'Ω'}
SYMS = {
    'times': ('×', BIN), 'cdot': ('·', BIN), 'div': ('÷', BIN), 'pm': ('±', BIN), 'mp': ('∓', BIN), 'ast': ('∗', BIN),
    'star': ('⋆', BIN), 'circ': ('∘', BIN), 'oplus': ('⊕', BIN), 'otimes': ('⊗', BIN), 'cup': ('∪', BIN), 'cap': ('∩', BIN),
    'setminus': ('∖', BIN), 'wedge': ('∧', BIN), 'land': ('∧', BIN), 'vee': ('∨', BIN), 'lor': ('∨', BIN), 'bullet': ('•', BIN),
    'le': ('≤', REL), 'leq': ('≤', REL), 'ge': ('≥', REL), 'geq': ('≥', REL), 'ne': ('≠', REL), 'neq': ('≠', REL),
    'approx': ('≈', REL), 'equiv': ('≡', REL), 'sim': ('∼', REL), 'simeq': ('≃', REL), 'cong': ('≅', REL), 'propto': ('∝', REL),
    'in': ('∈', REL), 'notin': ('∉', REL), 'ni': ('∋', REL), 'subset': ('⊂', REL), 'supset': ('⊃', REL),
    'subseteq': ('⊆', REL), 'supseteq': ('⊇', REL), 'll': ('≪', REL), 'gg': ('≫', REL), 'perp': ('⊥', REL),
    'parallel': ('∥', REL), 'mid': ('∣', REL), 'to': ('→', REL), 'rightarrow': ('→', REL), 'leftarrow': ('←', REL),
    'leftrightarrow': ('↔', REL), 'Rightarrow': ('⇒', REL), 'Leftarrow': ('⇐', REL), 'Leftrightarrow': ('⇔', REL),
    'implies': ('⟹', REL), 'iff': ('⟺', REL), 'mapsto': ('↦', REL), 'uparrow': ('↑', REL), 'downarrow': ('↓', REL),
    'infty': ('∞', ORD), 'partial': ('∂', ORD), 'nabla': ('∇', ORD), 'forall': ('∀', ORD), 'exists': ('∃', ORD),
    'emptyset': ('∅', ORD), 'varnothing': ('∅', ORD), 'neg': ('¬', ORD), 'lnot': ('¬', ORD), 'angle': ('∠', ORD),
    'triangle': ('△', ORD), 'degree': ('°', ORD), 'prime': ('′', ORD), 'ell': ('ℓ', ORD), 'hbar': ('ℏ', ORD),
    'therefore': ('∴', REL), 'because': ('∵', REL), 'ldots': ('…', ORD), 'dots': ('…', ORD), 'cdots': ('⋯', ORD),
    'vdots': ('⋮', ORD), 'ddots': ('⋱', ORD), 'checkmark': ('✓', ORD), 'square': ('□', ORD),
    'aleph': ('ℵ', ORD), 'Re': ('ℜ', ORD), 'Im': ('ℑ', ORD),
    '%': ('%', ORD), '$': ('$', ORD), '&': ('&', ORD), '#': ('#', ORD), '_': ('_', ORD), '{': ('{', OPEN), '}': ('}', CLOSE),
    'lbrace': ('{', OPEN), 'rbrace': ('}', CLOSE), 'langle': ('⟨', OPEN), 'rangle': ('⟩', CLOSE), 'lfloor': ('⌊', OPEN),
    'rfloor': ('⌋', CLOSE), 'lceil': ('⌈', OPEN), 'rceil': ('⌉', CLOSE), 'vert': ('|', ORD), 'Vert': ('‖', ORD), '|': ('‖', ORD),
}
SPACES = {',': 0.167, ':': 0.222, ';': 0.278, '!': -0.167, ' ': 0.25, 'quad': 1.0, 'qquad': 2.0, 'enspace': 0.5, 'thinspace': 0.167}
FUNCS = {'sin', 'cos', 'tan', 'cot', 'sec', 'csc', 'arcsin', 'arccos', 'arctan', 'sinh', 'cosh', 'tanh', 'log', 'ln', 'lg',
         'exp', 'det', 'dim', 'ker', 'gcd', 'deg', 'arg', 'mod', 'Pr', 'hom'}
LIMFUNCS = {'lim', 'max', 'min', 'sup', 'inf', 'limsup', 'liminf', 'argmax', 'argmin'}
ACCENTS = {'overline', 'underline', 'hat', 'widehat', 'bar', 'vec', 'tilde', 'widetilde', 'dot', 'ddot', 'overrightarrow',
           'overbrace', 'underbrace', 'boxed', 'cancel'}
BIGOPS = {'sum': '∑', 'prod': '∏', 'int': '∫', 'iint': '∫', 'oint': '∫', 'bigcup': '∪', 'bigcap': '∩'}
BB = {'R': 'ℝ', 'N': 'ℕ', 'Z': 'ℤ', 'Q': 'ℚ', 'C': 'ℂ', 'P': 'ℙ', 'H': 'ℍ', 'E': '𝔼'}
ENVS = {
    'pmatrix': ('(', ')'), 'bmatrix': ('[', ']'), 'Bmatrix': ('{', '}'), 'vmatrix': ('|', '|'), 'Vmatrix': ('‖', '‖'),
    'matrix': ('.', '.'), 'smallmatrix': ('.', '.'), 'cases': ('{', '.'), 'array': ('.', '.'),
    'aligned': ('.', '.'), 'align': ('.', '.'), 'align*': ('.', '.'), 'split': ('.', '.'), 'gather': ('.', '.'),
    'gather*': ('.', '.'), 'alignat': ('.', '.'), 'eqnarray': ('.', '.'),
}
ALIGNED = {'aligned', 'align', 'align*', 'split', 'alignat', 'eqnarray'}


# ---------------------------------------------------------------- tokens
def tokenize(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '\\':
            j = i + 1
            if j < n and src[j].isalpha():
                while j < n and src[j].isalpha():
                    j += 1
                out.append(('cmd', src[i + 1:j]))
                i = j
            elif j < n:
                out.append(('cmd', src[j]))
                i = j + 1
            else:
                i = j
        elif c.isspace():
            out.append(('sp', ' '))
            i += 1
        else:
            out.append(('ch', c))
            i += 1
    return out


class Parser:
    def __init__(self, src):
        self.t = tokenize(src)
        self.i = 0

    def peek(self):
        while self.i < len(self.t) and self.t[self.i][0] == 'sp':
            self.i += 1
        return self.t[self.i] if self.i < len(self.t) else None

    def next(self):
        p = self.peek()
        if p is not None:
            self.i += 1
        return p

    def at(self, kind, v):
        p = self.peek()
        return p is not None and p[0] == kind and p[1] == v

    def is_stop(self):
        p = self.peek()
        if p is None:
            return True
        if p[0] == 'ch' and p[1] in '}&':
            return True
        return p[0] == 'cmd' and p[1] in ('right', 'end', '\\')

    def seq(self):
        items = []
        while not self.is_stop():
            a = self.atom_with_scripts()
            if a is not None:
                items.append(a)
        return ('seq', autosize(items))

    def group_or_atom(self):
        p = self.peek()
        if p is None:
            return ('seq', [])
        if p == ('ch', '{'):
            self.next()
            s = self.seq()
            if self.at('ch', '}'):
                self.next()
            return s
        a = self.atom(True)
        return a if a is not None else ('seq', [])

    def atom_with_scripts(self):
        a = self.atom()
        if a is None:
            return None
        sub = sup = None
        while True:
            p = self.peek()
            if p == ('ch', '^') and sup is None:
                self.next()
                sup = self.group_or_atom()
            elif p == ('ch', '_') and sub is None:
                self.next()
                sub = self.group_or_atom()
            elif p == ('ch', "'") and sup is None:
                self.next()
                sup = ('sym', '′', ORD, 'rm')
            else:
                break
        return ('scripts', a, sub, sup) if (sub or sup) else a

    def raw_group(self):
        """The text inside {...}, spaces kept (for \\text)."""
        if not self.at('ch', '{'):
            p = self.next()
            return p[1] if p else ''
        self.next()
        depth, out = 1, []
        while self.i < len(self.t):
            k, v = self.t[self.i]
            self.i += 1
            if k == 'ch' and v == '{':
                depth += 1
            elif k == 'ch' and v == '}':
                depth -= 1
                if depth == 0:
                    break
            if k == 'cmd':
                out.append(v if not v.isalpha() else '')
            else:
                out.append(v)
        return ''.join(out)

    def delim(self):
        p = self.next()
        if p is None:
            return '.'
        if p[0] == 'ch':
            return p[1]
        n = p[1]
        return {'{': '{', '}': '}', 'lbrace': '{', 'rbrace': '}', 'langle': '⟨', 'rangle': '⟩', 'lfloor': '⌊', 'rfloor': '⌋',
                'lceil': '⌈', 'rceil': '⌉', 'vert': '|', '|': '‖', 'Vert': '‖'}.get(n, '.')

    def atom(self, single=False):
        p = self.next()
        if p is None:
            return None
        k, v = p
        if k == 'ch':
            if v == '{':
                s = self.seq()
                if self.at('ch', '}'):
                    self.next()
                return s
            if single and v.isdigit():
                return ('sym', v, ORD, 'rm')
            if v.isdigit() or (v == '.' and self.peek_digit()):
                j = [v]
                while True:
                    q = self.t[self.i] if self.i < len(self.t) else None
                    if q and q[0] == 'ch' and (q[1].isdigit() or (q[1] == '.' and self.digit_after(self.i))):
                        j.append(q[1])
                        self.i += 1
                    else:
                        break
                return ('sym', ''.join(j), ORD, 'rm')
            if v.isalpha():
                return ('sym', v, ORD, 'it')
            if v in '+':
                return ('sym', '+', BIN, 'rm')
            if v == '-':
                return ('sym', '−', BIN, 'rm')
            if v == '*':
                return ('sym', '∗', BIN, 'rm')
            if v in '=<>':
                return ('sym', v, REL, 'rm')
            if v == '(' or v == '[':
                return ('sym', v, OPEN, 'rm')
            if v == ')' or v == ']':
                return ('sym', v, CLOSE, 'rm')
            if v in ',;':
                return ('sym', v, PUNCT, 'rm')
            if v == '/':
                return ('sym', '/', ORD, 'rm')
            if v == ':':
                return ('sym', ':', REL, 'rm')
            if v == '|':
                return ('sym', '|', ORD, 'rm')
            if v in '^_':
                self.i -= 1
                return ('seq', [])
            if v in '&}':
                return None
            return ('sym', v, ORD, 'rm')
        return self.command(v)

    def peek_digit(self):
        return self.i < len(self.t) and self.t[self.i][0] == 'ch' and self.t[self.i][1].isdigit()

    def digit_after(self, i):
        return i + 1 < len(self.t) and self.t[i + 1][0] == 'ch' and self.t[i + 1][1].isdigit()

    def command(self, n):
        if n in GREEK:
            return ('sym', GREEK[n], ORD, 'it')
        if n in GREEK_UP:
            return ('sym', GREEK_UP[n], ORD, 'rm')
        if n in SPACES:
            return ('space', SPACES[n])
        if n in SYMS:
            s, c = SYMS[n]
            return ('sym', s, c, 'rm')
        if n in FUNCS:
            return ('fn', n, False)
        if n in LIMFUNCS:
            return ('fn', n, True)
        if n in BIGOPS:
            return ('bigop', n)
        if n in ('frac', 'dfrac', 'tfrac', 'cfrac'):
            a = self.group_or_atom()
            b = self.group_or_atom()
            return ('frac', a, b, {'dfrac': D, 'tfrac': T}.get(n, -1), True)
        if n == 'binom':
            a = self.group_or_atom()
            b = self.group_or_atom()
            return ('lr', '(', ')', ('frac', a, b, -1, False))
        if n == 'sqrt':
            idx = None
            if self.at('ch', '['):
                self.next()
                items = []
                while self.peek() is not None and not self.at('ch', ']'):
                    a = self.atom_with_scripts()
                    if a is not None:
                        items.append(a)
                if self.at('ch', ']'):
                    self.next()
                idx = ('seq', items)
            return ('sqrt', self.group_or_atom(), idx)
        if n == 'left':
            l = self.delim()
            body = self.seq()
            r = '.'
            if self.at('cmd', 'right'):
                self.next()
                r = self.delim()
            return ('lr', l, r, body)
        if n in ('big', 'Big', 'bigg', 'Bigg', 'bigl', 'bigr', 'Bigl', 'Bigr', 'biggl', 'biggr', 'Biggl', 'Biggr'):
            d = self.delim()
            return ('sym', d, OPEN if d in '([{⟨⌊⌈' else CLOSE, 'rm') if d != '.' else None
        if n in ('text', 'textrm', 'mbox', 'mathrm', 'operatorname', 'textnormal', 'mathsf', 'mathtt', 'textsf', 'texttt'):
            if n in ('mathrm', 'operatorname', 'mathsf', 'mathtt'):
                if self.at('ch', '{'):
                    return ('text', self.raw_group(), 'rm')
                a = self.atom()
                return a
            return ('text', self.raw_group(), 'tx')
        if n in ('textit', 'emph', 'mathit'):
            return ('text', self.raw_group(), 'it')
        if n in ('textbf', 'mathbf', 'boldsymbol', 'bm', 'bf'):
            return ('font', 'bf', self.group_or_atom())
        if n in ('mathbb', 'Bbb'):
            raw = self.raw_group()
            return ('text', ''.join(BB.get(c, c) for c in raw), 'rm')
        if n in ('mathcal', 'mathscr', 'mathfrak', 'mathbfit'):
            return ('font', 'it', self.group_or_atom())
        if n in ACCENTS:
            return ('acc', n, self.group_or_atom())
        if n == 'begin':
            return self.env(self.raw_group())
        if n in ('displaystyle', 'textstyle', 'scriptstyle', 'scriptscriptstyle'):
            k = {'displaystyle': D, 'textstyle': T, 'scriptstyle': S, 'scriptscriptstyle': SS}[n]
            return ('style', k, self.seq())
        if n in ('color', 'textcolor'):
            self.raw_group()
            return self.group_or_atom() if n == 'textcolor' else None
        if n in ('limits', 'nolimits', 'nonumber', 'notag', 'label', 'tag', 'hline', 'middle', 'protect', 'relax', 'mathstrut',
                 'strut', 'vphantom', 'hphantom', 'phantom'):
            if n in ('label', 'tag'):
                self.raw_group()
            return None
        if n == 'not':
            return ('sym', '̸', ORD, 'rm')
        return ('text', n, 'rm')

    def env(self, name):
        if name not in ENVS:
            self.seq()
            if self.at('cmd', 'end'):
                self.next()
                self.raw_group()
            return ('text', name, 'rm')
        spec = None
        if name in ('array', 'alignat'):
            spec = self.raw_group()
        rows, row, cell = [], [], []
        while True:
            cell = self.seq()
            p = self.peek()
            if p == ('ch', '&'):
                self.next()
                row.append(cell)
                continue
            row.append(cell)
            if p == ('cmd', '\\'):
                self.next()
                rows.append(row)
                row = []
                continue
            if p == ('cmd', 'end'):
                self.next()
                self.raw_group()
            elif p == ('cmd', 'right') or p is None or p == ('ch', '}'):
                self.next()
            rows.append(row)
            break
        rows = [r for r in rows if not (len(r) == 1 and not r[0][1])] or rows
        l, r = ENVS[name]
        return ('mat', rows, name, spec, l, r)


def is_tall(n):
    k = n[0]
    if k in ('frac', 'sqrt', 'bigop', 'mat'):
        return True
    if k == 'scripts':
        return is_tall(n[1])
    if k == 'seq':
        return any(is_tall(c) for c in n[1])
    if k == 'font' or k == 'style':
        return is_tall(n[2])
    if k == 'lr':
        return is_tall(n[3])
    return False


def _delim_of(n):
    """('(' or '[', is_open) for a plain bracket symbol, also when it carries scripts (the ')' in (…)^2)."""
    if n[0] == 'scripts':
        return _delim_of(n[1])
    if n[0] == 'sym' and n[1] in '([)]' and n[2] in (OPEN, CLOSE):
        return n[1]
    return None


def autosize(items):
    """A plain ( ) or [ ] that wraps a fraction, root, sum or matrix grows like \\left( \\right), so it reads like the textbook."""
    out, i = [], 0
    while i < len(items):
        d = _delim_of(items[i])
        if d in ('(', '[') and items[i][0] == 'sym':
            want, depth, j = ')' if d == '(' else ']', 0, -1
            for k in range(i, len(items)):
                dk = _delim_of(items[k])
                if dk == d and items[k][0] == 'sym':
                    depth += 1
                elif dk == want:
                    depth -= 1
                    if depth == 0:
                        j = k
                        break
            if j > 0:
                inner = autosize(items[i + 1:j])
                if any(is_tall(c) for c in inner):
                    lr = ('lr', d, want, ('seq', inner))
                    close = items[j]
                    out.append(('scripts', lr, close[2], close[3]) if close[0] == 'scripts' else lr)
                else:
                    out.append(items[i]); out += inner; out.append(items[j])
                i = j + 1
                continue
        out.append(items[i])
        i += 1
    return out


def parse(src):
    p = Parser(src)
    rows, cur = [], []
    while True:
        cur = cur + p.seq()[1]
        tok = p.peek()
        if tok is None:
            break
        p.next()  # a row break, or a stray closer which is dropped
        if tok == ('cmd', '\\'):
            rows.append(('seq', cur))
            cur = []
    if cur or not rows:
        rows.append(('seq', cur))
    if len(rows) == 1:
        return rows[0]
    return ('mat', [[r] for r in rows if r[1]], 'gather', None, '.', '.')


# ---------------------------------------------------------------- metrics
class Metrics:
    """width(text, font, size) is provided by the renderer; heights are estimated per glyph class."""

    def __init__(self, width_fn):
        self.width = width_fn

    @staticmethod
    def hd(text, font):
        if not text:
            return 0.0, 0.0
        asc, desc = 0.0, 0.0
        for c in text:
            a, d = Metrics.glyph(c, font)
            asc, desc = max(asc, a), max(desc, d)
        return asc, desc

    @staticmethod
    def glyph(c, font):
        if c.isdigit():
            return 0.68, 0.0
        if c.isupper():
            return 0.70, 0.0
        if c in 'bdfhklt':
            return 0.70, 0.2 if (c == 'f' and font == 'it') else 0.0
        if c in 'ij':
            return 0.68, 0.22 if c == 'j' else 0.0
        if c in 'gpqy':
            return 0.46, 0.22
        if 'a' <= c <= 'z':
            return 0.46, 0.0
        if c in 'βγζηξρφψχμϕ':
            return (0.70 if c in 'βζξϕψ' else 0.46), 0.22
        if c in 'δθλϑ':
            return 0.70, 0.0
        if c in 'αεϵικνοπστυωϖςϱ':
            return 0.46, 0.0
        if 'Α' <= c <= 'Ω':
            return 0.70, 0.0
        if c in '+−=±∓×÷<>≤≥≠≈≡∼≃≅∝→←↔⇒⇐⇔⟹⟺↦·∗∘⊕⊗∈∉∋⊂⊃⊆⊇≪≫∥∣∧∨∩∪•⋅':
            return 0.58, 0.08
        if c in '()[]{}|‖⟨⟩⌊⌋⌈⌉':
            return 0.75, 0.25
        if c in '.':
            return 0.1, 0.0
        if c in ',;':
            return 0.1, 0.18
        if c in ':!?':
            return 0.70, 0.0
        if c == '/':
            return 0.75, 0.25
        if c in '∞∂∇∀∃∅¬∠△°′':
            return 0.70, 0.0
        if c in '…⋯':
            return 0.15, 0.0
        if c == ' ':
            return 0.0, 0.0
        return 0.70, 0.1


class Box:
    __slots__ = ('w', 'asc', 'desc', 'ops')

    def __init__(self, w=0.0, asc=0.0, desc=0.0, ops=None):
        self.w, self.asc, self.desc, self.ops = w, asc, desc, ops or []


def shifted(ops, dx, dy):
    out = []
    for o in ops:
        k = o[0]
        if k == 't':
            out.append(('t', o[1] + dx, o[2] + dy) + o[3:])
        elif k == 'r':
            out.append(('r', o[1] + dx, o[2] + dy) + o[3:])
        elif k == 'l':
            out.append(('l', o[1] + dx, o[2] + dy, o[3] + dx, o[4] + dy, o[5]))
        elif k == 'q':
            out.append(('q', o[1] + dx, o[2] + dy, o[3] + dx, o[4] + dy, o[5] + dx, o[6] + dy, o[7]))
        elif k == 'd':
            out.append(('d', o[1] + dx, o[2] + dy, o[3]))
    return out


def place(parent_ops, b, x, baseline_dy):
    parent_ops.extend(shifted(b.ops, x, baseline_dy))


# ---------------------------------------------------------------- layout
class Layout:
    def __init__(self, metrics, base):
        self.m = metrics
        self.base = base

    def fs(self, st):
        return self.base * SCALE[st]

    def glyphs(self, text, font, st):
        sz = self.fs(st)
        a, d = Metrics.hd(text, font)
        return Box(self.m.width(text, font, sz), a * sz, d * sz, [('t', 0.0, 0.0, text, sz, font)])

    # --- spacing
    @staticmethod
    def cls(n):
        if n[0] == 'sym':
            return n[2]
        if n[0] == 'fn':
            return OP
        if n[0] == 'bigop':
            return OP
        if n[0] == 'scripts':
            return Layout.cls(n[1])
        return ORD

    def gap(self, a, b, st):
        if st >= S:
            return 0.0
        if a == OP and b in (ORD, OP):
            return 0.167
        if a == ORD and b == OP:
            return 0.167
        if a == BIN or b == BIN:
            return 0.222
        if a == REL or b == REL:
            return 0.278
        if a == PUNCT:
            return 0.167
        return 0.0

    def classes_for(self, items):
        classes = [self.cls(n) for n in items]
        for i, c in enumerate(classes):
            if c == BIN:
                prev = classes[i - 1] if i > 0 else None
                nxt = classes[i + 1] if i + 1 < len(classes) else None
                if prev is None or prev in (BIN, REL, OPEN, PUNCT, OP) or nxt is None or nxt in (REL, CLOSE, PUNCT):
                    classes[i] = ORD
        return classes

    def lay_seq(self, items, st):
        classes = self.classes_for(items)
        em = self.fs(st)
        x = 0.0
        asc = desc = 0.0
        ops = []
        for i, n in enumerate(items):
            if i > 0:
                x += self.gap(classes[i - 1], classes[i], st) * em
            b = self.lay(n, st)
            place(ops, b, x, 0.0)
            x += b.w
            asc, desc = max(asc, b.asc), max(desc, b.desc)
        return Box(x, asc, desc, ops)

    def lay_pieces(self, items, st):
        """Inline math that may wrap: pieces end after a binary operator or relation (the space after it stays inside)."""
        classes = self.classes_for(items)
        em = self.fs(st)
        out, ops, x, asc, desc = [], [], 0.0, 0.0, 0.0
        for i, n in enumerate(items):
            if i > 0 and not (classes[i - 1] in (BIN, REL)):
                x += self.gap(classes[i - 1], classes[i], st) * em
            b = self.lay(n, st)
            place(ops, b, x, 0.0)
            x += b.w
            asc, desc = max(asc, b.asc), max(desc, b.desc)
            if classes[i] in (BIN, REL) and i + 1 < len(items):
                x += self.gap(classes[i], classes[i + 1], st) * em
                out.append(Box(x, asc, desc, ops))
                ops, x, asc, desc = [], 0.0, 0.0, 0.0
        out.append(Box(x, asc, desc, ops))
        return out

    def lay(self, n, st):
        k = n[0]
        em = self.fs(st)
        if k == 'seq':
            return self.lay_seq(n[1], st)
        if k == 'sym':
            return self.glyphs(n[1], n[3], st)
        if k == 'text':
            f = 'tx' if n[2] == 'tx' else n[2]
            return self.glyphs(n[1], f, st)
        if k == 'font':
            return self.lay(self.with_font(n[2], n[1]), st)
        if k == 'space':
            return Box(n[1] * em, 0, 0)
        if k == 'fn':
            return self.glyphs(n[1], 'rm', st)
        if k == 'style':
            return self.lay(n[2], n[1])
        if k == 'frac':
            return self.lay_frac(n, st)
        if k == 'scripts':
            return self.lay_scripts(n, st)
        if k == 'sqrt':
            return self.lay_sqrt(n, st)
        if k == 'lr':
            body = self.lay(n[3], st)
            return self.delimit(n[1], n[2], body, st)
        if k == 'acc':
            return self.lay_acc(n, st)
        if k == 'bigop':
            return self.lay_bigop(n[1], st, False)
        if k == 'mat':
            return self.lay_mat(n, st)
        return Box()

    def with_font(self, n, f):
        k = n[0]
        if k == 'sym':
            return ('sym', n[1], n[2], 'bf')
        if k == 'seq':
            return ('seq', [self.with_font(c, f) for c in n[1]])
        if k == 'scripts':
            return ('scripts', self.with_font(n[1], f), n[2] and self.with_font(n[2], f), n[3] and self.with_font(n[3], f))
        if k == 'text':
            return ('text', n[1], 'bf')
        if k == 'fn':
            return ('text', n[1], 'bf')
        return n

    # --- fractions
    def lay_frac(self, n, st):
        em = self.fs(st)
        _, a, b, force, bar = n
        inner = {D: T, T: S, S: SS, SS: SS}[st] if force < 0 else {D: T, T: S}[force]
        num, den = self.lay(a, inner), self.lay(b, inner)
        rt = max(RT * em, 1.0)
        disp = st == D
        u0, v0 = (0.677, 0.686) if disp else (0.394, 0.345)
        clr = (3 * rt) if disp else rt
        if not bar:
            clr = rt * 1.5
        axis = AXIS * em
        u = max(u0 * em, axis + rt / 2 + clr + num.desc)
        v = max(v0 * em, den.asc - axis + rt / 2 + clr)
        pad = 0.1 * em
        w = max(num.w, den.w) + 2 * pad
        ops = []
        place(ops, num, (w - num.w) / 2, -u)
        place(ops, den, (w - den.w) / 2, v)
        if bar:
            ops.append(('r', 0.0, -(axis + rt / 2), w, rt))
        return Box(w, u + num.asc, v + den.desc, ops)

    # --- scripts
    def lay_scripts(self, n, st):
        _, base, sub, sup = n
        if base[0] == 'bigop':
            return self.lay_bigop_scripts(base[1], sub, sup, st)
        if base[0] == 'fn' and base[2] and st == D:
            return self.lay_limits(self.lay(base, st), sub, sup, st, 0.0)
        if base[0] == 'acc' and base[1] in ('overbrace', 'underbrace'):
            return self.lay_limits(self.lay(base, st), sub if base[1] == 'underbrace' else None,
                                   sup if base[1] == 'overbrace' else None, st, 0.0)
        return self.attach(self.lay(base, st), base[0] in ('sym', 'text', 'fn'), sub, sup, st, 0.0)

    def attach(self, B, simple, sub, sup, st, sub_dx):
        em = self.fs(st)
        sst = {D: S, T: S, S: SS, SS: SS}[st]
        sem = self.fs(sst)
        S_ = self.lay(sub, sst) if sub else None
        U_ = self.lay(sup, sst) if sup else None
        xh = 0.45 * em
        rt = max(RT * em, 1.0)
        u = 0.0 if simple else B.asc - 0.386 * sem
        v = 0.0 if simple else B.desc + 0.05 * sem
        if U_ is not None and S_ is None:
            u = max(u, 0.38 * em, U_.desc + xh / 4)
        elif S_ is not None and U_ is None:
            v = max(v, 0.15 * em, S_.asc - 0.8 * xh)
        elif S_ is not None and U_ is not None:
            u = max(u, 0.38 * em, U_.desc + xh / 4)
            v = max(v, 0.247 * em)
            gap = (u - U_.desc) - (S_.asc - v)
            if gap < 4 * rt:
                v += 4 * rt - gap
                psi = 0.8 * xh - (u - U_.desc)
                if psi > 0:
                    u += psi
                    v -= psi
        ops = list(B.ops)
        sp = 0.03 * em
        w = B.w
        asc, desc = B.asc, B.desc
        if U_ is not None:
            place(ops, U_, B.w + 0.0, -u)
            asc = max(asc, u + U_.asc)
        if S_ is not None:
            place(ops, S_, B.w + sub_dx, v)
            desc = max(desc, v + S_.desc)
        w = B.w + max(U_.w if U_ else 0.0, (S_.w + sub_dx) if S_ else 0.0) + sp
        return Box(w, asc, desc, ops)

    def lay_limits(self, B, sub, sup, st, gapmul):
        em = self.fs(st)
        sst = {D: S, T: S, S: SS, SS: SS}[st]
        S_ = self.lay(sub, sst) if sub else None
        U_ = self.lay(sup, sst) if sup else None
        w = max(B.w, S_.w if S_ else 0.0, U_.w if U_ else 0.0)
        ops = []
        g = 0.12 * em
        asc, desc = B.asc, B.desc
        place(ops, B, (w - B.w) / 2, 0.0)
        if U_ is not None:
            dy = -(B.asc + g + U_.desc)
            place(ops, U_, (w - U_.w) / 2, dy)
            asc = B.asc + g + U_.desc + U_.asc
        if S_ is not None:
            dy = B.desc + g + S_.asc
            place(ops, S_, (w - S_.w) / 2, dy)
            desc = B.desc + g + S_.asc + S_.desc
        return Box(w, asc, desc, ops)

    # --- big operators
    def big_glyph(self, name, st):
        em = self.fs(st)
        disp = st == D
        axis = AXIS * em
        ops = []
        if name in ('sum', 'prod'):
            H = (1.45 if disp else 1.0) * em
            w = (0.95 if name == 'sum' else 0.9) * em * (1.0 if disp else 0.85)
            sw = max(0.06 * em, 1.2)
            top, bot = -(axis + H / 2), -(axis - H / 2)
            if name == 'sum':
                ops += [('l', w, top, 0.0, top, sw), ('l', 0.0, top, 0.55 * w, -axis, sw), ('l', 0.55 * w, -axis, 0.0, bot, sw),
                        ('l', 0.0, bot, w, bot, sw), ('l', w, top, w, top + 0.14 * em, sw), ('l', w, bot, w, bot - 0.14 * em, sw)]
            else:
                ops += [('l', -0.02 * w, top, 1.02 * w, top, sw), ('l', 0.12 * w, top, 0.12 * w, bot, sw),
                        ('l', 0.88 * w, top, 0.88 * w, bot, sw)]
            return Box(w + 0.05 * em, -top, bot, ops)
        H = (2.0 if disp else 1.2) * em
        w = (0.32 if disp else 0.26) * H
        sw = max(0.055 * em, 1.2)
        top, bot = -(axis + H / 2), -(axis - H / 2)
        h = bot - top
        ops += [('q', 0.62 * w, top + 0.16 * h, 0.66 * w, top, 0.92 * w, top + 0.05 * h, sw),
                ('l', 0.62 * w, top + 0.16 * h, 0.38 * w, bot - 0.16 * h, sw),
                ('q', 0.38 * w, bot - 0.16 * h, 0.34 * w, bot, 0.08 * w, bot - 0.05 * h, sw)]
        return Box(w, -top, bot, ops)

    def lay_bigop(self, name, st, _):
        if name in ('sum', 'prod', 'int', 'iint', 'oint'):
            return self.big_glyph(name if name != 'iint' and name != 'oint' else 'int', st)
        return self.glyphs(BIGOPS[name], 'rm', st)

    def lay_bigop_scripts(self, name, sub, sup, st):
        B = self.lay_bigop(name, st, False)
        if name in ('sum', 'prod', 'bigcup', 'bigcap') and st == D:
            return self.lay_limits(B, sub, sup, st, 0.0)
        if name in ('int', 'iint', 'oint'):
            return self.attach(B, False, sub, sup, st, -0.25 * self.fs(st) if st == D else -0.12 * self.fs(st))
        return self.attach(B, False, sub, sup, st, 0.0)

    # --- sqrt
    def lay_sqrt(self, n, st):
        em = self.fs(st)
        body = self.lay(n[1], st)
        rt = max(RT * em, 1.0)
        gap = (0.18 if st == D else 0.13) * em
        H = body.asc + gap + rt
        Dp = body.desc
        sw = 0.62 * em
        sw_stroke = max(rt * 1.1, 1.2)
        idx = self.lay(n[2], S) if n[2] else None
        x0 = max(0.0, idx.w - 0.4 * sw) if idx else 0.0
        top, bot = -(H - rt / 2), Dp
        h = bot - top
        mid = bot - 0.42 * h
        ops = []
        pts = [(x0, mid + 0.05 * h), (x0 + 0.16 * sw, mid - 0.03 * h), (x0 + 0.45 * sw, bot), (x0 + sw, top)]
        for a, b in zip(pts, pts[1:]):
            ops.append(('l', a[0], a[1], b[0], b[1], sw_stroke))
        pad = 0.06 * em
        ops.append(('r', x0 + sw, -H, body.w + pad, rt))
        place(ops, body, x0 + sw, 0.0)
        if idx:
            place(ops, idx, 0.0, -(0.45 * (H + Dp) - Dp))
        return Box(x0 + sw + body.w + pad, H, Dp, ops)

    # --- delimiters
    def delimit(self, l, r, body, st):
        em = self.fs(st)
        axis = AXIS * em
        hd = max(body.asc - axis, body.desc + axis)
        H = max(2 * hd * 0.901, 2 * hd - 0.5 * em)
        ops = []
        w = 0.0
        tall = H > 1.25 * em
        pad = 0.1 * em if tall else 0.0
        if l != '.':
            b = self.draw_delim(l, H, st, True)
            place(ops, b, w, 0.0)
            w += b.w + pad
        place(ops, body, w, 0.0)
        w += body.w
        if r != '.':
            w += pad
            b = self.draw_delim(r, H, st, False)
            place(ops, b, w, 0.0)
            w += b.w
        asc = max(body.asc, axis + H / 2)
        desc = max(body.desc, H / 2 - axis)
        return Box(w, asc, desc, ops)

    def draw_delim(self, d, H, st, left):
        em = self.fs(st)
        axis = AXIS * em
        small = H <= 1.25 * em
        if small and d not in ('‖',):
            g = self.glyphs(d, 'rm', st)
            if d in '()[]{}|⟨⟩':
                return Box(g.w + 0.03 * em, max(g.asc, 0.75 * em), max(g.desc, 0.25 * em), shifted(g.ops, 0.015 * em, 0))
            return g
        top, bot = -(axis + H / 2), -(axis - H / 2)
        mid = (top + bot) / 2
        sw = max(0.06 * em, 1.2)
        ops = []
        if d in '()':
            w = 0.30 * em
            if d == '(':
                ops.append(('q', w, top, -w, mid, w, bot, sw))
                ops = [('q', w + 0.04 * em, top, 0.04 * em - w, mid, w + 0.04 * em, bot, sw)] if False else ops
            else:
                ops.append(('q', 0.0, top, 2 * w, mid, 0.0, bot, sw))
            w += 0.06 * em
            ops = shifted(ops, 0.03 * em, 0)
        elif d in '[]':
            w = 0.34 * em
            x1, x2 = (0.10 * em, w - 0.04 * em) if d == '[' else (0.04 * em, w - 0.10 * em)
            xs = x1 if d == '[' else x2
            xe = x2 if d == '[' else x1
            ops = [('l', xe, top, xs, top, sw), ('l', xs, top, xs, bot, sw), ('l', xs, bot, xe, bot, sw)]
        elif d in '{}':
            w = 0.45 * em
            a, c, t = (w - 0.06 * em, w / 2, 0.05 * em) if d == '{' else (0.06 * em, w / 2, w - 0.05 * em)
            if d == '{':
                x_far, x_mid, x_tip = w - 0.05 * em, w / 2 + 0.02 * em, 0.04 * em
            else:
                x_far, x_mid, x_tip = 0.05 * em, w / 2 - 0.02 * em, w - 0.04 * em
            r = 0.14 * em
            ops = [('q', x_far, top, x_mid, top, x_mid, top + r, sw), ('l', x_mid, top + r, x_mid, mid - r, sw),
                   ('q', x_mid, mid - r, x_mid, mid, x_tip, mid, sw), ('q', x_tip, mid, x_mid, mid, x_mid, mid + r, sw),
                   ('l', x_mid, mid + r, x_mid, bot - r, sw), ('q', x_mid, bot - r, x_mid, bot, x_far, bot, sw)]
        elif d in '|∣':
            w = 0.2 * em
            ops = [('l', w / 2, top, w / 2, bot, sw)]
        elif d == '‖':
            w = 0.32 * em
            ops = [('l', 0.09 * em, top, 0.09 * em, bot, sw), ('l', w - 0.09 * em, top, w - 0.09 * em, bot, sw)]
        elif d in '⟨⟩':
            w = 0.34 * em
            tip, far = (0.06 * em, w - 0.06 * em) if d == '⟨' else (w - 0.06 * em, 0.06 * em)
            ops = [('l', far, top, tip, mid, sw), ('l', tip, mid, far, bot, sw)]
        elif d in '⌊⌋⌈⌉':
            w = 0.32 * em
            xl, xr = 0.08 * em, w - 0.08 * em
            x_v, x_o = (xl, xr) if d in '⌊⌈' else (xr, xl)
            if d in '⌊⌋':
                ops = [('l', x_v, top, x_v, bot, sw), ('l', x_v, bot, x_o, bot, sw)]
            else:
                ops = [('l', x_v, bot, x_v, top, sw), ('l', x_v, top, x_o, top, sw)]
        else:
            return self.glyphs(d, 'rm', st)
        return Box(w, -top, bot, ops)

    # --- accents
    def lay_acc(self, n, st):
        em = self.fs(st)
        _, kind, body_n = n
        B = self.lay(body_n, st)
        rt = max(RT * em, 1.0)
        ops = list(B.ops)
        asc, desc = B.asc, B.desc
        w = max(B.w, 0.3 * em)
        cx = B.w / 2
        sw = max(0.05 * em, 1.0)
        if kind == 'overline':
            y = -(B.asc + 0.12 * em)
            ops.append(('r', 0.0, y - rt, B.w, rt))
            asc = B.asc + 0.12 * em + rt
        elif kind == 'underline':
            y = B.desc + 0.12 * em
            ops.append(('r', 0.0, y, B.w, rt))
            desc = B.desc + 0.12 * em + rt
        elif kind in ('hat', 'widehat'):
            y = -(max(B.asc, 0.46 * em) + 0.06 * em)
            ops += [('l', cx - 0.2 * em, y, cx, y - 0.17 * em, sw), ('l', cx, y - 0.17 * em, cx + 0.2 * em, y, sw)]
            asc = -y + 0.17 * em + sw
        elif kind in ('bar',):
            y = -(max(B.asc, 0.46 * em) + 0.10 * em)
            ops.append(('r', cx - 0.2 * em, y - rt, 0.4 * em, rt))
            asc = -y + rt
        elif kind in ('vec', 'overrightarrow'):
            y = -(max(B.asc, 0.46 * em) + 0.12 * em)
            x1, x2 = (cx - 0.22 * em, cx + 0.22 * em) if kind == 'vec' else (0.0, B.w)
            ops += [('l', x1, y, x2, y, sw), ('l', x2 - 0.08 * em, y - 0.06 * em, x2, y, sw), ('l', x2 - 0.08 * em, y + 0.06 * em, x2, y, sw)]
            asc = -y + 0.08 * em
        elif kind in ('tilde', 'widetilde'):
            y = -(max(B.asc, 0.46 * em) + 0.1 * em)
            ops += [('q', cx - 0.22 * em, y, cx - 0.11 * em, y - 0.16 * em, cx, y - 0.04 * em, sw),
                    ('q', cx, y - 0.04 * em, cx + 0.11 * em, y + 0.08 * em, cx + 0.22 * em, y - 0.08 * em, sw)]
            asc = -y + 0.2 * em
        elif kind in ('dot', 'ddot'):
            y = -(max(B.asc, 0.46 * em) + 0.12 * em)
            r = max(0.035 * em, 0.8)
            xs = [cx] if kind == 'dot' else [cx - 0.09 * em, cx + 0.09 * em]
            for x in xs:
                ops.append(('d', x, y, r))
            asc = -y + r
        elif kind == 'overbrace' or kind == 'underbrace':
            up = kind == 'overbrace'
            y = -(B.asc + 0.1 * em) if up else (B.desc + 0.1 * em)
            dy = -0.12 * em if up else 0.12 * em
            m = B.w / 2
            ops += [('q', 0.0, y + dy, 0.0, y, 0.15 * em, y, sw), ('l', 0.15 * em, y, m - 0.15 * em, y, sw),
                    ('q', m - 0.15 * em, y, m, y, m, y + (-dy), sw), ('q', m, y + (-dy), m, y, m + 0.15 * em, y, sw),
                    ('l', m + 0.15 * em, y, B.w - 0.15 * em, y, sw), ('q', B.w - 0.15 * em, y, B.w, y, B.w, y + dy, sw)]
            if up:
                asc = -y + 0.14 * em
            else:
                desc = y + 0.14 * em
        elif kind == 'boxed':
            p = 0.22 * em
            x0, x1, y0, y1 = -p, B.w + p, -(B.asc + p), B.desc + p
            ops = shifted(ops, p, 0.0)
            ops += [('l', 0.0, y0, x1 + p, y0, sw), ('l', 0.0, y1, x1 + p, y1, sw), ('l', 0.0, y0, 0.0, y1, sw), ('l', x1 + p, y0, x1 + p, y1, sw)]
            return Box(B.w + 2 * p, B.asc + p, B.desc + p, ops)
        elif kind == 'cancel':
            ops.append(('l', 0.0, B.desc, B.w, -B.asc, sw))
        return Box(B.w, asc, desc, ops)

    # --- matrices & aligned blocks
    def lay_mat(self, n, st):
        _, rows, name, spec, l, r = n
        cell_st = D if name in ALIGNED or name in ('gather', 'gather*') else (S if name == 'smallmatrix' else T)
        em = self.fs(cell_st)
        if name in ALIGNED:
            rows = [[c if j % 2 == 0 else ('seq', [('seq', [])] + list(c[1])) for j, c in enumerate(row)] for row in rows]
        grid = [[self.lay(c, cell_st) for c in row] for row in rows]
        ncol = max(len(r_) for r_ in grid) if grid else 0
        colw = [0.0] * ncol
        for r_ in grid:
            for j, b in enumerate(r_):
                colw[j] = max(colw[j], b.w)
        if name in ALIGNED:
            align = ['r' if j % 2 == 0 else 'l' for j in range(ncol)]
            gaps = [0.0 if j % 2 == 0 else 2.0 * em for j in range(ncol)]
        elif name == 'cases':
            align = ['l'] * ncol
            gaps = [1.0 * em] * ncol
        elif name in ('gather', 'gather*'):
            align = ['c'] * ncol
            gaps = [1.0 * em] * ncol
        else:
            align = [c for c in (spec or '') if c in 'lcr'] or ['c'] * ncol
            align = (align + ['c'] * ncol)[:ncol]
            gaps = [1.0 * em] * ncol
        rowgap = (0.45 if name in ALIGNED or name == 'gather' else 0.3) * em
        strut_a, strut_d = 0.7 * em, 0.25 * em
        heights = []
        for r_ in grid:
            a = max([strut_a] + [b.asc for b in r_])
            d = max([strut_d] + [b.desc for b in r_])
            heights.append((a, d))
        total_h = sum(a + d for a, d in heights) + rowgap * max(0, len(grid) - 1)
        axis = AXIS * em
        top = -(total_h / 2 + axis)
        ops = []
        y = top
        for r_, (a, d) in zip(grid, heights):
            x = 0.0
            for j, b in enumerate(r_):
                cw = colw[j]
                dx = {'l': 0.0, 'r': cw - b.w, 'c': (cw - b.w) / 2}[align[j]]
                place(ops, b, x + dx, y + a)
                x += cw + (gaps[j] if j < ncol - 1 else 0.0)
            y += a + d + rowgap
        w = sum(colw) + sum(gaps[:max(0, ncol - 1)])
        box = Box(w, total_h / 2 + axis, total_h / 2 - axis, ops)
        if l != '.' or r != '.':
            return self.delimit(l, r, box, cell_st)
        return box


def layout(src, display, base, metrics):
    try:
        ast = parse(src)
        return Layout(metrics, base).lay(ast, D if display else T), ast
    except RecursionError:
        raise

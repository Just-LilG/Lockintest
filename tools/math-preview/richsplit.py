"""Prototype of the text side: split an AI answer into paragraphs, bullets, display maths, inline maths, bold/italic/code,
and rescue plain-text maths ("x^2", "sqrt(…)", "(b/a)") that the model wrote without LaTeX. Regexes are java.util.regex-safe."""
import re

GREEK_WORDS = ['alpha', 'beta', 'gamma', 'delta', 'theta', 'lambda', 'mu', 'sigma', 'omega', 'phi', 'pi', 'tau', 'rho', 'epsilon']

# ------------------------------------------------------------ delimiters
def normalize(t):
    t = re.sub(r'\\\[(.+?)\\\]', lambda m: '$$' + m.group(1) + '$$', t, flags=re.S)
    t = re.sub(r'\\\((.+?)\\\)', lambda m: '$' + m.group(1) + '$', t, flags=re.S)
    return t

def find_inline_close(s, i):
    """s[i] is an opening $. Pandoc-style: the opener is followed by a non-space, the closer is preceded by a non-space
    and not followed by a digit (so "$5 and $6" stays text)."""
    n = len(s)
    if i + 1 >= n or s[i + 1].isspace() or s[i + 1] == '$':
        return -1
    j = i + 1
    while j < n:
        c = s[j]
        if c == '\\' and j + 1 < n:
            j += 2
            continue
        if c == '\n':
            return -1
        if c == '$':
            if s[j - 1].isspace():
                return -1
            if j + 1 < n and s[j + 1].isdigit():
                return -1
            return j
        j += 1
    return -1

# ------------------------------------------------------------ plain-text maths
def balanced_end(s, i):
    """s[i] == '(' -> index after its matching ')', or -1."""
    d = 0
    for j in range(i, len(s)):
        if s[j] == '(':
            d += 1
        elif s[j] == ')':
            d -= 1
            if d == 0:
                return j + 1
    return -1

def balanced_start(s, i):
    """s[i] == ')' -> index of its matching '(', or -1."""
    d = 0
    for j in range(i, -1, -1):
        if s[j] == ')':
            d += 1
        elif s[j] == '(':
            d -= 1
            if d == 0:
                return j
    return -1

POW = re.compile(r"(?:\d+(?:\.\d+)?[A-Za-z]?|[A-Za-z])\^(?:\{[^{}]*\}|\([^()]*\)|-?\d+(?:\.\d+)?|-?[A-Za-z])(?![A-Za-z0-9])|\)\^(?:\{[^{}]*\}|-?\d+|-?[A-Za-z])(?![A-Za-z0-9])")
SUB = re.compile(r"(?<![A-Za-z0-9_])[A-Za-z]_(?:\{[^{}]*\}|\d+|[A-Za-z])(?![A-Za-z0-9_])")
SQRT = re.compile(r"sqrt\(")
OPS = '+-−±∓=*/<>≤≥≠≈×·'
GW = '(?:' + '|'.join(GREEK_WORDS) + ')'
TERM = r"(?:\d+(?:\.\d+)?[A-Za-z]?|" + GW + r"(?![A-Za-z])|[A-Za-z](?![A-Za-z])|[A-Za-z]\^[^\s]|\([^()]*\)[A-Za-z0-9]*)"
R_EXT = re.compile(r"^\s*[+\-−±∓=*/<>≤≥≠≈×·]\s*" + TERM + r"(?:\^(?:\{[^{}]*\}|-?\d+|-?[A-Za-z]))?")
L_EXT = re.compile(r"(?<![A-Za-z0-9])" + TERM + r"(?:\^(?:\{[^{}]*\}|-?\d+|-?[A-Za-z]))?\s*[+\-−±∓=*/<>≤≥≠≈×·]\s*$")
L_JUX = re.compile(r"(?<![A-Za-z0-9])(?:\d+(?:\.\d+)?\s?)?" + GW + r"\s?$")

def anchors(s):
    spans = []
    for m in POW.finditer(s):
        spans.append([m.start(), m.end()])
    for m in SUB.finditer(s):
        spans.append([m.start(), m.end()])
    for m in SQRT.finditer(s):
        e = balanced_end(s, m.end() - 1)
        if e > 0:
            spans.append([m.start(), e])
    spans.sort()
    return spans

def grow(s, a, b):
    for _ in range(6):
        a0, b0 = a, b
        # balance parentheses
        depth, unmatched_open = 0, []
        for j in range(a, b):
            if s[j] == '(':
                unmatched_open.append(j)
            elif s[j] == ')':
                if unmatched_open:
                    unmatched_open.pop()
                else:
                    k = balanced_start(s, j)
                    if k >= 0 and k < a:
                        a = k
                        # a leading function/coefficient glued to the paren, e.g. a( or f(
                        m = re.search(r"(?:\d+(?:\.\d+)?)?[A-Za-z]?$", s[:a])
                        if m and m.group(0) and not (a - len(m.group(0)) > 0 and s[a - len(m.group(0)) - 1].isalpha()):
                            a -= len(m.group(0))
        if unmatched_open:
            e = balanced_end(s, unmatched_open[0])
            if e > 0:
                b = max(b, e)
        # the span sits inside a group that continues past it:  (-b ± sqrt(…)) / (2a)
        k = b
        while k < len(s) and s[k] == ' ':
            k += 1
        if k < len(s) and s[k] == ')':
            o = balanced_start(s, k)
            if 0 <= o < a:
                a, b = o, k + 1
                m = re.search(r"(?:\d+(?:\.\d+)?)?[A-Za-z]?$", s[:a])
                if m and m.group(0) and not (a - len(m.group(0)) > 0 and s[a - len(m.group(0)) - 1].isalpha()):
                    a -= len(m.group(0))
        m = R_EXT.match(s[b:])
        if m:
            b += m.end()
        m = L_JUX.search(s[:a])
        if m and m.group(0):
            a = m.start()
        m = L_EXT.search(s[:a])
        if m:
            a = m.start()
        if (a, b) == (a0, b0):
            break
    return a, b

def to_tex(span):
    t = span
    # sqrt( … )
    while True:
        m = SQRT.search(t)
        if not m:
            break
        e = balanced_end(t, m.end() - 1)
        if e < 0:
            break
        t = t[:m.start()] + '\\sqrt{' + t[m.end():e - 1] + '}' + t[e:]
    t = re.sub(r"\^\(([^()]*)\)", r"^{\1}", t)
    t = re.sub(r"_\(([^()]*)\)", r"_{\1}", t)
    t = re.sub(r"\(([^()/]+?)\s*/\s*([^()/]+?)\)", lambda m: '\\frac{' + m.group(1).strip() + '}{' + m.group(2).strip() + '}', t)
    t = re.sub(r"\(([^()]*)\)\s*/\s*\(([^()]*)\)", r"\\frac{\1}{\2}", t)
    t = re.sub(r"(?<![A-Za-z0-9^_{}\\])(\d+|[A-Za-z])\s*/\s*(\d+|[A-Za-z])(?![A-Za-z0-9(])", r"\\frac{\1}{\2}", t)
    t = t.replace('<=', '\\le ').replace('>=', '\\ge ').replace('!=', '\\ne ').replace('->', '\\to ').replace('=>', '\\Rightarrow ')
    t = t.replace('*', '\\cdot ')
    t = re.sub(r"\b(" + '|'.join(GREEK_WORDS) + r")\b", r"\\\1", t)
    t = t.replace('²', '^2').replace('³', '^3')
    return t

def upgrade_spans(s):
    """Plain text -> list of (is_math, text)."""
    if '^' not in s and '_' not in s and 'sqrt(' not in s:
        return [(False, s)]
    spans = anchors(s)
    out, pos, i = [], 0, 0
    while i < len(spans):
        a, b = spans[i]
        a, b = grow(s, a, b)
        a = max(a, pos)
        j = i + 1
        while j < len(spans) and spans[j][0] <= b:   # overlapping anchors share one span
            b = max(b, spans[j][1])
            a2, b2 = grow(s, spans[j][0], spans[j][1])
            b = max(b, b2)
            j += 1
        if a > pos:
            out.append((False, s[pos:a]))
        out.append((True, to_tex(s[a:b].strip())))
        lead = s[a:b]
        pos = b
        i = j
    if pos < len(s):
        out.append((False, s[pos:]))
    return out

def looks_math(t):
    """A string that is an equation rather than a sentence."""
    t = t.strip()
    if not t or len(t) > 200:
        return False
    if '$' in t or '\\' in t:
        return True
    words = re.findall(r"[A-Za-z]{4,}", t)
    ok = [w for w in words if w.lower() in GREEK_WORDS or w.lower() in ('sqrt', 'sin', 'cos', 'tan', 'log')]
    if len(words) - len(ok) > 0:
        return False
    return bool(re.search(r"[=+\-*/^_<>]|sqrt|\d", t)) and bool(re.search(r"[A-Za-z0-9]", t)) and (' ' not in t or bool(re.search(r"[=+\-*/^<>]", t)))

def force_tex(t):
    t = t.strip()
    if '\\' in t or '$' in t:
        return t.strip('$')
    t = to_tex(t)
    return t

# ------------------------------------------------------------ blocks and inline
BULLET = re.compile(r"^\s*(?:[-*•]|\d{1,2}[.)])\s+")

def split_inline(line):
    """-> list of ('t', text, bold, italic, code) | ('m', tex)"""
    out = []
    buf = []
    bold = ital = False
    def flush():
        if buf:
            s = ''.join(buf); buf.clear()
            for is_math, piece in upgrade_spans(s):
                out.append(('m', piece) if is_math else ('t', piece, bold, ital, False))
    i, n = 0, len(line)
    while i < n:
        c = line[i]
        if c == '\\' and i + 1 < n and line[i + 1] in '$*`\\':
            buf.append(line[i + 1]); i += 2; continue
        if c == '$':
            j = find_inline_close(line, i)
            if j > 0:
                flush(); out.append(('m', line[i + 1:j])); i = j + 1; continue
        if c == '`':
            j = line.find('`', i + 1)
            if j > 0:
                flush(); out.append(('t', line[i + 1:j], False, False, True)); i = j + 1; continue
        if line.startswith('**', i):
            flush(); bold = not bold; i += 2; continue
        if c == '*' and (i + 1 < n and not line[i + 1].isspace() or ital) and not (i > 0 and line[i - 1].isalnum() and not ital):
            if ital or line.find('*', i + 1) > 0:
                flush(); ital = not ital; i += 1; continue
        buf.append(c); i += 1
    flush()
    return out

def split_blocks(text):
    text = normalize(text.replace('\r', ''))
    blocks = []
    pos = 0
    for m in re.finditer(r"\$\$(.+?)\$\$", text, flags=re.S):
        blocks += split_lines(text[pos:m.start()])
        blocks.append(('display', m.group(1).strip()))
        pos = m.end()
    blocks += split_lines(text[pos:])
    return blocks

def split_lines(text):
    out, gap = [], False
    for raw in text.split('\n'):
        if not raw.strip():
            gap = True
            continue
        m = BULLET.match(raw)
        marker = None
        body = raw
        if m:
            tok = m.group(0).strip()
            marker = '•' if tok in ('-', '*', '•') else tok
            body = raw[m.end():]
        else:
            body = raw.strip()
        out.append(('para', split_inline(body), marker, gap and bool(out)))
        gap = False
    return out

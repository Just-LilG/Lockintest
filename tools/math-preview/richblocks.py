"""Prototype of the block parser added to RichSplit.kt: headings, rules, tables, callouts/quotes, fences (code, plot, flow,
cycle, timeline) and coloured / highlighted inline text. Reuses richsplit for the old pieces."""
import re
import richsplit as rs

COLORS = ('lime', 'flare', 'sky', 'amber', 'mist')
CALLOUT = re.compile(r"^(tip|warning|remember|example|note|careful|key idea)\s*[:：]\s*(.*)$", re.I | re.S)
SEP = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")
HEAD = re.compile(r"^\s{0,3}(#{1,3})\s+(.+?)\s*#*\s*$")
RULE = re.compile(r"^\s*(?:-{3,}|\*{3,}|_{3,})\s*$")
COLOR_OPEN = re.compile(r"^\{(" + '|'.join(COLORS) + r")\|")


def inline(line):
    """-> ('t', text, bold, italic, code, color, mark) | ('m', tex, color)"""
    out, buf = [], []
    bold = ital = mark = False
    colors = []
    def flush():
        if buf:
            s = ''.join(buf); buf.clear()
            col = colors[-1] if colors else None
            for is_math, piece in rs.upgrade_spans(s):
                out.append(('m', piece, col) if is_math else ('t', piece, bold, ital, False, col, mark))
    i, n = 0, len(line)
    while i < n:
        c = line[i]
        if c == '\\' and i + 1 < n and line[i + 1] in '$*`\\{}=':
            buf.append(line[i + 1]); i += 2; continue
        if c == '$':
            j = rs.find_inline_close(line, i)
            if j > 0:
                flush(); out.append(('m', line[i + 1:j], colors[-1] if colors else None)); i = j + 1; continue
        if c == '`':
            j = line.find('`', i + 1)
            if j > 0:
                flush(); out.append(('t', line[i + 1:j], False, False, True, None, False)); i = j + 1; continue
        if c == '{':
            m = COLOR_OPEN.match(line[i:])
            if m:
                flush(); colors.append(m.group(1)); i += m.end(); continue
        if c == '}' and colors:
            flush(); colors.pop(); i += 1; continue
        if line.startswith('**', i):
            flush(); bold = not bold; i += 2; continue
        if line.startswith('==', i) and (mark or (i + 2 < n and not line[i + 2].isspace())) and (mark or line.find('==', i + 2) > 0):
            flush(); mark = not mark; i += 2; continue
        if c == '*':
            nxt = i + 1 < n and not line[i + 1].isspace()
            prev = i > 0 and line[i - 1].isalnum()
            if (nxt or ital) and not (prev and not ital) and (ital or line.find('*', i + 1) > 0):
                flush(); ital = not ital; i += 1; continue
        buf.append(c); i += 1
    flush()
    return out


def split_cells(row):
    row = row.strip()
    if row.startswith('|'): row = row[1:]
    if row.endswith('|') and not row.endswith('\\|'): row = row[:-1]
    cells, cur, in_math, i = [], [], False, 0
    while i < len(row):
        c = row[i]
        if c == '\\' and i + 1 < len(row):
            cur.append(row[i + 1] if row[i + 1] == '|' else c + row[i + 1]); i += 2; continue
        if c == '$': in_math = not in_math
        if c == '|' and not in_math:
            cells.append(''.join(cur).strip()); cur = []
        else:
            cur.append(c)
        i += 1
    cells.append(''.join(cur).strip())
    return cells


def aligns(sep):
    out = []
    for c in split_cells(sep):
        l, r = c.startswith(':'), c.endswith(':')
        out.append('c' if l and r else 'r' if r else 'l')
    return out


def parse(text):
    lines = text.replace('\r', '').split('\n')
    out, i, gap = [], 0, False
    def add(b):
        nonlocal gap
        out.append(b + (gap and bool(out),) if b[0] == 'para' else b); gap = False
    while i < len(lines):
        raw = lines[i]
        if not raw.strip():
            gap = True; i += 1; continue
        s = raw.strip()
        if s.startswith('```'):
            lang = s[3:].strip().lower().split()[0] if s[3:].strip() else ''
            j = i + 1; body = []
            while j < len(lines) and not lines[j].strip().startswith('```'):
                body.append(lines[j]); j += 1
            out.append(('fence', lang, '\n'.join(body))); i = j + 1; gap = False; continue
        m = HEAD.match(raw)
        if m:
            out.append(('head', len(m.group(1)), inline(m.group(2)))); i += 1; gap = False; continue
        if RULE.match(raw):
            out.append(('rule',)); i += 1; gap = False; continue
        if s.startswith('|') and i + 1 < len(lines) and SEP.match(lines[i + 1]) and '|' in lines[i + 1]:
            head = split_cells(s); al = aligns(lines[i + 1]); j = i + 2; rows = []
            while j < len(lines) and lines[j].strip().startswith('|'):
                rows.append(split_cells(lines[j])); j += 1
            n = len(head)
            norm = lambda r: (r + [''] * n)[:n]
            out.append(('table', [inline(c) for c in head], [[inline(c) for c in norm(r)] for r in rows], (al + ['l'] * n)[:n]))
            i = j; gap = False; continue
        if s.startswith('>'):
            j = i; body = []
            while j < len(lines) and lines[j].strip().startswith('>'):
                body.append(re.sub(r"^\s*>\s?", '', lines[j])); j += 1
            inner = '\n'.join(body)
            m = CALLOUT.match(inner.strip())
            if m:
                out.append(('callout', m.group(1).lower(), parse(m.group(2))))
            else:
                out.append(('quote', parse(inner)))
            i = j; gap = False; continue
        m = rs.BULLET.match(raw)
        marker, body = None, raw.strip()
        if m:
            tok = m.group(0).strip(); marker = '•' if tok in ('-', '*', '•') else tok; body = raw[m.end():]
        add(('para', inline(body), marker))
        i += 1
    return out

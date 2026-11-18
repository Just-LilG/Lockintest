import random, time
import mathtex as m
from render import METRICS, sheet

# 1) pieces widths add up to the whole line
for src in [r'x^2+2x+1=(x+1)^2', r'a=b+c-d\le e', r'\frac{a}{b}+\sqrt{x}=y']:
    ast = m.parse(src); L = m.Layout(METRICS, 20)
    whole = L.lay(ast, m.T); pcs = L.lay_pieces(ast[1], m.T)
    print(src, round(whole.w,2), round(sum(p.w for p in pcs),2), len(pcs))
    assert abs(whole.w - sum(p.w for p in pcs)) < 1e-6

# 2) fuzz: random mutations must never raise or hang
atoms = [r'\frac', r'\sqrt', r'\left(', r'\right)', r'\left[', r'\right.', '{', '}', '^', '_', '&', r'\\', r'\begin{pmatrix}',
         r'\end{pmatrix}', r'\begin{cases}', r'\end{cases}', r'\begin{aligned}', r'\end{aligned}', r'\sum', r'\int', 'x', '2', '1.5',
         '+', '-', '=', r'\alpha', r'\text{hi there}', r'\hat', r'\vec', r'\overline', r'\boxed', r"'", r'\unknown', r'\mathbb{R}',
         r'\sqrt[3]', r'\lim', r'\underbrace', r'\binom', ' ', '$', r'\%', r'\begin{array}{cc}', r'\end{array}', r'\\\\']
random.seed(7)
t0 = time.time(); n = 0
for _ in range(20000):
    src = ''.join(random.choice(atoms) for _ in range(random.randint(1, 18)))
    try:
        b, _ = m.layout(src, random.random() < .5, 20, METRICS); n += 1
        assert b.w == b.w and b.asc == b.asc and b.desc == b.desc  # no NaN
    except Exception as e:
        print('FAIL', repr(src), type(e).__name__, e); raise
print('fuzz ok', n, round(time.time()-t0, 1), 's')
# 3) deep nesting
deep = '{' * 400 + 'x' + '}' * 400
try:
    m.layout(deep, False, 20, METRICS); print('deep ok')
except RecursionError:
    print('deep -> RecursionError (Kotlin must cap depth)')

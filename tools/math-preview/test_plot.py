import math
import plotmath as pm

def val(s, **env):
    n, vs = pm.parse_expr(s); return pm.safe_ev(n, env)

# evaluator
cases = [("2+3*4",14),("(2+3)*4",20),("2^3^2",512),("-2^2",-4),("2x+1",None),("3(4+1)",15),("sqrt(16)+abs(-3)",7),
         ("sin(pi/2)",1),("2pi",2*math.pi),("1/4",0.25),("10-2-3",5),("2^-1",0.5),("e^0",1),("ln(e)",1),("x^2-4x+3",None)]
for s, want in cases:
    if want is None: 
        print(s, val(s, x=2)); continue
    got = val(s); assert abs(got-want) < 1e-9, (s, got, want)
assert abs(val("ax^2+bx+c", a=1,b=-4,c=3,x=2) - (-1)) < 1e-9
assert abs(val("2x(x+1)", x=3) - 24) < 1e-9
assert abs(val("sin x", x=0)) < 1e-9
print("eval ok")

# number checker
T = lambda t: pm.check(t)
assert T(r"2^3 + 4 = 12") is True
assert T(r"2^3 + 4 = 13") is False
assert T(r"\frac{1}{3} = 0.333") is True
assert T(r"\frac{1}{3} = 0.34") is False
assert T(r"\frac{3}{4} + \frac{1}{4} = 1") is True
assert T(r"\sqrt{16} = 4") is True
assert T(r"\pi \approx 3.14") is True
assert T(r"\sqrt{2} \approx 1.5") is False
assert T(r"x^2 = 9") is None
assert T(r"2 \times 3 = 6 = 12 \div 2") is True
assert T(r"a = b") is None
assert T(r"5 + 5") is None
assert T(r"\left(1+2\right)^{2} = 9") is True
assert T(r"10\% = 0.1") is None
print("check ok")

# plot spec
src = """y = x^2 - 4x + 3
x: -1..5
points: (2,-1) vertex; (1,0) root; (3,0) root
title: A parabola"""
p = pm.parse_plot(src); assert p and p.x == (-1,5) and len(p.points)==3 and p.title
q = pm.parse_plot("y = a x^2 + b x + c\nx: -3 to 5\nparam: a = 1 [-3..3]\nparam: b = -4 [-8..8]\nparam: c = 3 [-5..5]")
assert q and len(q.params)==3
assert pm.parse_plot("y = x^2") is None          # no x range
assert pm.parse_plot("y = z^2\nx: 0..1") is None  # unknown variable
assert pm.parse_plot("y = (x^\nx: 0..1") is None
z = pm.parse_plot("y = a x\nx: 0..1\nparam: a = 2 [3..3]"); assert z.params[0][2] < z.params[0][3]
print("plot spec ok")

# diagrams
d = pm.parse_diagram('flow', "Sunlight -> Leaf -> Glucose\nLeaf -> Oxygen : released")
assert d and len(d.nodes)==4 and len(d.edges)==3, (d.nodes, d.edges)
assert d.edges[2][2] == 'released'
c = pm.parse_diagram('cycle', "Evaporation\nCondensation\nPrecipitation\nCollection")
assert len(c.edges)==4 and c.edges[3]==(3,0,None)
c2 = pm.parse_diagram('cycle', "A -> B -> C -> A"); assert len(c2.nodes)==3 and len(c2.edges)==3
t = pm.parse_diagram('timeline', "1905: Einstein publishes relativity\n1915 | General relativity"); assert len(t.items)==2 and t.items[0][0]=='1905'
rows, back = pm.layers(c2); assert len(rows)==3 and back == {(2,0)}
print("diagram ok")

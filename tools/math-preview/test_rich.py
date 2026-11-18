import richsplit as r
def show(s):
    print(repr(s)); 
    for b in r.split_blocks(s): print('   ', b)

show("You factor out 'a' to make the coefficient of the x^2 term inside the brackets equal to 1.")
show("Solve x^2 + 5x + 6 = 0 using the formula x = (-b ± sqrt(b^2 - 4ac)) / (2a).")
show("The expression a(x^2 + (b/a)x) + c is the starting point.")
show("It costs $5 and $6 each. Use $x^2+1$ here, and $$\\frac{a}{b}$$ below.")
show("**Key idea:** use $a(x^2 + \\frac{b}{a}x) + c$.\n\n- first: v_0 = 3 m/s\n- second: area = pi r^2\n1. step one\n2) step two")
show("It is a/b and and/or w/o spaces, snake_case_name, 2^10 = 1024, e^x, x^(n+1), (x+1)^2")
show("\\[ E = mc^2 \\] and \\(v = u + at\\)")
for t in ["a(x^2 + (b/a)x) + c", "v = u + at", "Hello world", "x^2 + 5x + 6 = 0", "the quadratic formula", "2pi r", "sqrt(x^2+y^2)"]:
    print(repr(t), r.looks_math(t), '->', r.force_tex(t))

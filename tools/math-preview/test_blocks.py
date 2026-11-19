import richblocks as rb
P = rb.parse
t = P("## What it means\nA **bold** and {lime|green $a$} word, ==marked== here.")
assert t[0][:2] == ('head', 2)
sp = t[1][1]
assert any(s[0]=='t' and s[2] for s in sp)                         # bold
assert ('m', 'a', 'lime') in sp
assert any(s[0]=='t' and s[6] and s[1]=='marked' for s in sp)       # highlight
assert any(s[0]=='t' and s[5]=='lime' and 'green' in s[1] for s in sp)
tb = P("| Term | Meaning |\n|:--|--:|\n| $x^2$ | a square |\n| $|x|$ | absolute value |\nafter")
assert tb[0][0]=='table' and tb[0][3]==['l','r'] and len(tb[0][2])==2
assert tb[0][2][1][0][0] == ('m','|x|',None)                         # pipe inside maths is not a column break
assert tb[1][0]=='para'
short = P("| a | b | c |\n|---|---|---|\n| 1 |")
assert len(short[0][2][0])==3
c = P("> Tip: Factor out $a$ first.\n> It keeps $x^2$ simple.\n\n> just a quote")
assert c[0][0]=='callout' and c[0][1]=='tip' and len(c[0][2])==2
assert c[1][0]=='quote'
f = P("before\n```plot\ny = x^2\nx: -1..1\n```\nafter\n```\nprint(1)\n```\n---\n- item")
assert [b[0] for b in f] == ['para','fence','para','fence','rule','para'], [b[0] for b in f]
assert f[1][1]=='plot' and 'x: -1..1' in f[1][2] and f[3][1]=='' and f[3][2]=='print(1)'
assert P("a | b")[0][0]=='para'                # not a table without a separator row
assert P("2 --- 3")[0][0]=='para'
assert P("**not a rule**")[0][0]=='para'
u = P("an unclosed {lime|colour and ==mark")[0][1]
assert u                                        # never throws, never loops
print("blocks ok")

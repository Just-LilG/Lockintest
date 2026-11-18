import sys
from PIL import Image, ImageDraw, ImageFont
import mathtex as m

F = '/usr/share/fonts/truetype/dejavu/'
FILES = {'rm': 'DejaVuSerif.ttf', 'it': 'DejaVuSerif-Italic.ttf' if False else 'DejaVuSerifCondensed-Italic.ttf',
         'bf': 'DejaVuSerif-Bold.ttf', 'tx': 'DejaVuSans.ttf'}
_cache = {}
def font(kind, size):
    key = (kind, round(size, 1))
    if key not in _cache:
        _cache[key] = ImageFont.truetype(F + FILES.get(kind, 'DejaVuSerif.ttf'), max(1, int(round(size))))
    return _cache[key]

def width_fn(text, kind, size):
    return font(kind, size).getlength(text)

METRICS = m.Metrics(width_fn)

def quad(p0, c, p1, n=16):
    return [((1-t)**2*p0[0]+2*(1-t)*t*c[0]+t*t*p1[0], (1-t)**2*p0[1]+2*(1-t)*t*c[1]+t*t*p1[1]) for t in [i/n for i in range(n+1)]]

def draw_box(d, b, ox, oy, color):
    for o in b.ops:
        k = o[0]
        if k == 't':
            d.text((ox+o[1], oy+o[2]), o[3], font=font(o[5], o[4]), fill=color, anchor='ls')
        elif k == 'r':
            d.rectangle([ox+o[1], oy+o[2], ox+o[1]+o[3], oy+o[2]+max(o[4],1)], fill=color)
        elif k == 'l':
            d.line([ox+o[1], oy+o[2], ox+o[3], oy+o[4]], fill=color, width=max(1,int(round(o[5]))))
        elif k == 'q':
            pts = quad((ox+o[1], oy+o[2]), (ox+o[3], oy+o[4]), (ox+o[5], oy+o[6]))
            d.line(pts, fill=color, width=max(1,int(round(o[7]))), joint='curve')
        elif k == 'd':
            r = o[3]; d.ellipse([ox+o[1]-r, oy+o[2]-r, ox+o[1]+r, oy+o[2]+r], fill=color)

def sheet(items, base=24, scale=3, path='sheet.png', width=900, bg=(20,20,22), fg=(240,240,240)):
    rows = []
    for src, disp in items:
        b, ast = m.layout(src, disp, base*scale, METRICS)
        rows.append((src, disp, b))
    H = sum(int(b.asc + b.desc + 30*scale) for _, _, b in rows) + 20*scale
    W = width*scale
    img = Image.new('RGB', (W, H), bg)
    d = ImageDraw.Draw(img)
    y = 10*scale
    for src, disp, b in rows:
        y += 12*scale
        x = (W - b.w)/2 if disp else 20*scale
        draw_box(d, b, x, y + b.asc, fg)
        y += b.asc + b.desc + 18*scale
    img = img.resize((width, H//scale), Image.LANCZOS)
    img.save(path)
    return rows

if __name__ == '__main__':
    items = [
        (r'a\left(x^2+\frac{b}{a}x\right)+c', True),
        (r'a\left[\left(x+\frac{b}{2a}\right)^2-\left(\frac{b}{2a}\right)^2\right]+c', True),
        (r'x=\frac{-b\pm\sqrt{b^2-4ac}}{2a}', True),
        (r'ax^2+bx+c=a\left(x+\frac{b}{2a}\right)^2+c-\frac{b^2}{4a}', True),
        (r'\sum_{i=1}^{n} i = \frac{n(n+1)}{2}', True),
        (r'\int_0^1 x^2\,dx=\frac13', True),
        (r'\begin{pmatrix}1&2\\3&4\end{pmatrix}\begin{pmatrix}x\\y\end{pmatrix}=\begin{pmatrix}5\\6\end{pmatrix}', True),
        (r'f(x)=\begin{cases}x^2 & x\ge 0\\ -x & x<0\end{cases}', True),
        (r'v=u+at,\quad s=ut+\tfrac12 at^2,\quad v^2=u^2+2as', True),
        (r'\lim_{x\to 0}\frac{\sin x}{x}=1', True),
        (r'\vec{F}=m\vec{a},\ \hat{x},\ \bar{y},\ \overline{AB}', True),
        (r'E=mc^2', False),
        (r'\sqrt[3]{x^2+y^2}\ \alpha\beta\gamma\ \theta_1', True),
        (r'\begin{aligned}y&=x^2+2x+1\\&=(x+1)^2\end{aligned}', True),
    ]
    sheet(items)

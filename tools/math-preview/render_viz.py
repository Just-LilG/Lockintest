import math
from PIL import Image, ImageDraw, ImageFont
import plotmath as pm

SS = 3
BG=(22,22,24); FG=(238,238,238); GRID=(52,52,56); AX=(150,150,155); LIME=(190,242,100); FLARE=(255,107,90); SKY=(111,183,255)
F = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
def font(sz): return ImageFont.truetype(F, int(sz*SS))

def plot_img(p, env, W=360, H=250, path='plot.png'):
    img = Image.new('RGB', (W*SS, H*SS), BG); d = ImageDraw.Draw(img)
    L, R, T, B = 38, 12, 26 if p.title else 12, 24
    xs, ys_all = pm.sample(p, env)
    y0, y1 = pm.auto_y(p, ys_all)
    def X(x): return (L + (x-p.x[0])/(p.x[1]-p.x[0])*(W-L-R))*SS
    def Y(y): return (T + (1-(y-y0)/(y1-y0))*(H-T-B))*SS
    sx = pm.nice_step(p.x[1]-p.x[0]); sy = pm.nice_step(y1-y0)
    v = math.ceil(p.x[0]/sx)*sx
    while v <= p.x[1]+1e-9:
        d.line([X(v), T*SS, X(v), (H-B)*SS], fill=GRID, width=SS)
        d.text((X(v), (H-B+4)*SS), f"{v:g}", font=font(10), fill=AX, anchor='mt'); v += sx
    v = math.ceil(y0/sy)*sy
    while v <= y1+1e-9:
        d.line([L*SS, Y(v), (W-R)*SS, Y(v)], fill=GRID, width=SS)
        d.text(((L-5)*SS, Y(v)), f"{v:g}", font=font(10), fill=AX, anchor='rm'); v += sy
    if y0 < 0 < y1: d.line([L*SS, Y(0), (W-R)*SS, Y(0)], fill=AX, width=2*SS)
    if p.x[0] < 0 < p.x[1]: d.line([X(0), T*SS, X(0), (H-B)*SS], fill=AX, width=2*SS)
    cols = [LIME, SKY, FLARE]
    for ys, c in zip(ys_all, cols):
        seg = []
        for x, y in zip(xs, ys):
            if y == y and y0-(y1-y0) < y < y1+(y1-y0):
                seg.append((X(x), min(max(Y(y), (T-30)*SS), (H-B+30)*SS)))
            else:
                if len(seg) > 1: d.line(seg, fill=c, width=int(2.4*SS), joint='curve')
                seg = []
        if len(seg) > 1: d.line(seg, fill=c, width=int(2.4*SS), joint='curve')
    for x, y, lab in p.points:
        r = 4*SS; d.ellipse([X(x)-r, Y(y)-r, X(x)+r, Y(y)+r], fill=FG)
        if lab: d.text((X(x)+7*SS, Y(y)-7*SS), lab, font=font(11), fill=FG, anchor='lb')
    if p.title: d.text((W/2*SS, 4*SS), p.title, font=font(12), fill=FG, anchor='mt')
    img.resize((W, H), Image.LANCZOS).save(path)

def diagram_img(dg, W=360, path='diagram.png'):
    def size_for(label, maxw):
        f = font(12)
        words = label.split(); lines=[]; cur=''
        for w in words:
            t = (cur+' '+w).strip()
            if d0.textlength(t, font=f)/SS + 20 <= maxw or not cur: cur = t
            else: lines.append(cur); cur = w
        lines.append(cur)
        wmax = max(d0.textlength(l, font=f)/SS for l in lines)
        return (min(maxw, wmax+20), 16*len(lines)+14), lines
    d0 = ImageDraw.Draw(Image.new('RGB',(10,10)))
    sf = lambda lab, mw: size_for(lab, mw)[0]
    if dg.kind == 'cycle': rects, H = pm.layout_cycle(dg, W, sf)
    else: rects, H, back = pm.layout_flow(dg, W, sf)
    H = H + 24
    img = Image.new('RGB', (W*SS, int(H)*SS), BG); d = ImageDraw.Draw(img)
    def edge(a, b, lab):
        ra, rb = rects[a], rects[b]
        ca = (ra[0]+ra[2]/2, ra[1]+ra[3]/2); cb = (rb[0]+rb[2]/2, rb[1]+rb[3]/2)
        def clip(r, c, o):
            dx, dy = o[0]-c[0], o[1]-c[1]
            t = min((r[2]/2)/abs(dx) if dx else 9e9, (r[3]/2)/abs(dy) if dy else 9e9)
            return (c[0]+dx*t, c[1]+dy*t)
        p0, p1 = clip(ra, ca, cb), clip(rb, cb, ca)
        d.line([p0[0]*SS, p0[1]*SS, p1[0]*SS, p1[1]*SS], fill=AX, width=2*SS)
        ang = math.atan2(p1[1]-p0[1], p1[0]-p0[0]); h = 8
        for s in (-0.45, 0.45):
            d.line([p1[0]*SS, p1[1]*SS, (p1[0]-h*math.cos(ang+s))*SS, (p1[1]-h*math.sin(ang+s))*SS], fill=AX, width=2*SS)
        if lab: d.text(((p0[0]+p1[0])/2*SS+6*SS, (p0[1]+p1[1])/2*SS), lab, font=font(10), fill=LIME, anchor='lm')
    for a, b, lab in dg.edges: edge(a, b, lab)
    for i, (x, y, w, h) in rects.items():
        d.rounded_rectangle([x*SS, y*SS, (x+w)*SS, (y+h)*SS], radius=10*SS, fill=(40,40,44), outline=LIME, width=SS)
        lines = size_for(dg.nodes[i], w)[1]
        for k, l in enumerate(lines):
            d.text(((x+w/2)*SS, (y+7+16*k+8)*SS), l, font=font(12), fill=FG, anchor='mm')
    img.resize((W, int(H)), Image.LANCZOS).save(path)

if __name__ == '__main__':
    p = pm.parse_plot("y = x^2 - 4x + 3\ny2 = x - 1\nx: -1..5\npoints: (2,-1) vertex; (1,0) root; (3,0) root\ntitle: y = x² − 4x + 3")
    plot_img(p, {}, path='plot.png')
    diagram_img(pm.parse_diagram('flow', "Sunlight -> Leaf -> Glucose\nLeaf -> Oxygen : released\nLeaf -> Water vapour"), path='flow.png')
    diagram_img(pm.parse_diagram('cycle', "Evaporation\nCondensation\nPrecipitation\nCollection"), path='cycle.png')
    ims = [Image.open(f) for f in ('plot.png','flow.png','cycle.png')]
    Wt = max(i.width for i in ims); Ht = sum(i.height for i in ims)+40
    sheet = Image.new('RGB', (Wt, Ht), BG); y=0
    for i in ims: sheet.paste(i, (0,y)); y += i.height+20
    sheet.save('viz_preview.png')

import sys, math, rig, shapes
from rig import Life, Spec
from PIL import Image, ImageDraw
YOU=Spec(135,1,12,520,0,1,31.0); THEM=Spec(1115,-1,13,708,0,1,28.8)
MT=Image.open('mountain.png').convert('RGBA')
ARRIVE=0.42
def smooth(x):
    v=min(1,max(0,x)); return v*v*(3-2*v)
def jump_of(n):
    if n<0.34: return 0.0
    if n<0.42: return -smooth((n-0.34)/0.08)
    if n<0.78: return -math.cos(2*math.pi*(n-0.42)/0.36)
    if n<0.92: return -1+smooth((n-0.78)/0.14)
    return 0.0
def nudge_state(n):
    after=min(1,max(0,(n-ARRIVE)/(1-ARRIVE))) if n>ARRIVE else 0.0
    hop=0.0
    if n>ARRIVE:
        k=min(1,max(0,(n-ARRIVE)/0.32)); hop=math.sin(k*math.pi)*(1-0.35*k)
    youWave=math.sin(n/ARRIVE*math.pi)*0.8 if 0<n<ARRIVE else 0.0
    themWave=math.sin(after*math.pi) if after>0 else 0.0
    return youWave,themWave,jump_of(n)
def frame(n, poses, X0=290,X1=960,Y0=330,Y1=900, s=1.0, ss=2, label=''):
    W=int((X1-X0)*s); H=int((Y1-Y0)*s)
    img=Image.new('RGBA',(W*ss,H*ss),(11,11,12,255))
    m=MT.resize((int(MT.width/1.5*s*ss),int(MT.height/1.5*s*ss)),Image.LANCZOS)
    img.alpha_composite(m,(int((87.333-X0)*s*ss),int((474-Y0)*s*ss)))
    d=ImageDraw.Draw(img)
    import render
    for spec,pose,lift in poses:
        # shadow (drawn inside the lifted transform in the app)
        gx=spec.worldX(pose.hip.x); gy=rig.ground_y(gx)
        prims=shapes.build(spec,pose)
        shifted=[]
        for p in prims:
            q=shapes.Prim(p.kind,p.tone,p.pts,p.width)
            if lift:
                v=list(p.pts)
                for i in range(1,len(v),2) if p.kind!=0 else [1]: v[i]-=lift
                q.pts=v
            shifted.append(q)
        sx=(gx-X0)*s*ss; sy=(gy-1-lift-Y0)*s*ss
        d.ellipse([sx-38*s*ss,sy-5.5*s*ss,sx+38*s*ss,sy+5.5*s*ss],fill=(42,42,56,60))
        render.draw_prims(d,shifted,X0,Y0,s*ss)
    im=img.resize((W,H),Image.LANCZOS).convert('RGB')
    ImageDraw.Draw(im).text((8,6),label,fill=(198,244,50)); return im
def run(out, youLife=None, themLife=None, ns=(0.20,0.38,0.42,0.50,0.60,0.70,0.80,0.92), posf=None):
    youLife=youLife or Life(stand=0,walk=0); themLife=themLife or Life(stand=0,walk=0)
    ims=[]
    for n in ns:
        yw,tw,hop=nudge_state(n); t=3.0+n*1.6
        pl=posf(YOU,yw,youLife,t) if posf else rig.pose(YOU,0.65,t,yw,youLife)
        pr=rig.pose(THEM,0.62,t,tw,themLife,jump=hop)
        ims.append(frame(n,[(YOU,pl,0),(THEM,pr,0)],label=f'n={n:.2f}  youWave={yw:.2f} themWave={tw:.2f} jump={hop:.2f}'))
    cols=4; rows=(len(ims)+cols-1)//cols
    sh=Image.new('RGB',(ims[0].width*cols,ims[0].height*rows))
    for k,im in enumerate(ims): sh.paste(im,((k%cols)*im.width,(k//cols)*im.height))
    sh.save(out); print(out,sh.size)
if __name__=='__main__': run(sys.argv[1] if len(sys.argv)>1 else 'nudge_after.png')

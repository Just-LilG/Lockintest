import sys, render, rig, shapes
from PIL import Image, ImageDraw
L=rig.Life
ims=[]
for spec,t,i,l in [(render.You,0.36,3.4,L()),(render.Them,0.55,3.0,L()),(render.You,0.5,5.0,L(stand=1,walk=0)),(render.You,0.45,3.0,L(tired=1))]:
    p=rig.pose(spec,t,i,0,l); prims=shapes.build(spec,p)
    hx=spec.worldX(p.head.x); hy=p.head.y; s=11; ss=2; W=H=int(60*s)
    img=Image.new('RGBA',(W*ss,H*ss),(11,11,12,255)); d=ImageDraw.Draw(img)
    render.draw_prims(d,prims,hx-30,hy-30,s*ss); ims.append(img.resize((W,H),Image.LANCZOS).convert('RGB'))
sh=Image.new('RGB',(ims[0].width*4,ims[0].height))
for k,im in enumerate(ims): sh.paste(im,(k*im.width,0))
sh.save(sys.argv[1])

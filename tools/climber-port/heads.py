import rig, render, shapes, sys
from PIL import Image, ImageDraw
L=rig.Life
me=rig.Spec(135,1,12,520,0,1,31.0)
her=rig.Spec(1115,-1,13,708,0,1,28.8); her.female=True
def head(spec,t,i,l,sc=9.0):
    p=rig.pose(spec,t,i,0.0,l); prims=shapes.build(spec,p)
    hx=spec.worldX(p.head.x); hy=p.head.y
    W=H=int(60*sc); ss=2
    img=Image.new('RGBA',(W*ss,H*ss),(228,232,240,255)); d=ImageDraw.Draw(img)
    render.draw_prims(d,prims,hx-30,hy-34,sc*ss)
    return img.resize((W,H),Image.LANCZOS)
ims=[head(me,0.3,3.0,L()),head(her,0.3,3.0,L()),head(me,0.5,5.0,L(stand=1,walk=0)),head(her,0.5,5.0,L(stand=1,walk=0))]
w,h=ims[0].size; sh=Image.new('RGB',(w*4,h))
for k,im in enumerate(ims): sh.paste(im.convert('RGB'),(k*w,0))
sh.save(sys.argv[1])

import sys, math, rig, shapes, render
from rig import Life
from PIL import Image, ImageDraw
def strip(frames, out, scale=2.6, title=''):
    ims=[]
    for (t,idle,life,wave,label) in frames:
        p=rig.pose(render.You,t,idle,wave,life); prims=shapes.build(render.You,p)
        W=int(200*scale); H=int(230*scale); ss=2
        cx=render.You.worldX(p.hip.x); cy=p.hip.y; tx=cx-90; ty=cy-175
        img=Image.new('RGBA',(W*ss,H*ss),(11,11,12,255)); d=ImageDraw.Draw(img)
        pts=[((x-tx)*scale*ss,(rig.ground_y(x)-ty)*scale*ss) for x in range(int(tx),int(tx+200),4)]
        d.polygon(pts+[(W*ss,H*ss),(0,H*ss)],fill=(228,232,240,255))
        render.draw_prims(d,prims,tx,ty,scale*ss)
        im=img.resize((W,H),Image.LANCZOS).convert('RGB'); ImageDraw.Draw(im).text((8,6),label,fill=(198,244,50)); ims.append(im)
    sh=Image.new('RGB',(ims[0].width*len(ims),ims[0].height))
    for k,im in enumerate(ims): sh.paste(im,(k*im.width,0))
    sh.save(out); print(out, sh.size)
if __name__=='__main__':
    # cheer onset sampled across the spring (cheer weight 0 .. 1)
    f=[(0.5,2.0,Life(stand=1,cheer=c,walk=0),0,f'cheer {c:.2f}') for c in (0,0.2,0.4,0.6,0.8,1.0)]
    strip(f,'strip_cheer_before.png')
    w=[(0.3,2.0,Life(),k,f'wave {k:.2f}') for k in (0,0.2,0.4,0.6,0.8,1.0)]
    strip(w,'strip_wave_before.png')

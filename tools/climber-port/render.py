import sys, math
from PIL import Image, ImageDraw
import rig, shapes
SCENE_X=60.0; SCENE_Y=250.0; SCENE_W=1140.0; BASELINE=905.0
You=rig.Spec(135,1,12,520,0,1,31.0); Them=rig.Spec(1115,-1,13,708,0,1,28.8)
def draw_prims(d, prims, tx, ty, s, accent=None, ss=1):
    for p in prims:
        col=shapes.PAL[p.tone]
        if p.tone==shapes.ACCENT and accent: col=accent
        rgba=((col>>16)&255,(col>>8)&255,col&255,255)
        v=p.pts
        T=lambda x,y:((x-tx)*s,(y-ty)*s)
        if p.kind==0:
            cx,cy=T(v[0],v[1]); r=v[2]*s
            d.ellipse([cx-r,cy-r,cx+r,cy+r],fill=rgba)
        elif p.kind==1:
            d.polygon([T(v[i],v[i+1]) for i in range(0,len(v),2)],fill=rgba)
        else:
            pts=[T(v[i],v[i+1]) for i in range(0,len(v),2)]
            w=p.width*s
            if len(pts)>1: d.line(pts,fill=rgba,width=max(1,int(round(w))),joint='curve')
            r=w/2
            for (x,y) in pts: d.ellipse([x-r,y-r,x+r,y+r],fill=rgba)
def render_one(spec,t,idle,life=None,wave=0.0,scale=3.0,box=None,bg=(11,11,12),ss=3):
    p=rig.pose(spec,t,idle,wave,life); prims=shapes.build(spec,p)
    wxs=[]
    cx=spec.worldX(p.hip.x); cy=p.hip.y
    W=int(260*scale); H=int(250*scale)
    tx=cx-130; ty=cy-165
    img=Image.new('RGBA',(W*ss,H*ss),bg+(255,))
    d=ImageDraw.Draw(img)
    # ground line from skyline
    pts=[]
    for x in range(int(tx),int(tx+260),4): pts.append(((x-tx)*scale*ss,(rig.ground_y(x)-ty)*scale*ss))
    d.polygon(pts+[(W*ss,H*ss),(0,H*ss)],fill=(228,232,240,255))
    draw_prims(d,prims,tx,ty,scale*ss)
    return img.resize((W,H),Image.LANCZOS)
if __name__=='__main__':
    tag=sys.argv[1] if len(sys.argv)>1 else 'base'
    L=rig.Life
    cases=[('walk a',You,0.30,3.0,L()),('walk b',You,0.36,3.4,L()),('walk c',Them,0.55,3.0,L()),
           ('stand',You,0.5,5.0,L(stand=1,walk=0)),('cheer',You,0.5,2.0,L(stand=1,cheer=1,walk=0)),('tired',You,0.45,3.0,L(tired=1))]
    ims=[render_one(s,t,i,l) for (_,s,t,i,l) in cases]
    W=sum(i.width for i in ims[:3]); sheet=Image.new('RGB',(ims[0].width*3,ims[0].height*2))
    for k,im in enumerate(ims): sheet.paste(im.convert('RGB'),((k%3)*im.width,(k//3)*im.height))
    sheet.save(f'sheet_{tag}.png'); print(sheet.size)

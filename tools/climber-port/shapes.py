# Python port of intro/ClimberShapes.kt (preview only).
import math
from math import sin, cos, atan2, hypot, pi
from rig import Pt, HEAD_R, ground_grade

(INK,JACKET,JACKET_FAR,PANTS,PANTS_FAR,BOOT,GLOVE,PACK,ROLL,HELMET,FACE,GEAR,GOGGLE,ROPE,ACCENT,SHINE,
 JACKET_SHADE,METAL,PANTS_SHADE,GAITER,SOLE,LENS,HOOD,SLEEVE_SHADE,GLOVE_HI,FACE_SHADE,PACK_SHADE,PACK_LID,
 PANTS_HI,JACKET_DEEP,PANTS_DEEP,HELMET_SHADE,PACK_DEEP)=range(33)
PAL=[0xFF0B0B0C,0xFFEAEAF3,0xFFC9C9D6,0xFF8F8FA0,0xFF6E6E7E,0xFF34343E,0xFF42424E,0xFFB0B0C2,0xFF9494A8,0xFFF8F8FB,0xFFBDBDCB,0xFF3C3C48,0xFF2E2E38,0xFF9A9AA8,0xFFC6F432,0xFFFFFFFF,0xFFDCDCE6,0xFF6A6A78,0xFF7A7A8A,0xFF50505E,0xFF1C1C24,0xFF1B2230,0xFFE6E6F0,0xFFD6D6E2,0xFF5E5E6C,0xFFA6A6B8,0xFF8E8EA2,0xFFA2A2B6,0xFFABABBD,0xFFA6A6BC,0xFF585868,0xFFC9C9D8,0xFF76768C]
HALO=2.7

class Prim:
    def __init__(s,kind,tone,pts,width=0.0): s.kind=kind; s.tone=tone; s.pts=list(pts); s.width=width

def smooth_closed(pts, sub):
    n=len(pts)//2; out=[]
    for i in range(n):
        i0=(i-1+n)%n; i1=i; i2=(i+1)%n; i3=(i+2)%n
        for s in range(sub):
            t=s/sub; t2=t*t; t3=t2*t
            for ax in (0,1):
                p0=pts[2*i0+ax]; p1=pts[2*i1+ax]; p2=pts[2*i2+ax]; p3=pts[2*i3+ax]
                out.append(0.5*((2*p1)+(-p0+p2)*t+(2*p0-5*p1+4*p2-p3)*t2+(-p0+3*p1-3*p2+p3)*t3))
    return out

def offset_poly(pts,d):
    n=len(pts)//2; area=0.0
    for i in range(n):
        j=(i+1)%n; area+=pts[2*i]*pts[2*j+1]-pts[2*j]*pts[2*i+1]
    sgn=1.0 if area>=0 else -1.0
    o=[0.0]*len(pts)
    for i in range(n):
        a=(i-1+n)%n; b=(i+1)%n
        tx=pts[2*b]-pts[2*a]; ty=pts[2*b+1]-pts[2*a+1]; l=max(hypot(tx,ty),1e-3)
        o[2*i]=pts[2*i]+sgn*ty/l*d; o[2*i+1]=pts[2*i+1]-sgn*tx/l*d
    return o

def helmet_grown(src,cx,cy,grow):
    o=[]
    for i in range(0,len(src),2):
        dx=src[i]-cx; dy=src[i+1]-cy; l=hypot(dx,dy)
        o+= [src[i]+dx/l*grow, src[i+1]+dy/l*grow]
    return o

def rope_anchor(c,p):
    tx=sin(p.lean); ty=-cos(p.lean); nx=cos(p.lean); ny=sin(p.lean)
    return Pt(c.worldX(p.hip.x+tx*10+nx*11), p.hip.y+ty*10+ny*11)

def draw_boot(c,ankle,pitch,tone,out):
    g=ground_grade(c,ankle.x)+pitch
    tx=cos(g); ty=sin(g); ux=sin(g); uy=-cos(g)
    def pt(fwd,up):
        return (c.worldX(ankle.x+tx*fwd+ux*up), ankle.y+ty*fwd+uy*up)
    outline=[pt(-7.5,-2),pt(-6.8,-8.6),pt(-2,-10),pt(14,-10),pt(19,-8),pt(20.5,-4.5),pt(17,-1),pt(9.5,2),pt(5.5,8),pt(-5.5,8)]
    arr=[v for q in outline for v in q]
    sm=smooth_closed(arr,3)
    out.append(Prim(1,INK,offset_poly(sm,HALO)))
    for f0 in (11.0,16.5):
        a=pt(f0,-9.5); b=pt(f0+2.2,-9.5); tip=pt(f0+1.2,-14.5)
        out.append(Prim(1,INK,[a[0]-0.8,a[1],b[0]+0.8,b[1],tip[0],tip[1]+1.2]))
        out.append(Prim(1,METAL,[a[0],a[1],b[0],b[1],tip[0],tip[1]]))
    out.append(Prim(1,tone,sm))
    s0=pt(-6,-8.4); s1=pt(18.5,-7.8)
    out.append(Prim(2,SOLE,[s0[0],s0[1],s1[0],s1[1]],3.6))
    t0=pt(15,-5.5); t1=pt(17.5,-2.6)
    out.append(Prim(2,GAITER,[t0[0],t0[1],t1[0],t1[1]],3.4))
    l0=pt(7,0.5); l1=pt(3.5,5.2)
    out.append(Prim(2,SHINE,[l0[0],l0[1],l1[0],l1[1]],1.2))

def build(c,p):
    out=[]
    wx=lambda pt: c.worldX(pt.x)
    tx=sin(p.lean); ty=-cos(p.lean); cnx=cos(p.lean); cny=sin(p.lean)
    def body(a,b): return Pt(p.hip.x+tx*b+cnx*a, p.hip.y+ty*b+cny*a)
    def circle(x,y,r,tone):
        out.append(Prim(0,INK,[x,y,r+HALO])); out.append(Prim(0,tone,[x,y,r]))
    def dot(x,y,r,tone): out.append(Prim(0,tone,[x,y,r]))
    def taper_quad(ax,ay,ra,bx,by,rb,tone):
        dx=bx-ax; dy=by-ay; ln=hypot(dx,dy)
        if ln<1e-3: return
        nx=-dy/ln; ny=dx/ln
        out.append(Prim(1,tone,[ax+nx*ra,ay+ny*ra,bx+nx*rb,by+ny*rb,bx-nx*rb,by-ny*rb,ax-nx*ra,ay-ny*ra]))
    def taper(ax,ay,ra,bx,by,rb,tone):
        taper_quad(ax,ay,ra+HALO,bx,by,rb+HALO,INK)
        out.append(Prim(0,INK,[ax,ay,ra+HALO])); out.append(Prim(0,INK,[bx,by,rb+HALO]))
        taper_quad(ax,ay,ra,bx,by,rb,tone)
        out.append(Prim(0,tone,[ax,ay,ra])); out.append(Prim(0,tone,[bx,by,rb]))
    def stroke(tone,width,*xy): out.append(Prim(2,tone,xy,width))
    def inked(tone,width,*xy):
        out.append(Prim(2,INK,xy,width+HALO*1.7)); out.append(Prim(2,tone,xy,width))
    def ring(x,y,r,tone,width):
        pts=[]
        for k in range(17):
            a=2*pi*k/16; pts+=[x+r*cos(a),y+r*sin(a)]
        out.append(Prim(2,INK,pts,width+2.2)); out.append(Prim(2,tone,pts,width))
    def blob(pts,tone,halo=HALO):
        sm=smooth_closed(pts,4)
        if halo>0: out.append(Prim(1,INK,offset_poly(sm,halo)))
        out.append(Prim(1,tone,sm))
    LX=c.dir*0.40; LY=-0.92
    def form(ax,ay,ra,bx,by,rb,shade,hi=None,t0=0.0,t1=1.0):
        """Shade along the side of a limb that faces away from the light, and a thin highlight on the lit side."""
        dx=bx-ax; dy=by-ay; ln=hypot(dx,dy)
        if ln<1e-3: return
        nx=-dy/ln; ny=dx/ln
        if nx*LX+ny*LY>0: nx=-nx; ny=-ny           # n points away from the light
        def P(t,r,k):                               # point at fraction t along the limb, k of the radius out on the shade side
            return (ax+dx*t+nx*r*k, ay+dy*t+ny*r*k)
        # a round-capped stroke on the shade side (soft ends, always inside the limb's outline)
        u0=t0+(t1-t0)*0.10; u1=t0+(t1-t0)*0.90
        rr0=ra+(rb-ra)*u0; rr1=ra+(rb-ra)*u1; rm=(rr0+rr1)/2
        w=rm*0.62; off=rm*0.64
        o0=(ax+dx*u0+nx*off*rr0/rm, ay+dy*u0+ny*off*rr0/rm); o1=(ax+dx*u1+nx*off*rr1/rm, ay+dy*u1+ny*off*rr1/rm)
        out.append(Prim(2,shade,[o0[0],o0[1],o1[0],o1[1]],w))
        if hi is not None:
            u0=t0+(t1-t0)*0.12; u1=t0+(t1-t0)*0.88
            ra_=ra+(rb-ra)*u0; rb_=ra+(rb-ra)*u1
            h=[(ax+dx*u0-nx*ra_*0.80, ay+dy*u0-ny*ra_*0.80),(ax+dx*u1-nx*rb_*0.80, ay+dy*u1-ny*rb_*0.80)]
            out.append(Prim(2,hi,[v for pt in h for v in pt],1.7))
    def limb(a,ra,b,rb,tone): taper(wx(a),a.y,ra,wx(b),b.y,rb,tone)
    def limb_form(a,ra,b,rb,shade,hi=None,t0=0.0,t1=1.0): form(wx(a),a.y,ra,wx(b),b.y,rb,shade,hi,t0,t1)

    def leg(hipJ,knee,ankle,pitch,tone,shade,boot,near):
        hxw=wx(hipJ); kxw=wx(knee); axw=wx(ankle)
        limb(hipJ,11.4,knee,8.6,tone); limb(knee,8.6,ankle,6.2,tone)
        cx=kxw+(axw-kxw)*0.30; cy=knee.y+(ankle.y-knee.y)*0.30
        dot(cx,cy,8.0,tone)
        if near:
            limb_form(hipJ,11.4,knee,8.6,PANTS_DEEP,PANTS_HI)
            limb_form(knee,8.6,ankle,6.2,PANTS_DEEP,PANTS_HI,0.0,0.8)
            tdx=kxw-hxw; tdy=knee.y-hipJ.y; tl=max(hypot(tdx,tdy),1e-3)
            lx=hxw+tdx/tl*15; ly=hipJ.y+tdy/tl*15
            stroke(GEAR,2.4,lx-tdy/tl*9.4,ly+tdx/tl*9.4,lx+tdy/tl*9.4,ly-tdx/tl*9.4)
        mx=(hxw+axw)/2; my=(hipJ.y+ankle.y)/2; fx=kxw-mx; fy=knee.y-my; fl=max(hypot(fx,fy),1e-3)
        dot(kxw+fx/fl*3.0,knee.y+fy/fl*3.0,4.2,shade)
        dx=kxw-axw; dy=knee.y-ankle.y; l=max(hypot(dx,dy),1e-3)
        gx=axw+dx/l*15; gy=ankle.y+dy/l*15
        taper(axw,ankle.y,6.8,gx,gy,6.6,GAITER)
        draw_boot(c,ankle,pitch,boot,out)

    def hand(h,dirX,dirY,grip,tone):
        x=wx(h); y=h.y; ln=max(hypot(dirX,dirY),1e-3); dx=dirX/ln; dy=dirY/ln
        open_=max(0.0,min(1.0,1-grip))
        if open_>0.15:
            fx=x+dx*(5+7*open_); fy=y+dy*(5+7*open_)
            taper(x,y,5.0,fx,fy,3.6-0.6*open_,tone)
        nx=-dy; ny=dx
        if ny>0: nx=-nx; ny=-ny
        tx2=x+nx*(4.2+1.2*open_)+dx*(1.5+3*open_); ty2=y+ny*(4.2+1.2*open_)+dy*(1.5+3*open_)
        out.append(Prim(0,INK,[tx2,ty2,2.4+HALO]))
        circle(x,y,5.1+0.7*grip,tone)
        dot(tx2,ty2,2.4,tone)
        dot(x-nx*1.4-dx*1.2,y-ny*1.4-dy*1.2,1.6,GLOVE_HI)

    def arm(shoulder,elbow,handPt,grip,tone,shade,glove):
        sx=wx(shoulder); ex=wx(elbow); hx=wx(handPt)
        limb(shoulder,8.4,elbow,7.0,tone); limb(elbow,7.0,handPt,5.6,tone)
        if shade==SLEEVE_SHADE:
            limb_form(shoulder,8.4,elbow,7.0,JACKET_DEEP,SHINE)
            bx_=sx+(ex-sx)*0.46; by_=shoulder.y+(elbow.y-shoulder.y)*0.46
            ux=ex-sx; uy=elbow.y-shoulder.y; ul=max(hypot(ux,uy),1e-3); px_=-uy/ul; py_=ux/ul
            stroke(ACCENT,3.4,bx_-px_*7.0,by_-py_*7.0,bx_+px_*7.0,by_+py_*7.0)
            limb_form(elbow,7.0,handPt,5.6,JACKET_DEEP,SHINE,0.0,0.85)
        mx=(sx+hx)/2; my=(shoulder.y+handPt.y)/2; ix=mx-ex; iy=my-elbow.y; il=hypot(ix,iy)
        if 3<il<48: stroke(INK,1.5,ex+ix/il*1.5,elbow.y+iy/il*1.5,ex+ix/il*6.4,elbow.y+iy/il*6.4)
        fdx=hx-ex; fdy=handPt.y-elbow.y; fl=max(hypot(fdx,fdy),1e-3)
        cx=ex+fdx*0.80; cy=elbow.y+fdy*0.80
        stroke(GEAR,2.8,cx-fdy/fl*5.2,cy+fdx/fl*5.2,cx+fdy/fl*5.2,cy-fdx/fl*5.2)
        hand(handPt,fdx,fdy,grip,glove)

    arm(p.shoulderB,p.elbowB,p.handB,p.gripB,JACKET_FAR,JACKET_FAR,GLOVE)
    leg(p.hipB,p.kneeB,p.ankleB,p.pitchB,PANTS_FAR,PANTS_FAR,BOOT,False)

    pl=p.lean-p.pack; ptx=sin(pl); pty=-cos(pl); pnx=cos(pl); pny=sin(pl)
    pivot=Pt(p.hip.x+tx*60,p.hip.y+ty*60)
    def rp(a,b): return Pt(pivot.x+ptx*(b-60)-pnx*a, pivot.y+pty*(b-60)-pny*a)
    def rpw(a,b): q=rp(a,b); return (wx(q),q.y)
    b0=rpw(31,12); b1=rpw(31,66); h1=rpw(25,72); h2=rpw(37,70)
    inked(METAL,2.6,b0[0],b0[1],b1[0],b1[1]); inked(GEAR,2.6,h1[0],h1[1],b1[0],b1[1],h2[0],h2[1])
    pts=[rpw(5,10),rpw(18,8),rpw(29,14),rpw(31,30),rpw(29,46),rpw(23,55),rpw(10,55),rpw(3,48)]
    blob([v for q in pts for v in q],PACK)
    lp=[rpw(4,44),rpw(14,41),rpw(27,43),rpw(25,54),rpw(14,58),rpw(5,53)]
    blob([v for q in lp for v in q],PACK_LID,1.6)
    kp=[rpw(23,14),rpw(31,16),rpw(31,30),rpw(24,31),rpw(21,22)]
    blob([v for q in kp for v in q],PACK_SHADE,1.2)
    dp=[rpw(5,11),rpw(18,9),rpw(28,15),rpw(29,24),rpw(14,22),rpw(4,24)]
    out.append(Prim(1,PACK_DEEP,[v for q in dp for v in q]))
    sh_=[rpw(5,38),rpw(26,40),rpw(27,44),rpw(5,43)]
    out.append(Prim(1,PACK_DEEP,[v for q in sh_ for v in q]))
    s1=rpw(29,38); s2=rpw(4,38); stroke(GEAR,2.4,s1[0],s1[1],s2[0],s2[1])
    roll=rp(19,64); circle(wx(roll),roll.y,8.5,ROLL); ring(wx(roll),roll.y,4.2,PACK_SHADE,1.4)
    coil=rp(21,28); ring(wx(coil),coil.y,8.5,ROPE,2.6); ring(wx(coil),coil.y,5.2,ROPE,2.2)

    h=body(-13,62); circle(wx(h),h.y,8.2,HOOD); h2_=body(-8,69); dot(wx(h2_),h2_.y,4.2,HOOD)

    tp=[body(12.4,-3),body(13.6,12),body(12.2,28),body(14.8,46),body(13.2,60),body(0,66),body(-12.4,60),body(-13.6,46),body(-11.6,28),body(-12.8,12),body(-12.2,-3),body(0,-6)]
    blob([v for q in tp for v in ((wx(q),q.y))],JACKET)
    # the half of the jacket away from the slope, in shade; a deeper zone along the hem (light comes from above)
    a=body(-8.6,6); b=body(-9.2,56)
    stroke(JACKET_DEEP,6.0,wx(a),a.y,wx(b),b.y)
    hz=[body(-12.2,-2),body(12.4,-2),body(13.2,13),body(6,17),body(-3,15),body(-12.6,14)]
    out.append(Prim(1,JACKET_DEEP,[v for q in hz for v in (wx(q),q.y)]))
    for bb in (22.0,36.0,50.0):
        q1=body(-12.0,bb); q2=body(12.6,bb); stroke(JACKET_DEEP,1.5,wx(q1),q1.y,wx(q2),q2.y)
    z1=body(10,8); z2=body(12.5,58); stroke(ACCENT,2.4,wx(z1),z1.y,wx(z2),z2.y)
    h1_=body(-5,63.5); h2__=body(9,62.5); stroke(SHINE,2.0,wx(h1_),h1_.y,wx(h2__),h2__.y)
    c1=body(8.0,40); c2=body(9.4,54); stroke(SHINE,1.6,wx(c1),c1.y,wx(c2),c2.y)
    m1=body(-12.6,-1); m2=body(12.8,-1); stroke(GEAR,3.6,wx(m1),m1.y,wx(m2),m2.y)

    bb1=Pt(p.hip.x+tx*6-cnx*13,p.hip.y+ty*6-cny*13); bb2=Pt(p.hip.x+tx*6+cnx*13,p.hip.y+ty*6+cny*13)
    stroke(GEAR,5.5,wx(bb1),bb1.y,wx(bb2),bb2.y)
    bk=body(3,6); dot(wx(bk),bk.y,2.2,METAL); cb=body(-9,4); ring(wx(cb),cb.y,2.6,METAL,1.2)
    ss1=Pt(p.shoulder.x-cnx*11+tx*2,p.shoulder.y-cny*11+ty*2)
    ss2=Pt(p.shoulder.x+cnx*14-tx*2,p.shoulder.y+cny*14-ty*2)
    ss3=Pt(p.hip.x+tx*16+cnx*13.5,p.hip.y+ty*16+cny*13.5)
    stroke(GEAR,3.4,wx(ss1),ss1.y,wx(ss2),ss2.y,wx(ss3),ss3.y)
    an=rope_anchor(c,p); out.append(Prim(0,INK,[an.x,an.y,5.8])); out.append(Prim(0,ROPE,[an.x,an.y,3.6]))

    leg(p.hipA,p.kneeA,p.ankleA,p.pitchA,PANTS,PANTS_SHADE,BOOT,True)

    hx=wx(p.head); hy=p.head.y
    up=atan2(p.head.x-p.shoulder.x,-(p.head.y-p.shoulder.y))
    vx=sin(up)*c.dir; vy=-cos(up); fx=cos(up)*c.dir; fy=sin(up)
    sxw=wx(p.shoulder)
    taper(sxw,p.shoulder.y,6.8,hx,hy,6.4,FACE)
    gx=sxw+(hx-sxw)*0.58; gy=p.shoulder.y+(hy-p.shoulder.y)*0.58
    taper(sxw,p.shoulder.y,8.6,gx,gy,8.2,ACCENT)
    bx0=sxw+(hx-sxw)*0.30-fx*6.5; by0=p.shoulder.y+(hy-p.shoulder.y)*0.30-fy*6.5
    tail=[bx0,by0]; ph=p.clock*6.3+(0.0 if c.dir>0 else 1.4); back=-c.dir
    for i in range(1,5):
        tail+=[bx0+back*i*4.8+0.9*i, by0+i*1.5+sin(ph-i*0.95)*(0.6+0.8*i)]
    inked(ACCENT,4.2,*tail)
    def hp(a,b): return (hx+fx*a+vx*b, hy+fy*a+vy*b)
    # ---- Face: a profile with a nose, a jaw and a cheek in shade ----
    circle(hx,hy,HEAD_R-0.5,FACE)
    n_=hp(HEAD_R-0.6,-4.6); circle(n_[0],n_[1],2.4,FACE)
    ch=hp(7.0,-10.2); circle(ch[0],ch[1],4.6,FACE)
    ck=hp(3.5,-7.5); dot(ck[0],ck[1],3.4,FACE_SHADE)
    m0=hp(9,-9.4); m1=hp(12,-9.4); stroke(INK,1.1,m0[0],m0[1],m1[0],m1[1])
    # chin strap, from under the shell to the jaw
    c0=hp(-1.5,1.5); c1=hp(1.5,-9.0); c2=hp(8,-11.5)
    stroke(GEAR,1.8,c0[0],c0[1],c1[0],c1[1],c2[0],c2[1])
    # the shadow the helmet throws on the brow
    f0=hp(14,2.4); f1=hp(0,-0.6); f2=hp(-10,-6.5)
    stroke(FACE_SHADE,6.0,f0[0],f0[1],f1[0],f1[1],f2[0],f2[1])
    # ---- Helmet: a rounded shell that wraps the back of the head, with a brim built into the front ----
    hl=[hp(-14.2,-9),hp(-16,0),hp(-14,8.5),hp(-8,14.2),hp(0,16.4),hp(8,14.8),hp(13.8,10.2),hp(19,6.8),hp(18,4.2),hp(8,3.0),hp(0,1.5),hp(-8,-2),hp(-12.5,-7)]
    blob([v for q in hl for v in q],HELMET)
    hs=[hp(-14.0,-8),hp(-15.6,0.5),hp(-13.4,8.0),hp(-9,11.6),hp(-8.5,6),hp(-10.5,-1),hp(-12,-6)]
    out.append(Prim(1,HELMET_SHADE,[v for q in hs for v in q]))
    bs=[hp(19,6.8),hp(18,4.2),hp(8,3.0),hp(8.5,5.2),hp(14,6.6)]
    out.append(Prim(1,HELMET_SHADE,[v for q in bs for v in q]))
    # a lime rim just inside the lower edge
    rm=[hp(15.5,5.6),hp(8,5.2),hp(0,3.6),hp(-8,0.4),hp(-13,-5.2)]
    stroke(ACCENT,1.9,*[v for q in rm for v in q])
    # vents on the crown
    for ang in (-50.0,-18.0,14.0):
        a=math.radians(ang); ax=vx*cos(a)+fx*sin(a); ay=vy*cos(a)+fy*sin(a)
        stroke(JACKET_SHADE,1.5,hx+ax*(HEAD_R-1.4),hy+ay*(HEAD_R-1.4),hx+ax*(HEAD_R+1.4),hy+ay*(HEAD_R+1.4))
    # ---- Goggles: a strap round the head, and a lens that sits on the eyes ----
    s0=hp(7,-0.8); s1=hp(-3,-1.4); s2=hp(-12.4,-4.6)
    stroke(GOGGLE,2.6,s0[0],s0[1],s1[0],s1[1],s2[0],s2[1])
    g1=hp(10.5,1.8); g2=hp(10.5,-2.8)
    inked(GOGGLE,7.4,g1[0],g1[1],g2[0],g2[1]); stroke(LENS,5.2,g1[0],g1[1],g2[0],g2[1])
    l0=hp(12.2,1.1); l1=hp(12.2,-1.4); stroke(ACCENT,1.4,l0[0],l0[1],l1[0],l1[1])
    gl=hp(11.4,2.2); dot(gl[0],gl[1],1.2,SHINE)
    hi=hp(4,12.2); dot(hi[0],hi[1],2.4,SHINE)

    ex=wx(p.elbowA); ey=p.elbowA.y; hx2=wx(p.handA); hy2=p.handA.y
    ln=max(hypot(hx2-ex,hy2-ey),1e-3); dx=(hx2-ex)/ln; dy=(hy2-ey)/ln
    t0=(hx2-dx*9,hy2-dy*9); headX=hx2+dx*24; headY=hy2+dy*24
    px=-dy; py=dx
    if py<0: px=-px; py=-py
    stroke(INK,6.4,t0[0],t0[1],headX,headY); stroke(METAL,3.4,t0[0],t0[1],headX,headY)
    stroke(ACCENT,3.6,hx2-dx*2,hy2-dy*2,hx2+dx*6,hy2+dy*6)
    pickX=headX+px*12+dx*3; pickY=headY+py*12+dy*3; adzeX=headX-px*8; adzeY=headY-py*8
    stroke(INK,6.0,adzeX,adzeY,headX,headY,pickX,pickY); stroke(GEAR,3.2,adzeX,adzeY,headX,headY,pickX,pickY)
    arm(p.shoulderA,p.elbowA,p.handA,p.gripA,JACKET,SLEEVE_SHADE,GLOVE)
    return out

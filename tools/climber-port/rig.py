# Line-for-line Python port of intro/ClimberRig.kt (for rendering previews only).
import math
from math import sin, cos, sqrt, atan2, acos, hypot, floor, pi
from sky import SKY, SKY_X0, SKY_STEP

def clamp01(x): return 0.0 if x < 0 else 1.0 if x > 1 else x
def mix(a, b, t): return a + (b - a) * t
def ease_sine(x): return 0.5 - 0.5 * cos(pi * clamp01(x))

def ground_y(x):
    f = (x - SKY_X0) / SKY_STEP
    i = int(f)
    if f < 0: i = 0
    i = max(0, min(len(SKY) - 2, i))
    t = clamp01(f - i)
    return SKY[i] * (1 - t) + SKY[i + 1] * t

class Pt:
    __slots__ = ('x', 'y')
    def __init__(s, x, y): s.x = x; s.y = y

class Spec:
    def __init__(s, xs, d, steps, xend, t0, dur, stride=0.0):
        s.xs = xs; s.dir = d; s.steps = steps; s.xEnd = xend; s.t0 = t0; s.dur = dur
        s.dTotal = abs(xend - xs)
        s.stepLen = stride if stride > 0 else s.dTotal / steps
        n = 24
        s._mean = None
    def worldX(s, u): return s.xs + s.dir * u
    @property
    def meanSlope(s):
        if s._mean is None:
            n = 24
            s._mean = sum(slope_at(s, s.dTotal * i / n) for i in range(n + 1)) / (n + 1)
        return s._mean

class Life:
    def __init__(s, stand=0.0, cheer=0.0, tired=0.0, slip=0.0, walk=1.0):
        s.stand = stand; s.cheer = cheer; s.tired = tired; s.slip = slip; s.walk = walk

class Pose: pass

THIGH = 50.0; SHIN = 50.0; UARM = 33.0; FARM = 31.0; TORSO = 64.0; HEAD_R = 13.5
ANKLE_H = 10.0; HAND_LEAD = 100.0; CROUCH = 0.80; LEAN_DEG = 57.0

def distance(c, t):
    x = (t - c.t0) / c.dur
    if x <= 0: return 0.0
    if x >= 1: return c.dTotal
    return c.dTotal * (0.35 * x + 0.65 * ease_sine(x))

def pulse(x, period, dur):
    f = ((x % period) + period) % period
    if f >= dur: return 0.0
    k = sin(pi * f / dur)
    return k * k

def smooth(x):
    v = clamp01(x); return v * v * (3 - 2 * v)

def slope_at(c, u):
    a = c.worldX(u - 10); b = c.worldX(u + 10)
    dx = max(abs(b - a), 1.0)
    return atan2(abs(ground_y(b) - ground_y(a)), dx)

def lerp_pt(a, b, k): return Pt(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)

def swing_pt(root, a, b, k, forward=False):
    if k <= 0: return a
    if k >= 1: return b
    ax = a.x - root.x; ay = a.y - root.y; bx = b.x - root.x; by = b.y - root.y
    ra = hypot(ax, ay); rb = hypot(bx, by)
    if ra < 6 or rb < 6: return lerp_pt(a, b, k)
    ta = atan2(ay, ax)
    dt = atan2(by, bx) - ta
    while dt > pi: dt -= 2 * pi
    while dt < -pi: dt += 2 * pi
    if forward and dt > 0.6: dt -= 2 * pi
    th = ta + dt * k; r = ra + (rb - ra) * k
    return Pt(root.x + r * cos(th), root.y + r * sin(th))

def ground_grade(c, u):
    x = c.worldX(u)
    dy = (ground_y(x + 1) - ground_y(x - 1)) / 2
    return atan2(dy * c.dir, 1.0)

class Limb:
    def __init__(s, j, e): s.joint = j; s.end = e

def two_bone(root, target, a, b, bend, soft=False):
    dx = target.x - root.x; dy = target.y - root.y
    dist = hypot(dx, dy); ln = a + b
    if soft:
        knee = 0.80 * ln
        if dist <= knee: d = max(1e-3, dist)
        else:
            span = 0.185 * ln
            d = knee + span * (1 - math.exp(-(dist - knee) / span))
    else:
        d = max(1e-3, min(ln - 1e-3, dist))
    cosA = max(-1.0, min(1.0, (a * a + d * d - b * b) / (2 * a * d)))
    ang = acos(cosA); th = atan2(dy, dx)
    joint = Pt(root.x + a * cos(th + bend * ang), root.y + a * sin(th + bend * ang))
    end = Pt(root.x + dx / dist * d, root.y + dy / dist * d) if dist > 1e-6 else target
    return Limb(joint, end)

def two_bone_blend(root, target, a, b, blend):
    k = clamp01(blend)
    if k <= 0: return two_bone(root, target, a, b, +1, True)
    if k >= 1: return two_bone(root, target, a, b, -1, True)
    p = two_bone(root, target, a, b, +1, True); m = two_bone(root, target, a, b, -1, True)
    return Limb(lerp_pt(p.joint, m.joint, k), p.end)

JUMP_H = 28.0

def pose(c, t, idleT, wave=0.0, life=None, jump=0.0):
    life = life or Life()
    ph = idleT + (0.0 if c.dir > 0 else 2.7)
    d = distance(c, t); step = c.stepLen
    phi = d / (2 * step); phiLag = phi - 0.07; twoPi = 2 * pi

    def footU(o, shift):
        psi = phi + o; n = floor(psi); f = psi - n
        s = 0.0 if f < 0.5 else ease_sine((f - 0.5) / 0.5)
        return 2 * step * (n + s) + step / 2 + shift
    def footLift(o):
        psi = phi + o; f = psi - floor(psi)
        if f < 0.5: return 0.0
        x = (f - 0.5) / 0.5
        return 15 * sin(pi * x ** 0.72)
    def footPitch(o):
        psi = phi + o; f = psi - floor(psi)
        if f < 0.5: return 0.50 * smooth((f - 0.28) / 0.22)
        x = (f - 0.5) / 0.5
        return 0.50 * (1 - smooth(x / 0.35)) - 0.30 * sin(pi * clamp01((x - 0.15) / 0.85))
    def ankle(u, lift): return Pt(u, ground_y(c.worldX(u)) - ANKLE_H - lift)

    aA = ankle(footU(0, 0), footLift(0)); aB = ankle(footU(0.5, -step), footLift(0.5))
    reach = CROUCH * (THIGH + SHIN)
    hipX = d + 2.2 * sin(ph * 0.55)
    def lowerBound(a):
        dx = a.x - hipX
        return a.y - sqrt(max(reach * reach - dx * dx, 1.0))
    bob = 3 * cos(twoPi * 2 * phi - 0.45)
    breathRate = 2.1 - 0.7 * life.tired
    breathe = (1.6 + 1.4 * life.tired) * sin(ph * breathRate)
    hipY = max(lowerBound(aA), lowerBound(aB)) + 5 + bob * 0.6 + breathe + 6 * life.tired
    hip = Pt(hipX, hipY)
    slopeNow = slope_at(c, d)
    slopeLean = max(-9.0, min(9.0, 0.45 * math.degrees(slopeNow - c.meanSlope)))
    look = pulse(ph, 8.5, 2.2)
    lean = math.radians(LEAN_DEG + slopeLean + 4 * cos(twoPi * 2 * phi) - 1.4 * sin(ph * breathRate) - 6 * look * (1 - life.tired) - 10 * wave + 5 * life.tired)
    tilt = lean * 0.38 + 0.05 * sin(ph * 1.3) - 0.42 * look * (1 - life.tired) - 0.25 * wave + 0.22 * life.tired
    tilt -= math.radians(4 * cos(twoPi * 2 * phiLag - 0.35) * 0.7)

    def handTarget(o):
        psi = phi + o; n = floor(psi); f = psi - n
        s = 0.0 if f < 0.5 else ease_sine((f - 0.5) / 0.5)
        lift = 0.0 if f < 0.5 else 13 * sin(pi * ((f - 0.5) / 0.5) ** 0.8)
        u = 2 * step * (n + s) + HAND_LEAD - 2 * step * o
        return Pt(u, ground_y(c.worldX(u)) - 5 - lift)
    def handGrip(o):
        psi = phi + o; f = psi - floor(psi)
        if f < 0.5: return 1.0
        x = (f - 0.5) / 0.5
        return 1 - 0.85 * sin(pi * clamp01(x * 1.05)) ** 0.7

    plant = pulse(ph + 3.1, 6.5, 1.2)
    ht = handTarget(0.5)
    handA = Pt(ht.x + 10 * plant, ht.y - 24 * plant)
    handB = handTarget(0.0)
    gripA = handGrip(0.5); gripB = handGrip(0.0)
    gripA = min(gripA, 1 - 0.5 * plant)
    footA = aA; footB = aB
    pitchA = footPitch(0); pitchB = footPitch(0.5)
    bendArmA = 0.0; bendLeg = -1.0
    pack = 0.10 * cos(twoPi * 2 * phiLag - 0.9)
    puff = life.tired
    twist = sin(twoPi * phi)
    climbSh = Pt(hip.x + TORSO * sin(lean), hip.y - TORSO * cos(lean))
    rest = clamp01(life.stand + (1 - life.walk))
    wStand = clamp01(rest + life.cheer)
    twistK = 1 - wStand
    if wStand > 0 or life.slip > 0:
        g = lambda u: ground_y(c.worldX(u))
        if wStand > 0:
            roll = sin(ph * 0.42)
            fA = Pt(d + 14, g(d + 14) - ANKLE_H)
            stamp = pulse(ph + 1.9, 7.5, 0.9) * (1 - life.cheer)
            bx = d - 16 + 3 * stamp
            fB = Pt(bx, g(bx) - ANKLE_H - 6 * stamp)
            tall = 0.94 * (THIGH + SHIN)
            sdx = d + 2.4 * roll
            def lb(a):
                dx = a.x - sdx; return a.y - sqrt(max(tall * tall - dx * dx, 1.0))
            hopPh = idleT * 8.4
            hop = life.cheer * 9 * max(0.0, sin(hopPh))
            squat = life.cheer * 4 * max(0.0, -sin(hopPh))
            sHip = Pt(sdx, max(lb(fA), lb(fB)) + 3 + breathe - hop + squat + 1.2 * stamp)
            sLean = math.radians(17 + 0.3 * slopeLean - 1.2 * sin(ph * breathRate) + 4 * life.tired - 7 * life.cheer)
            sSh = Pt(sHip.x + TORSO * sin(sLean), sHip.y - TORSO * cos(sLean))
            sTilt = sLean * 0.3 - 0.18 + 0.12 * sin(ph * 0.4) + 0.10 * sin(ph * 0.17 + 1) + 0.15 * life.tired - 0.30 * life.cheer * (0.6 + 0.4 * sin(hopPh - 0.8))
            sHandA = Pt(d + 40, g(d + 40) - 30)
            shake = pulse(ph + 5.2, 13, 1.5)
            sHandB = Pt(sHip.x + 9 + 16 * shake, sHip.y - 8 - 34 * shake + 5 * sin(idleT * 31) * shake)
            sGripA = 0.85; sGripB = 0.55 * (1 - shake) + 0.1 * shake
            if life.cheer > 0:
                pumpA = 7 * sin(hopPh - 0.5); pumpB = 7 * sin(hopPh - 1.1)
                upA = Pt(sSh.x + 22, sSh.y - 58 - pumpA); upB = Pt(sSh.x - 14, sSh.y - 60 + pumpB)
                k = clamp01(life.cheer / max(wStand, 1e-3))
                sHandA = swing_pt(sSh, sHandA, upA, k, True); sHandB = swing_pt(sSh, sHandB, upB, k, True)
                sGripA = mix(sGripA, 1, k); sGripB = mix(sGripB, 1, k)
            slide = wStand * (1 - wStand) * 4
            dA = abs(fA.x - aA.x); dB = abs(fB.x - aB.x)
            lift = 6 * slide
            hip = lerp_pt(hip, sHip, wStand)
            footA = lerp_pt(footA, fA, wStand); 
            if dA >= dB: footA = Pt(footA.x, footA.y - lift)
            footB = lerp_pt(footB, fB, wStand)
            if dA < dB: footB = Pt(footB.x, footB.y - lift)
            lean = mix(lean, sLean, wStand); tilt = mix(tilt, sTilt, wStand)
            blendSh = lerp_pt(climbSh, sSh, wStand)
            handA = swing_pt(blendSh, handA, sHandA, wStand); handB = swing_pt(blendSh, handB, sHandB, wStand)
            gripA = mix(gripA, sGripA, wStand); gripB = mix(gripB, sGripB, wStand)
            pitchA = mix(pitchA, 0.05, wStand); pitchB = mix(pitchB, 0.05 - 0.55 * stamp, wStand)
            pass  # elbow keeps its natural bend side (no flip)
            pack = mix(pack, 0.03 * sin(ph * breathRate) - life.cheer * 0.14 * max(0.0, sin(hopPh - 0.9)), wStand)
            puff = max(puff, 0.35 * (1 - life.walk))
        if life.slip > 0:
            k = life.slip
            hip = Pt(hip.x - 6 * k, hip.y + 16 * k)
            lean += 0.35 * k; tilt += 0.3 * k
            slipSh = Pt(hip.x + TORSO * sin(lean), hip.y - TORSO * cos(lean))
            handA = swing_pt(slipSh, handA, Pt(hip.x + 20, hip.y - 95), k * 0.8)
            gripA = mix(gripA, 0.2, k); pack -= 0.25 * k; twistK *= 1 - k

    if jump != 0.0:
        # A jump of surprise: negative = the crouch before take-off and the landing, positive = in the air.
        up = jump * JUMP_H if jump > 0 else jump * 9.0
        hip = Pt(hip.x, hip.y - up)
        # The hands go with the body, or the arms would stretch to the ground.
        handA = Pt(handA.x, handA.y - up * 0.85); handB = Pt(handB.x, handB.y - up * 0.85)
        if jump > 0:
            tuck = jump * (JUMP_H * 0.80 + 8.0)
            footA = Pt(footA.x, footA.y - tuck); footB = Pt(footB.x, footB.y - tuck * 0.9)
            pitchA += 0.30 * jump; pitchB += 0.40 * jump
            tilt -= 0.20 * jump
            pack -= 0.12 * jump
        else:
            lean += 0.12 * -jump
            pack += 0.06 * -jump
    sh = Pt(hip.x + TORSO * sin(lean), hip.y - TORSO * cos(lean))
    head = Pt(sh.x + 27 * sin(tilt), sh.y - 27 * cos(tilt))
    tw = twist * twistK
    hipA = Pt(hip.x - 1.7 * tw, hip.y); hipB = Pt(hip.x + 1.7 * tw, hip.y)
    shA = Pt(sh.x + 2.0 * tw, sh.y); shB = Pt(sh.x - 2.0 * tw, sh.y)
    legA = two_bone(hipA, footA, THIGH, SHIN, bendLeg); legB = two_bone(hipB, footB, THIGH, SHIN, bendLeg)
    waveTarget = Pt(sh.x + 38 + 14 * sin(idleT * 13), sh.y - 44)
    targetA = swing_pt(sh, handA, waveTarget, wave) if wave > 0 else handA
    bendA = 0.0
    armA = two_bone_blend(shA, targetA, UARM, FARM, bendA)
    armB = two_bone(shB, handB, UARM, FARM, +1, True)
    p = Pose()
    p.hip = hip; p.shoulder = sh; p.head = head
    p.kneeA = legA.joint; p.ankleA = legA.end; p.kneeB = legB.joint; p.ankleB = legB.end
    p.elbowA = armA.joint; p.handA = armA.end; p.elbowB = armB.joint; p.handB = armB.end
    p.lean = lean; p.pack = pack; p.hipA = hipA; p.hipB = hipB; p.shoulderA = shA; p.shoulderB = shB
    p.pitchA = pitchA; p.pitchB = pitchB; p.gripA = gripA; p.gripB = gripB
    p.clock = idleT; p.gaze = tilt - lean * 0.38; p.puff = puff
    return p

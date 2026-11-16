# LockIn Ludo sounds, synthesised from scratch. Run: python3 tools/ludo_sfx.py (needs numpy and ffmpeg with libvorbis),
# then copy out/*.ogg to app/src/main/res/raw.
import numpy as np, subprocess, wave, os
SR=44100
rng=np.random.default_rng(7)
def t(d): return np.arange(int(SR*d))/SR
def env(n, a=0.002, d=0.1, curve=4.0):
    x=np.arange(n)/SR
    e=np.minimum(1, x/max(a,1e-4))*np.exp(-np.maximum(0,x-a)/max(d,1e-4)*1.0)
    return e
def tone(f, d, a=0.003, dec=0.15, harm=((1,1),), fm=0):
    x=t(d); s=np.zeros_like(x)
    for h,amp in harm: s+=amp*np.sin(2*np.pi*f*h*x + fm*np.sin(2*np.pi*f*2*x))
    return s*env(len(x),a,dec)
def noise(d): return rng.uniform(-1,1,int(SR*d))
def lp(x, a):  # one-pole lowpass, a in (0,1): higher = more smoothing
    y=np.zeros_like(x); p=0.0
    for i,v in enumerate(x): p=p+(1-a)*(v-p); y[i]=p
    return y
def hp(x,a): return x-lp(x,a)
def bp(x, lo, hi): return hp(lp(x,lo),hi)
def place(buf, snd, at):
    i=int(at*SR); j=min(len(buf), i+len(snd)); buf[i:j]+=snd[:j-i]; return buf
def click(f=2500, d=0.03, dec=0.006, nmix=0.6):
    n=bp(noise(d),0.2,0.97)*nmix + tone(f,d,0.0005,dec)*0.8
    return n*env(len(n),0.0005,dec)
def out(name, x, gain=0.9):
    x=x/ (np.max(np.abs(x))+1e-9)*gain
    # tiny fade-out to avoid clicks
    k=min(len(x), int(0.004*SR)); x[-k:]*=np.linspace(1,0,k)
    w='/tmp/'+name+'.wav'
    with wave.open(w,'wb') as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(SR); f.writeframes((x*32767).astype(np.int16).tobytes())
    subprocess.run(['ffmpeg','-y','-loglevel','error','-i',w,'-c:a','libvorbis','-q:a','4','out/'+name+'.ogg'],check=True)
os.makedirs('out',exist_ok=True)

# Dice rattle in a cup: many small irregular clicks, thickening then thinning
b=np.zeros(int(SR*0.62))
for i in range(26):
    at=0.02+ (i/26)**0.9*0.56 + rng.uniform(-0.01,0.01)
    b=place(b, click(rng.uniform(1800,3600), 0.025, rng.uniform(0.003,0.007))*rng.uniform(0.35,1.0), at)
b+=bp(noise(0.62),0.6,0.995)*0.05*env(len(b),0.05,0.4)
out('ludo_shake', b, 0.75)

# Dice landing: low thump + two quick clacks (bounce)
x=np.zeros(int(SR*0.32))
thump=tone(140,0.12,0.001,0.035,((1,1),(2.1,0.3)))+lp(noise(0.12),0.9)*0.4*env(int(SR*0.12),0.001,0.02)
x=place(x,thump,0)
x=place(x,click(3000,0.04,0.008,0.7),0.0)
x=place(x,click(3400,0.03,0.006,0.6)*0.6,0.11)
x=place(x,click(3700,0.02,0.004,0.5)*0.35,0.19)
out('ludo_land', x)

# Token hop: wooden knock (played at rising rates on each step)
k=tone(620,0.09,0.0008,0.022,((1,1),(2.76,0.45),(5.4,0.2)))+bp(noise(0.09),0.5,0.98)*0.25*env(int(SR*0.09),0.0005,0.01)
out('ludo_hop', k, 0.7)

# Out of the yard: bubbly pop + rising sweep
d=0.3; x=t(d); f=380+900*(x/d)**1.6
s=np.sin(2*np.pi*np.cumsum(f)/SR)*env(len(x),0.004,0.09)
s=place(s,click(1500,0.03,0.006)*0.5,0)
out('ludo_out', s)

# Capture: whoosh (filtered noise sweep) + heavy thud + falling tone
d=0.75; n=len(t(d)); buf=np.zeros(n)
w=noise(0.35); a=np.linspace(0.97,0.6,len(w)); ww=np.zeros_like(w); p=0
for i,v in enumerate(w): p=p+(1-a[i])*(v-p); ww[i]=p
ww*=np.sin(np.linspace(0,np.pi,len(ww)))**1.5
buf=place(buf, ww*1.6, 0)
th=tone(90,0.3,0.001,0.08,((1,1),(1.5,0.4),(2.2,0.2)))+lp(noise(0.3),0.93)*0.8*env(int(SR*0.3),0.001,0.05)
buf=place(buf, th*1.3, 0.28)
x2=t(0.4); f=520*np.exp(-x2*4)+120
buf=place(buf, np.sin(2*np.pi*np.cumsum(f)/SR)*env(len(x2),0.005,0.18)*0.5, 0.3)
out('ludo_capture', buf)

# Safe square: soft glassy bell
s=tone(1318.5,0.6,0.002,0.25,((1,1),(2.0,0.25),(3.01,0.12)))+tone(1975.5,0.6,0.002,0.2)*0.35
out('ludo_safe', s, 0.55)

# Home: sparkling arpeggio up (C major 9) with shimmer
notes=[523.25,659.25,783.99,1046.5,1318.5,1567.98]
buf=np.zeros(int(SR*1.1))
for i,f in enumerate(notes):
    buf=place(buf, tone(f,0.5,0.002,0.22,((1,1),(2,0.3),(4,0.08))), i*0.07)
for i in range(10):
    buf=place(buf, tone(rng.uniform(2500,5200),0.12,0.001,0.04)*0.18, 0.3+rng.uniform(0,0.6))
out('ludo_home', buf, 0.8)

# Six: bright double ding
buf=np.zeros(int(SR*0.5))
buf=place(buf, tone(1046.5,0.35,0.001,0.12,((1,1),(2,0.4),(3,0.15))),0)
buf=place(buf, tone(1567.98,0.4,0.001,0.16,((1,1),(2,0.4),(3,0.15))),0.09)
out('ludo_six', buf, 0.7)

# Your turn: warm two-note chime (marimba-like)
buf=np.zeros(int(SR*0.6))
mar=lambda f: tone(f,0.45,0.001,0.12,((1,1),(4.0,0.25),(9.2,0.05)))
buf=place(buf, mar(587.33),0); buf=place(buf, mar(880.0),0.12)
out('ludo_turn', buf, 0.7)

# Clock tick for the last seconds
out('ludo_tick', place(click(4200,0.03,0.004,0.4),tone(1900,0.03,0.0005,0.006)*0.5,0), 0.45)

# Win fanfare: brassy-ish chords with a rising run and sparkles
def brass(f,d,dec=0.4):
    x=t(d); s=np.zeros_like(x)
    for h in range(1,9): s+= (1/h**1.1)*np.sin(2*np.pi*f*h*x)
    e=np.minimum(1,x/0.03)*np.exp(-x/dec)
    return s*e
buf=np.zeros(int(SR*2.2))
seq=[(392.0,0,0.18),(523.25,0.16,0.18),(659.25,0.32,0.18),(783.99,0.48,1.4)]
for f,at,dd in seq:
    buf=place(buf, brass(f,dd+0.4, 0.25 if dd<1 else 0.7)*0.6, at)
for f in (523.25,659.25,1046.5): buf=place(buf, brass(f,1.5,0.7)*0.35, 0.48)
for i in range(18): buf=place(buf, tone(rng.uniform(2200,6000),0.15,0.001,0.05)*0.12, 0.5+rng.uniform(0,1.3))
out('ludo_win', lp(buf,0.25), 0.85)

# Lose: gentle descending three notes
buf=np.zeros(int(SR*1.1))
for i,f in enumerate([523.25,440.0,349.23]):
    buf=place(buf, tone(f,0.6,0.004,0.25,((1,1),(2,0.2))), i*0.22)
out('ludo_lose', buf, 0.6)

# Emote pop
x=t(0.12); f=900+1400*np.exp(-x*40)
out('ludo_pop', np.sin(2*np.pi*np.cumsum(f)/SR)*env(len(x),0.001,0.03), 0.6)

# Start whoosh+chime
buf=np.zeros(int(SR*0.9))
w=bp(noise(0.5),0.85,0.99)*np.sin(np.linspace(0,np.pi,int(SR*0.5)))**2
buf=place(buf,w*0.8,0)
buf=place(buf,tone(783.99,0.5,0.003,0.2,((1,1),(2,0.3)))*0.7,0.35)
buf=place(buf,tone(1174.66,0.5,0.003,0.2,((1,1),(2,0.3)))*0.7,0.45)
out('ludo_start', buf, 0.7)
print(sorted(os.listdir('out')))

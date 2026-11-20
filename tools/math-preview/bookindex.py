"""Prototype of study/BookIndex.kt: tokenising, chunking and BM25 over the text of a book's pages."""
import math, re
STOP = set("the a an and or of to in on for with is are was were be been it this that these those as at by from what which who how why does do did can you your about into than then there their its not no if but so we i me my".split())

def stem(w):
    if w.endswith("ies") and len(w) > 4: return w[:-3] + "y"
    if w.endswith("ing") and len(w) > 5: return w[:-3]
    if w.endswith("ed") and len(w) > 4: return w[:-2]
    if w.endswith("s") and not w.endswith("ss") and not w.endswith("us") and len(w) > 3: return w[:-1]
    return w

def terms(text):
    return [stem(w) for w in re.findall(r"[^\W_]+", text.lower()) if len(w) >= 2 and w not in STOP]

def chunk(text, mx=650):
    paras = [re.sub(r"\s+", " ", p).strip() for p in re.split(r"\n\s*\n|\n", text.replace("\r", ""))]
    out, cur = [], ""
    def flush():
        nonlocal cur
        if cur: out.append(cur); cur = ""
    for p in paras:
        if not p: continue
        while len(p) > mx:
            cut = p.rfind(". ", 0, mx)
            cut = cut + 1 if cut > mx // 3 else mx
            piece, p = p[:cut].strip(), p[cut:].strip()
            flush(); out.append(piece)
        if len(cur) + len(p) + 1 > mx: flush()
        cur = (cur + " " + p).strip()
    flush()
    return out

class Index:
    def __init__(self):
        self.chunks = []   # (page, text, tf, len)
        self.df = {}
        self.pages = {}
        self.total = 0
    def add(self, page, text):
        self.pages[page] = text
        for c in chunk(text):
            ts = terms(c)
            if not ts: continue
            tf = {}
            for t in ts: tf[t] = tf.get(t, 0) + 1
            self.chunks.append((page, c, tf, len(ts)))
            self.total += len(ts)
            for t in tf: self.df[t] = self.df.get(t, 0) + 1
    def search(self, q, k=3, exclude=()):
        qs = set(terms(q))
        if not qs or not self.chunks: return []
        n = len(self.chunks); avg = self.total / n
        best = {}
        for page, text, tf, ln in self.chunks:
            if page in exclude: continue
            s = 0.0
            for t in qs:
                f = tf.get(t, 0)
                if not f: continue
                idf = math.log(1 + (n - self.df[t] + 0.5) / (self.df[t] + 0.5))
                s += idf * (f * 2.5) / (f + 1.5 * (0.25 + 0.75 * ln / avg))
            if s > 0 and (page not in best or s > best[page][0]): best[page] = (s, text)
        hits = sorted(((s, p, t) for p, (s, t) in best.items()), reverse=True)
        if not hits: return []
        top = hits[0][0]
        return [(p, t, round(s, 3)) for s, p, t in hits if s >= 0.3 * top][:k]

CORPUS = {
    0: "Chapter 1: Motion. An object is in motion when its position changes with time. Speed is the distance travelled divided by the time taken.",
    1: "Velocity is speed in a given direction. Acceleration is the rate at which velocity changes. A car speeding up, slowing down or turning is accelerating.",
    2: "Newton's second law says the net force on an object equals its mass times its acceleration: F = ma. A bigger force gives a bigger acceleration.",
    3: "Mass is the amount of matter in an object, measured in kilograms. Weight is the force of gravity on that mass and is measured in newtons.",
    4: "Energy is the ability to do work. Kinetic energy depends on mass and speed. Potential energy depends on height.",
    5: "Photosynthesis is how plants make food from light, water and carbon dioxide. It happens in the chloroplasts of leaf cells.",
}
if __name__ == "__main__":
    ix = Index()
    for p, t in CORPUS.items(): ix.add(p, t)
    for q in ["what is mass", "how does force change acceleration", "how do plants make food", "kinetic energy", "weight in newtons", "something unrelated about volcanoes"]:
        print(q, "->", [(p, s) for p, _, s in ix.search(q)])
    print("chunk:", [len(c) for c in chunk(("Sentence one is here. " * 60) + "\n\nSecond paragraph.")])
    print(terms("The forces on moving objects, accelerating quickly!"))

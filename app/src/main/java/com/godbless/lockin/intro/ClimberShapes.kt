package com.godbless.lockin.intro

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Tone indices into [ClimberPalette]. Kept as ints so this file stays plain Kotlin. */
internal object Tone {
    const val INK = 0
    const val JACKET = 1
    const val JACKET_FAR = 2
    const val PANTS = 3
    const val PANTS_FAR = 4
    const val BOOT = 5
    const val GLOVE = 6
    const val PACK = 7
    const val ROLL = 8
    const val HELMET = 9
    const val FACE = 10
    const val GEAR = 11
    const val GOGGLE = 12
    const val ROPE = 13
    const val ACCENT = 14
    const val SHINE = 15
    const val JACKET_SHADE = 16
    const val METAL = 17
    const val PANTS_SHADE = 18
    const val GAITER = 19
    const val SOLE = 20
    const val LENS = 21
    const val HOOD = 22
    const val SLEEVE_SHADE = 23
    const val GLOVE_HI = 24
    const val FACE_SHADE = 25
    const val PACK_SHADE = 26
    const val PACK_LID = 27
    const val PANTS_HI = 28
    const val JACKET_DEEP = 29
    const val PANTS_DEEP = 30
    const val HELMET_SHADE = 31
    const val PACK_DEEP = 32
    const val HAIR = 33
    const val HAIR_HI = 34
    const val EYE_W = 35
    const val CHEEK = 36
    const val LIP = 37
    const val SKIN_HI = 38
    const val SKIN_DEEP = 39
    const val IRIS = 40
    const val LENS_HI = 41
    const val HAIR_DEEP = 42
    const val HELMET_RIM = 43
    const val IRIS_HI = 44
}

/** The colour of each [Tone], as ARGB. (The painter swaps ACCENT for the theme's accent.) */
internal val ClimberPalette = intArrayOf(
    0xFF0B0B0C.toInt(), // INK (outline)
    0xFF252832.toInt(), // JACKET: deep graphite technical shell
    0xFF171B24.toInt(), // JACKET_FAR: far side falls into shadow
    0xFF343844.toInt(), // PANTS: charcoal climbing trousers
    0xFF20242E.toInt(), // PANTS_FAR
    0xFF171A22.toInt(), // BOOT
    0xFF242A35.toInt(), // GLOVE
    0xFF303744.toInt(), // PACK
    0xFF454C5A.toInt(), // ROLL
    0xFFF8F8FB.toInt(), // HELMET
    0xFF9A573B.toInt(), // FACE (warm deep-brown skin)
    0xFF11151D.toInt(), // GEAR
    0xFF171D2A.toInt(), // GOGGLE
    0xFFB5BBC5.toInt(), // ROPE
    0xFFC6F432.toInt(), // ACCENT (LockIn lime)
    0xFFFFFFFF.toInt(), // SHINE
    0xFF414856.toInt(), // JACKET_SHADE
    0xFF9CA7B5.toInt(), // METAL
    0xFF252B36.toInt(), // PANTS_SHADE
    0xFF1D232D.toInt(), // GAITER
    0xFF090C12.toInt(), // SOLE
    0xFF101A2A.toInt(), // LENS
    0xFF303744.toInt(), // HOOD
    0xFF343B48.toInt(), // SLEEVE_SHADE
    0xFF566171.toInt(), // GLOVE_HI
    0xFF713D2D.toInt(), // FACE_SHADE (skin in shade)
    0xFF222936.toInt(), // PACK_SHADE
    0xFF3B4351.toInt(), // PACK_LID
    0xFFABABBD.toInt(), // PANTS_HI (lit edge of the trousers)
    0xFF141922.toInt(), // JACKET_DEEP (jacket in shade)
    0xFF171C25.toInt(), // PANTS_DEEP (trousers in shade)
    0xFFC9C9D8.toInt(), // HELMET_SHADE
    0xFF76768C.toInt(), // PACK_DEEP
    0xFF241811.toInt(), // HAIR
    0xFF533322.toInt(), // HAIR_HI
    0xFFF6F2EE.toInt(), // EYE_W
    0xFFB86D55.toInt(), // CHEEK (warm cheek highlight)
    0xFF6E302B.toInt(), // LIP
    0xFFB97753.toInt(), // SKIN_HI (lit skin: cheekbone, nose tip)
    0xFF5B2B22.toInt(), // SKIN_DEEP (neck under the jaw)
    0xFF4A2A18.toInt(), // IRIS
    0xFF3B5F95.toInt(), // LENS_HI (goggle reflection)
    0xFF120B08.toInt(), // HAIR_DEEP
    0xFFB9BACB.toInt(), // HELMET_RIM (band along the lower edge)
    0xFF8D5E36.toInt(), // IRIS_HI (lighter lower half of the iris)
)

/** One drawing primitive in scene (world) coordinates. */
internal class Prim(
    /** 0 = filled circle [x, y, r]; 1 = filled polygon [x0, y0, x1, y1 ...]; 2 = round-capped polyline; 3 = soft disc [x, y, r] that fades to nothing at its edge (blush, light). */
    val kind: Int,
    val tone: Int,
    val pts: FloatArray,
    val width: Float = 0f,
)

/**
 * Turns a climber's pose into a stack of shapes, back to front. Every body part is drawn twice:
 * first slightly larger in the ink colour (a thin outline so the figure never melts into the
 * white mountain), then in its own tone.
 *
 * The figure is built to read as a person in winter kit rather than a mannequin: a puffy jacket with baffles and a
 * hood, tapered limbs with knee pads and cuffs, gloves that open to reach and close on the axe, boots that roll with
 * each step (gaiter, sole, crampon points), a packed rucksack with a lid and a strapped axe, a neck gaiter whose tail
 * streams in the same wind as the flag, and a face under the goggles.
 */
internal object ClimberShapes {
    private const val HALO = 2.7f

    class Out {
        val prims = ArrayList<Prim>(220)
    }

    fun ropeAnchor(c: ClimberSpec, p: Pose): Pt {
        val tx = sin(p.lean)
        val ty = -cos(p.lean)
        val nx = cos(p.lean)
        val ny = sin(p.lean)
        return Pt(c.worldX(p.hip.x + tx * 10f + nx * 11f), p.hip.y + ty * 10f + ny * 11f)
    }

    fun build(c: ClimberSpec, p: Pose): List<Prim> {
        val out = Out()
        fun wx(pt: Pt) = c.worldX(pt.x)

        // Torso axis (up the back), chest normal (facing the slope) and back normal.
        val tx = sin(p.lean)
        val ty = -cos(p.lean)
        val cnx = cos(p.lean)
        val cny = sin(p.lean)

        // A restrained breath cycle expands the upper shell and lifts the chest a fraction.
        // It is deliberately subtle: the jacket should breathe, not pulse like an inflatable toy.
        val breathPulse = (0.5f + 0.5f * sin(p.clock * (1.85f - 0.35f * p.puff))) * p.puff
        /** A point on the torso: [a] forward from its centre line, [b] up from the hip. In the local frame. */
        fun body(a: Float, b: Float): Pt {
            val chest = (b / 64f).coerceIn(0f, 1f)
            val expansion = 1f + 0.032f * breathPulse * chest
            return Pt(
                p.hip.x + tx * b + cnx * a * expansion,
                p.hip.y + ty * b + cny * a * expansion - 0.65f * breathPulse * chest,
            )
        }

        // A female climber is a little slimmer through the shoulders, waist and limbs, and has a ponytail.
        val fem = c.female
        val lk = if (fem) 0.94f else 1f
        val ak = if (fem) 0.92f else 1f
        val thigh = 11.4f * (if (fem) 1.12f else lk)
        // Her torso is shaped by two width curves (heights in [femB]): the chest side swells into a bust and nips in at the
        // waist, the back side swells into the hips and seat. A man's torso is left as it was.
        val femB = floatArrayOf(-3f, 6f, 14f, 28f, 38f, 48f, 56f, 66f)
        val femFront = floatArrayOf(0.95f, 0.98f, 0.92f, 0.70f, 0.96f, 1.32f, 0.98f, 0.88f)
        val femBack = floatArrayOf(1.18f, 1.26f, 1.05f, 0.74f, 0.80f, 0.84f, 0.86f, 0.88f)
        fun curveAt(v: FloatArray, b: Float): Float {
            if (b <= femB[0]) return v[0]
            for (i in 0 until femB.size - 1) {
                if (b <= femB[i + 1]) {
                    val t = (b - femB[i]) / (femB[i + 1] - femB[i])
                    val e = t * t * (3f - 2f * t)
                    return v[i] + (v[i + 1] - v[i]) * e
                }
            }
            return v[v.size - 1]
        }
        /** Width factor of the torso at height [b] on the side [a] is on. */
        fun wf(a: Float, b: Float): Float = if (!fem) 1f else curveAt(if (a >= 0f) femFront else femBack, b)
        /** Like [body] but scaled by the torso's width at that height. */
        fun tb(a: Float, b: Float) = body(a * wf(a, b), b)
        fun wflat(vararg q: Pt): FloatArray {
            val o = FloatArray(q.size * 2)
            for (i in q.indices) { o[2 * i] = wx(q[i]); o[2 * i + 1] = q[i].y }
            return o
        }

        fun circle(x: Float, y: Float, r: Float, tone: Int) {
            out.prims.add(Prim(0, Tone.INK, floatArrayOf(x, y, r + HALO)))
            out.prims.add(Prim(0, tone, floatArrayOf(x, y, r)))
        }

        fun dot(x: Float, y: Float, r: Float, tone: Int) {
            out.prims.add(Prim(0, tone, floatArrayOf(x, y, r)))
        }

        fun taperQuad(ax: Float, ay: Float, ra: Float, bx: Float, by: Float, rb: Float, tone: Int) {
            val dx = bx - ax
            val dy = by - ay
            val len = hypot(dx, dy)
            if (len < 1e-3f) return
            val nx = -dy / len
            val ny = dx / len
            out.prims.add(
                Prim(
                    1, tone,
                    floatArrayOf(
                        ax + nx * ra, ay + ny * ra,
                        bx + nx * rb, by + ny * rb,
                        bx - nx * rb, by - ny * rb,
                        ax - nx * ra, ay - ny * ra,
                    ),
                ),
            )
        }

        /** A limb segment that narrows from [ra] to [rb], with its outline underneath. */
        fun taper(ax: Float, ay: Float, ra: Float, bx: Float, by: Float, rb: Float, tone: Int) {
            taperQuad(ax, ay, ra + HALO, bx, by, rb + HALO, Tone.INK)
            out.prims.add(Prim(0, Tone.INK, floatArrayOf(ax, ay, ra + HALO)))
            out.prims.add(Prim(0, Tone.INK, floatArrayOf(bx, by, rb + HALO)))
            taperQuad(ax, ay, ra, bx, by, rb, tone)
            out.prims.add(Prim(0, tone, floatArrayOf(ax, ay, ra)))
            out.prims.add(Prim(0, tone, floatArrayOf(bx, by, rb)))
        }

        fun stroke(tone: Int, width: Float, vararg xy: Float) {
            out.prims.add(Prim(2, tone, xy, width))
        }

        /** A stroke with its ink outline underneath. */
        fun inked(tone: Int, width: Float, vararg xy: Float) {
            out.prims.add(Prim(2, Tone.INK, xy, width + HALO * 1.7f))
            out.prims.add(Prim(2, tone, xy, width))
        }

        /** A circle drawn as a line (for rope coils). */
        fun ring(x: Float, y: Float, r: Float, tone: Int, width: Float) {
            val pts = FloatArray(2 * 41)
            for (k in 0..40) {
                val a = 2.0 * PI * k / 40.0
                pts[2 * k] = x + r * cos(a).toFloat()
                pts[2 * k + 1] = y + r * sin(a).toFloat()
            }
            out.prims.add(Prim(2, Tone.INK, pts, width + 2.2f))
            out.prims.add(Prim(2, tone, pts, width))
        }

        /** A smooth closed shape through the world points [pts] (x0, y0, x1, y1 ...), outlined in ink. */
        fun blob(pts: FloatArray, tone: Int, halo: Float = HALO) {
            val sm = smoothClosed(pts, 4)
            if (halo > 0f) out.prims.add(Prim(1, Tone.INK, offsetPoly(sm, halo)))
            out.prims.add(Prim(1, tone, sm))
        }

        fun limb(a: Pt, ra: Float, b: Pt, rb: Float, tone: Int) =
            taper(wx(a), a.y, ra, wx(b), b.y, rb, tone)

        // The light comes from above and a little ahead (towards the summit), so the underside of every limb is in shade.
        val lightX = c.dir * 0.40f
        val lightY = -0.92f

        /**
         * Shading that hugs one edge of a limb from ([ax], [ay], radius [ra]) to ([bx], [by], [rb]) between [t0] and [t1]:
         * widest in the middle and tapering to nothing at both ends, so it follows the outline instead of lying over
         * the limb like a stripe. [lit] false is the side away from the light, true the side towards it.
         */
        fun crescent(ax: Float, ay: Float, ra: Float, bx: Float, by: Float, rb: Float, tone: Int, depth: Float = 0.5f, t0: Float = 0f, t1: Float = 1f, lit: Boolean = false) {
            val dx = bx - ax
            val dy = by - ay
            val len = hypot(dx, dy)
            if (len < 1e-3f) return
            var nx = -dy / len
            var ny = dx / len
            if ((nx * lightX + ny * lightY > 0f) != lit) { nx = -nx; ny = -ny }
            val n = 12
            val pts = FloatArray(2 * 2 * (n + 1))
            for (i in 0..n) {
                val u = i.toFloat() / n
                val t = t0 + (t1 - t0) * u
                val r = (ra + (rb - ra) * t) * 0.995f
                val d = depth * (ra + (rb - ra) * t) * Math.pow(sin(PI * u), 0.8).toFloat()
                val cx = ax + dx * t
                val cy = ay + dy * t
                pts[2 * i] = cx + nx * r; pts[2 * i + 1] = cy + ny * r                      // on the outline
                val j = 2 * n + 1 - i                                                      // back along the inner edge
                pts[2 * j] = cx + nx * (r - d); pts[2 * j + 1] = cy + ny * (r - d)
            }
            out.prims.add(Prim(1, tone, pts))
        }

        /** A band that goes right round a limb at fraction [t] along it (edge to edge, its edges curved like a cylinder's). */
        fun wrapBand(ax: Float, ay: Float, ra: Float, bx: Float, by: Float, rb: Float, t: Float, width: Float, tone: Int, bow: Float = 0.22f) {
            val dx = bx - ax
            val dy = by - ay
            val len = hypot(dx, dy)
            if (len < 1e-3f) return
            val ux = dx / len
            val uy = dy / len
            val nx = -uy
            val ny = ux
            val half = width / 2f / len
            val n = 9
            val pts = FloatArray(2 * 2 * (n + 1))
            for (k in 0..n) {
                val sv = -1f + 2f * k / n
                for (side in 0..1) {
                    val tt = if (side == 0) t - half else t + half
                    val r = ra + (rb - ra) * tt
                    val bulge = bow * r * (1f - sv * sv)
                    val x = ax + dx * tt + nx * r * sv * 0.995f + ux * bulge
                    val y = ay + dy * tt + ny * r * sv * 0.995f + uy * bulge
                    val idx = if (side == 0) k else 2 * n + 1 - k
                    pts[2 * idx] = x; pts[2 * idx + 1] = y
                }
            }
            out.prims.add(Prim(1, tone, pts))
        }

        /** An oval patch centred on ([cx], [cy]) whose long axis ([ry]) points along ([dirX], [dirY]). */
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, dirX: Float, dirY: Float, tone: Int) {
            val l = hypot(dirX, dirY).coerceAtLeast(1e-3f)
            val ux = dirX / l
            val uy = dirY / l
            val vx2 = -uy
            val vy2 = ux
            val n = 24
            val pts = FloatArray(2 * n)
            for (k in 0 until n) {
                val a = 2.0 * PI * k / n
                val ex2 = rx * cos(a).toFloat()
                val ey2 = ry * sin(a).toFloat()
                pts[2 * k] = cx + vx2 * ex2 + ux * ey2
                pts[2 * k + 1] = cy + vy2 * ex2 + uy * ey2
            }
            out.prims.add(Prim(1, tone, pts))
        }

        /** Shade underneath a limb and, if [hi] is given, a thin lit edge on top: both hug the outline. */
        fun limbForm(a: Pt, ra: Float, b: Pt, rb: Float, shade: Int, hi: Int = -1, t0: Float = 0f, t1: Float = 1f) {
            crescent(wx(a), a.y, ra, wx(b), b.y, rb, shade, 0.5f, t0, t1)
            if (hi >= 0) crescent(wx(a), a.y, ra, wx(b), b.y, rb, hi, 0.2f, t0, t1, lit = true)
        }

        fun leg(hipJoint: Pt, knee: Pt, ankle: Pt, pitch: Float, tone: Int, shade: Int, boot: Int, near: Boolean) {
            val hxw = wx(hipJoint); val kxw = wx(knee); val axw = wx(ankle)
            // Thigh and shin, fuller where the muscle is, narrowing to the knee and the ankle.
            limb(hipJoint, thigh, knee, 8.6f * lk, tone)
            limb(knee, 8.6f * lk, ankle, 6.2f * lk, tone)
            // The calf bulges a little below the knee.
            run {
                val cx = kxw + (axw - kxw) * 0.30f
                val cy = knee.y + (ankle.y - knee.y) * 0.30f
                dot(cx, cy, 8.0f * lk, tone)
            }
            if (near) {
                // Thigh and shin in shade underneath, lit along the top, so the leg reads as round.
                limbForm(hipJoint, thigh, knee, 8.6f * lk, Tone.PANTS_DEEP, Tone.PANTS_HI)
                limbForm(knee, 8.6f * lk, ankle, 6.2f * lk, Tone.PANTS_DEEP, Tone.PANTS_HI, 0f, 0.8f)
                // A harness leg loop that goes round the top of the thigh.
                wrapBand(hxw, hipJoint.y, thigh, kxw, knee.y, 8.6f * lk, 0.30f, 3.0f, Tone.GEAR)
            }
            // Knee pad, a little forward of the joint.
            run {
                val mx = (hxw + axw) / 2f; val my = (hipJoint.y + ankle.y) / 2f
                val fx = kxw - mx; val fy = knee.y - my
                val fl = hypot(fx, fy).coerceAtLeast(1e-3f)
                ellipse(kxw + fx / fl * 3.0f, knee.y + fy / fl * 3.0f, 4.4f, 6.0f, axw - hxw, ankle.y - hipJoint.y, shade)
            }
            // Gaiter over the top of the boot, and the boot itself.
            run {
                val dx = kxw - axw; val dy = knee.y - ankle.y
                val l = hypot(dx, dy).coerceAtLeast(1e-3f)
                val gx = axw + dx / l * 15f; val gy = ankle.y + dy / l * 15f
                taper(axw, ankle.y, 6.8f, gx, gy, 6.6f, Tone.GAITER)
            }
            drawBoot(c, ankle, pitch, boot, out)
        }

        /**
         * A hand: a fist when [grip] is high, an open mitt with fingers reaching when low, always with a thumb. [dirX]/[dirY]
         * is the way the forearm points.
         */
        fun hand(h: Pt, dirX: Float, dirY: Float, grip: Float, tone: Int) {
            val x = wx(h); val y = h.y
            val len = hypot(dirX, dirY).coerceAtLeast(1e-3f)
            val dx = dirX / len; val dy = dirY / len
            val open = (1f - grip).coerceIn(0f, 1f)
            // Open fingers reach on past the palm.
            if (open > 0.15f) {
                val fx = x + dx * (5f + 7f * open); val fy = y + dy * (5f + 7f * open)
                taper(x, y, 5.0f, fx, fy, 3.6f - 0.6f * open, tone)
            }
            // Thumb on the upper side: its outline first, then the palm over it, then the thumb in the mitt's colour,
            // so the two read as one mitt with one outline.
            var nx = -dy; var ny = dx
            if (ny > 0f) { nx = -nx; ny = -ny }
            val tx2 = x + nx * (4.2f + 1.2f * open) + dx * (1.5f + 3f * open)
            val ty2 = y + ny * (4.2f + 1.2f * open) + dy * (1.5f + 3f * open)
            out.prims.add(Prim(0, Tone.INK, floatArrayOf(tx2, ty2, 2.4f + HALO)))
            circle(x, y, 5.1f + 0.7f * grip, tone)
            dot(tx2, ty2, 2.4f, tone)
            dot(x - nx * 1.4f - dx * 1.2f, y - ny * 1.4f - dy * 1.2f, 1.6f, Tone.GLOVE_HI)
        }

        fun arm(shoulder: Pt, elbow: Pt, handPt: Pt, grip: Float, tone: Int, shade: Int, glove: Int) {
            val sx = wx(shoulder); val ex = wx(elbow); val hx = wx(handPt)
            // Puffy sleeves: wide at the shoulder, narrowing to the wrist.
            limb(shoulder, 8.4f * ak, elbow, 7.0f * ak, tone)
            limb(elbow, 7.0f * ak, handPt, 5.6f * ak, tone)
            // The near sleeve (the far one is already a darker tone): shaded underneath, lit along the top, with a lime
            // band that goes round the upper arm and picks it out against the jacket (the accent of the zip and gaiter).
            if (shade == Tone.SLEEVE_SHADE) {
                limbForm(shoulder, 8.4f * ak, elbow, 7.0f * ak, Tone.JACKET_DEEP, Tone.SHINE)
                limbForm(elbow, 7.0f * ak, handPt, 5.6f * ak, Tone.JACKET_DEEP, Tone.SHINE, 0f, 0.85f)
                wrapBand(sx, shoulder.y, 8.4f * ak, ex, elbow.y, 7.0f * ak, 0.46f, 3.4f, Tone.ACCENT)
            }
            // A cuff near the wrist, and the glove.
            run {
                val fdx = hx - ex; val fdy = handPt.y - elbow.y
                wrapBand(ex, elbow.y, 7.0f * ak, hx, handPt.y, 5.6f * ak, 0.80f, 2.8f, Tone.GEAR, 0.18f)
                hand(handPt, fdx, fdy, grip, glove)
            }
        }

        // ---- Far side first ----
        arm(p.shoulderB, p.elbowB, p.handB, p.gripB, Tone.JACKET_FAR, Tone.JACKET_FAR, Tone.GLOVE)
        leg(p.hipB, p.kneeB, p.ankleB, p.pitchB, Tone.PANTS_FAR, Tone.PANTS_FAR, Tone.BOOT, near = false)

        // ---- Backpack with a bedroll on top. It hangs off the shoulders, so it swings a little behind each step. ----
        val pl = p.lean - p.pack
        val ptx = sin(pl); val pty = -cos(pl); val pnx = cos(pl); val pny = sin(pl)
        // Swing about the shoulder straps (60 units up the back), not the hips.
        val pivot = Pt(p.hip.x + tx * 60f, p.hip.y + ty * 60f)
        fun rp(a: Float, b: Float): Pt {
            val x = pivot.x + ptx * (b - 60f) - pnx * a
            val y = pivot.y + pty * (b - 60f) - pny * a
            return Pt(x, y)
        }
        fun rpw(a: Float, b: Float): Pair<Float, Float> = rp(a, b).let { wx(it) to it.y }
        // The ice axe lashed to the back of the pack, its head sticking up over the top.
        run {
            val b0 = rpw(31f, 12f); val b1 = rpw(31f, 66f)
            val h1 = rpw(25f, 72f); val h2 = rpw(37f, 70f)
            inked(Tone.METAL, 2.6f, b0.first, b0.second, b1.first, b1.second)
            inked(Tone.GEAR, 2.6f, h1.first, h1.second, b1.first, b1.second, h2.first, h2.second)
        }
        // The rucksack: a rounded body with a lid, wider at the bottom, a side pocket and straps.
        run {
            val body = FloatArray(16)
            val pts = arrayOf(
                rpw(5f, 10f), rpw(18f, 8f), rpw(29f, 14f), rpw(31f, 30f),
                rpw(29f, 46f), rpw(23f, 55f), rpw(10f, 55f), rpw(3f, 48f),
            )
            for (i in pts.indices) { body[2 * i] = pts[i].first; body[2 * i + 1] = pts[i].second }
            blob(body, Tone.PACK)
            // The lid.
            val lid = FloatArray(12)
            val lp = arrayOf(rpw(4f, 44f), rpw(14f, 41f), rpw(27f, 43f), rpw(25f, 54f), rpw(14f, 58f), rpw(5f, 53f))
            for (i in lp.indices) { lid[2 * i] = lp[i].first; lid[2 * i + 1] = lp[i].second }
            blob(lid, Tone.PACK_LID, halo = 1.6f)
            // Side pocket and the shaded back of the pack.
            val pk = FloatArray(10)
            val kp = arrayOf(rpw(23f, 14f), rpw(31f, 16f), rpw(31f, 30f), rpw(24f, 31f), rpw(21f, 22f))
            for (i in kp.indices) { pk[2 * i] = kp[i].first; pk[2 * i + 1] = kp[i].second }
            blob(pk, Tone.PACK_SHADE, halo = 0f)
            // Rounded shade under the top of the pack, where the light doesn't reach.
            val dp = arrayOf(rpw(7f, 12.5f), rpw(18f, 10.5f), rpw(26.5f, 15.5f), rpw(27f, 23f), rpw(14f, 21f), rpw(6f, 23f))
            val dpa = FloatArray(12)
            for (i in dp.indices) { dpa[2 * i] = dp[i].first; dpa[2 * i + 1] = dp[i].second }
            blob(dpa, Tone.PACK_DEEP, halo = 0f)
            val s1 = rpw(29f, 38f); val s2 = rpw(4f, 38f)
            stroke(Tone.GEAR, 2.4f, s1.first, s1.second, s2.first, s2.second)
        }
        val roll = rp(19f, 64f)
        circle(wx(roll), roll.y, 8.5f, Tone.ROLL)
        ring(wx(roll), roll.y, 4.2f, Tone.PACK_SHADE, 1.4f)
        // A coil of spare rope strapped to the pack.
        val coil = rp(21f, 28f)
        ring(wx(coil), coil.y, 8.5f, Tone.ROPE, 2.6f)
        ring(wx(coil), coil.y, 5.2f, Tone.ROPE, 2.2f)

        // ---- The hood, bunched behind the neck ----
        run {
            val h = body(-13f, 62f)
            circle(wx(h), h.y, 8.2f, Tone.HOOD)
            val h2 = body(-8f, 69f)
            dot(wx(h2), h2.y, 4.2f, Tone.HOOD)
        }

        // ---- Her seat and hips: trousers rounding out behind and below the jacket hem ----
        if (fem) {
            blob(
                wflat(body(8f, -2f), body(-6f, 0f), body(-15.5f, -3f), body(-18.5f, -9f), body(-16f, -15.5f), body(-8f, -19f), body(3f, -17.5f), body(9f, -10f)),
                Tone.PANTS,
            )
            blob(
                wflat(body(-9f, -8f), body(-16.8f, -6f), body(-17.4f, -11f), body(-14.5f, -15.5f), body(-9f, -17.8f), body(-5f, -13f)),
                Tone.PANTS_SHADE, halo = 0f,
            )
        }

        // ---- Torso: a puffy jacket, narrow at the waist and full at the chest ----
        run {
            val pts = if (fem) arrayOf(
                tb(12.4f, -3f), tb(13.2f, 5f), tb(13.6f, 12f), tb(12.2f, 28f), tb(13.6f, 40f), tb(14.8f, 47f), tb(13.6f, 54f), tb(13.2f, 60f),
                tb(0f, 66f),
                tb(-12.4f, 60f), tb(-13.2f, 54f), tb(-13.6f, 46f), tb(-11.6f, 28f), tb(-12.8f, 12f), tb(-13.2f, 5f), tb(-12.4f, -3f),
                tb(0f, -6f),
            ) else arrayOf(
                tb(12.4f, -3f), tb(13.6f, 12f), tb(12.2f, 28f), tb(14.8f, 46f), tb(13.2f, 60f),
                tb(0f, 66f),
                tb(-12.4f, 60f), tb(-13.6f, 46f), tb(-11.6f, 28f), tb(-12.8f, 12f), tb(-12.2f, -3f),
                tb(0f, -6f),
            )
            val arr = FloatArray(pts.size * 2)
            for (i in pts.indices) { arr[2 * i] = wx(pts[i]); arr[2 * i + 1] = pts[i].y }
            blob(arr, Tone.JACKET)
        }
        run {
            // Shade that hugs the back edge of the jacket (the side away from the slope) and a rounded zone along the hem
            // (the light comes from above), the baffles curving across the body, and a lime zip down the front.
            val ey = floatArrayOf(-3f, 12f, 28f, 46f, 60f)
            val ex = floatArrayOf(-12.2f, -12.8f, -11.6f, -13.6f, -12.4f)
            fun backEdge(y: Float): Float {
                for (i in 0 until ey.size - 1) {
                    if (y >= ey[i] && y <= ey[i + 1]) return ex[i] + (ex[i + 1] - ex[i]) * ((y - ey[i]) / (ey[i + 1] - ey[i]))
                }
                return ex[ex.size - 1]
            }
            run {
                val steps = 10
                val shade = FloatArray(2 * (2 * steps))
                for (i in 0..steps) {
                    val y = 6f + 50f * i / steps
                    val o = tb(backEdge(y) + 1.0f, y)
                    shade[2 * i] = wx(o); shade[2 * i + 1] = o.y
                    if (i in 1 until steps) {
                        val q = tb(backEdge(y) + 1.0f + 5.4f * Math.pow(sin(PI * i / steps), 0.8).toFloat(), y)
                        val j = 2 * steps - i
                        shade[2 * j] = wx(q); shade[2 * j + 1] = q.y
                    }
                }
                blob(shade, Tone.JACKET_DEEP, halo = 0f)
            }
            val hz = arrayOf(tb(-11.2f, -1f), tb(11.4f, -1f), tb(12.8f, 7f), tb(8f, 12.5f), tb(0f, 13.8f), tb(-8f, 12.5f), tb(-12.3f, 7f))
            val hza = FloatArray(hz.size * 2)
            for (i in hz.indices) { hza[2 * i] = wx(hz[i]); hza[2 * i + 1] = hz[i].y }
            blob(hza, Tone.JACKET_DEEP, halo = 0f)
            for (bb in floatArrayOf(22f, 36f, 50f)) {
                val pts = FloatArray(18)
                for (k in 0..8) {
                    val sv = -1f + 2f * k / 8f
                    val q = tb(-11.2f + 22.4f * k / 8f, bb - 1.8f * (1f - sv * sv))
                    pts[2 * k] = wx(q); pts[2 * k + 1] = q.y
                }
                stroke(Tone.JACKET_DEEP, 1.2f, *pts)
            }
            val z1 = tb(10f, 8f); val z2 = tb(12.5f, 58f)
            stroke(Tone.ACCENT, 2.4f, wx(z1), z1.y, wx(z2), z2.y)
            // Angular high-visibility chest panel: a bold LockIn lime shape, inset from the seams.
            val panel = arrayOf(tb(4.2f, 20f), tb(10.0f, 22f), tb(11.0f, 42f), tb(8.8f, 51f), tb(5.0f, 47f), tb(3.4f, 31f))
            val pa = FloatArray(panel.size * 2)
            for (i in panel.indices) { pa[2 * i] = wx(panel[i]); pa[2 * i + 1] = panel[i].y }
            blob(pa, Tone.ACCENT, halo = 0f)
            val seamA = tb(4.4f, 23f); val seamB = tb(8.2f, 45f)
            stroke(Tone.SHINE, 0.75f, wx(seamA), seamA.y, wx(seamB), seamB.y)
            // Reflective shoulder tab catches the light without breaking the dark silhouette.
            val tabA = tb(-9.8f, 54f); val tabB = tb(-5.5f, 57f)
            stroke(Tone.SHINE, 1.5f, wx(tabA), tabA.y, wx(tabB), tabB.y)
            if (fem) {
                // The bust: a shaded underside that follows the curve, and a soft light on top of it, so the chest reads
                // as a form under the jacket.
                blob(
                    wflat(tb(12.6f, 38.5f), tb(14.6f, 42.5f), tb(14.8f, 46.5f), tb(11.8f, 46f), tb(9.4f, 43f), tb(9.2f, 39.5f)),
                    Tone.JACKET_DEEP, halo = 0f,
                )
                val bl = tb(12.6f, 51.5f)
                out.prims.add(Prim(3, Tone.SHINE, floatArrayOf(wx(bl), bl.y, 5.2f)))
            }
            // The hem.
            val m1 = tb(-12.6f, -1f); val m2 = tb(12.8f, -1f)
            stroke(Tone.GEAR, 3.6f, wx(m1), m1.y, wx(m2), m2.y)
        }

        // ---- Harness: waist belt and shoulder strap ----
        val beltBack = if (fem) 16f else 13f
        val b1 = Pt(p.hip.x + tx * 6f - cnx * beltBack, p.hip.y + ty * 6f - cny * beltBack)
        val b2 = Pt(p.hip.x + tx * 6f + cnx * 13f, p.hip.y + ty * 6f + cny * 13f)
        stroke(Tone.GEAR, 5.5f, wx(b1), b1.y, wx(b2), b2.y)
        // The buckle and a carabiner on the belt.
        run {
            val bk = body(3f, 6f)
            dot(wx(bk), bk.y, 2.2f, Tone.METAL)
            val cb = body(-9f, 4f)
            ring(wx(cb), cb.y, 2.6f, Tone.METAL, 1.2f)
        }
        val s1 = Pt(p.shoulder.x - cnx * 11f + tx * 2f, p.shoulder.y - cny * 11f + ty * 2f)
        val s2 = Pt(p.shoulder.x + cnx * 14f - tx * 2f, p.shoulder.y + cny * 14f - ty * 2f)
        val s3 = Pt(p.hip.x + tx * 16f + cnx * 13.5f, p.hip.y + ty * 16f + cny * 13.5f)
        stroke(Tone.GEAR, 3.4f, wx(s1), s1.y, wx(s2), s2.y, wx(s3), s3.y)
        val anchor = ropeAnchor(c, p)
        out.prims.add(Prim(0, Tone.INK, floatArrayOf(anchor.x, anchor.y, 5.8f)))
        out.prims.add(Prim(0, Tone.ROPE, floatArrayOf(anchor.x, anchor.y, 3.6f)))

        // ---- Near leg ----
        leg(p.hipA, p.kneeA, p.ankleA, p.pitchA, Tone.PANTS, Tone.PANTS_SHADE, Tone.BOOT, near = true)

        // ---- Head: neck, face, helmet and goggles ----
        // The head is drawn as one smooth profile (cranium, brow, nose, lips, chin, jaw) a touch larger than the rig's
        // head circle, so the figure reads chunky and friendly instead of a ball on a stalk.
        val hx = wx(p.head)
        val hy = p.head.y
        val up = atan2(p.head.x - p.shoulder.x, -(p.head.y - p.shoulder.y)) // angle from vertical
        // Local x flips for the climber who walks left.
        val vx = sin(up) * c.dir
        val vy = -cos(up)
        val fx = cos(up) * c.dir
        val fy = sin(up)
        val sxw = wx(p.shoulder)
        val headK = 1.10f
        /** A point on the head: [a] forward from its centre, [b] up from it (the head's own frame, tipped with the gaze). */
        fun hp(a: Float, b: Float) = Pt(hx + headK * (fx * a + vx * b), hy + headK * (fy * a + vy * b))
        fun flat(vararg q: Pt): FloatArray {
            val o = FloatArray(q.size * 2)
            for (i in q.indices) { o[2 * i] = q[i].x; o[2 * i + 1] = q[i].y }
            return o
        }
        fun flatL(q: List<Pt>): FloatArray {
            val o = FloatArray(q.size * 2)
            for (i in q.indices) { o[2 * i] = q[i].x; o[2 * i + 1] = q[i].y }
            return o
        }
        fun line(tone: Int, w: Float, vararg q: Pt) = out.prims.add(Prim(2, tone, flat(*q), w))
        fun poly(tone: Int, vararg q: Pt) = out.prims.add(Prim(1, tone, flat(*q)))
        fun sblob(tone: Int, halo: Float, vararg q: Pt) = blob(flat(*q), tone, halo)
        fun dotAt(q: Pt, r: Float, tone: Int) = dot(q.x, q.y, r, tone)
        /** A soft-edged disc: blush, light on the cheek. It fades out, so it blends instead of sitting on the face like a sticker. */
        fun soft(q: Pt, r: Float, tone: Int) = out.prims.add(Prim(3, tone, floatArrayOf(q.x, q.y, r)))
        /** A smooth ribbon around the spine [sp] with half-width [rd] at each point. */
        fun ribbon(sp: List<Pt>, rd: FloatArray, tone: Int, halo: Float) {
            val n = sp.size
            val l = ArrayList<Pt>(n)
            val r = ArrayList<Pt>(n)
            for (i in 0 until n) {
                val a = sp[maxOf(i - 1, 0)]; val b = sp[minOf(i + 1, n - 1)]
                val dx = b.x - a.x; val dy = b.y - a.y
                val len = hypot(dx, dy).coerceAtLeast(1e-3f)
                val nx = -dy / len; val ny = dx / len
                l.add(Pt(sp[i].x + nx * rd[i], sp[i].y + ny * rd[i]))
                r.add(Pt(sp[i].x - nx * rd[i], sp[i].y - ny * rd[i]))
            }
            r.reverse()
            blob(flatL(l + r), tone, halo)
        }

        // The neck sits in the shade under the jaw. The gaiter is a rolled band in the accent colour with a lit lip, and
        // its tail streams behind the climber in the same wind as the flag.
        taper(sxw, p.shoulder.y, 6.6f, hx - fx * 1.5f, hy - fy * 1.5f, 6.2f, Tone.SKIN_DEEP)
        run {
            val gx = sxw + (hx - sxw) * 0.52f; val gy = p.shoulder.y + (hy - p.shoulder.y) * 0.52f
            taper(sxw, p.shoulder.y, 8.8f, gx, gy, 8.0f, Tone.ACCENT)
            stroke(Tone.INK, 0.9f, gx - fx * 7.6f, gy - fy * 7.6f + 0.8f, gx + fx * 7.6f, gy + fy * 7.6f + 0.8f)
            stroke(Tone.SHINE, 0.8f, gx - fx * 5f, gy - fy * 5f - 1.9f, gx + fx * 3f, gy + fy * 3f - 1.9f)
            val bx0 = sxw + (hx - sxw) * 0.30f - fx * 6.5f
            val by0 = p.shoulder.y + (hy - p.shoulder.y) * 0.30f - fy * 6.5f
            val ph = p.clock * 6.3f + (if (c.dir > 0f) 0f else 1.4f)
            val back = -c.dir
            val tail = ArrayList<Pt>(6)
            for (i in 0..5) tail.add(Pt(bx0 + back * i * 4.4f + 0.8f * i, by0 + i * 1.3f + sin(ph - i * 0.95f) * (0.5f + 0.75f * i)))
            ribbon(tail, floatArrayOf(3.2f, 3.0f, 2.5f, 1.9f, 1.2f, 0.5f), Tone.ACCENT, HALO)
            stroke(Tone.SHINE, 0.8f, tail[0].x, tail[0].y - 1.4f, tail[2].x, tail[2].y - 1.2f)
        }

        // A ponytail out of the back of the helmet (drawn before the head, so the helmet covers its root): one smooth
        // shape that tapers and swings with the stride, a lit ribbon along its top and a lime tie at the root.
        if (fem) {
            val ph = p.clock * 6.3f + 0.8f
            val n = 8
            val sp = ArrayList<Pt>(n)
            for (i in 0 until n) {
                sp.add(if (i == 0) hp(-12f, 2.5f) else hp(-11.5f - 4.0f * i, 4f + 1.9f * i - 0.7f * i * i + sin(ph - i * 0.8f) * (0.4f + 0.8f * i)))
            }
            val rad = floatArrayOf(2.5f, 3.4f, 3.7f, 3.4f, 2.8f, 2.0f, 1.2f, 0.4f)
            ribbon(sp, FloatArray(n) { rad[it] * headK * 0.95f }, Tone.HAIR, HALO)
            val rib = FloatArray(2 * (n - 2))
            for (i in 1 until n - 1) {
                val a = sp[maxOf(i - 1, 0)]; val b = sp[minOf(i + 1, n - 1)]
                val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-3f)
                val nx = -(b.y - a.y) / len; val ny = (b.x - a.x) / len
                rib[2 * (i - 1)] = sp[i].x + nx * rad[i] * headK * 0.43f
                rib[2 * (i - 1) + 1] = sp[i].y + ny * rad[i] * headK * 0.43f
            }
            stroke(Tone.HAIR_HI, 1.2f, *rib)
            dotAt(sp[1], 2.6f, Tone.INK)
            dotAt(sp[1], 1.8f, Tone.ACCENT)
        }

        // ---- Head silhouette, then the shade under the jaw, the cheekbone light and the blush ----
        sblob(
            Tone.FACE, HALO,
            hp(-13f, 3f), hp(-10.5f, 11f), hp(-2f, 14.5f), hp(8f, 13f), hp(13.2f, 7f),
            hp(14.3f, 1.5f), hp(15.6f, -1.2f), hp(18.0f, -4.2f), hp(15.0f, -6.0f), // brow, nose bridge, tip, under the nose
            hp(14.2f, -8.0f), hp(14.6f, -10.2f), hp(13.2f, -12.2f),                // lips
            hp(9.5f, -14.8f), hp(3f, -15.2f), hp(-4f, -13.2f), hp(-10.5f, -8f), hp(-14f, -2f),
        )
        // Shade along the jaw and under the chin: it hugs the outline and tapers away at both ends, like the limb shading.
        sblob(
            Tone.FACE_SHADE, 0f,
            hp(-12.9f, -2.8f), hp(-10.0f, -8.3f), hp(-4.0f, -12.5f), hp(3.0f, -14.2f), hp(9.2f, -13.7f),
            hp(5.0f, -11.4f), hp(-1.5f, -10.0f), hp(-7.2f, -6.8f), hp(-11.2f, -2.0f),
        )
        // Soft light on the cheekbone, a soft blush below it, and a glint on the tip of the nose.
        soft(hp(6.6f, -3.6f), 5.2f, Tone.SKIN_HI)
        soft(hp(8.0f, -7.6f), 4.6f, Tone.CHEEK)
        soft(hp(16.4f, -3.9f), 1.7f, Tone.SKIN_HI)
        line(Tone.LIP, 0.8f, hp(15.6f, -5.2f), hp(14.7f, -5.7f))
        // The ear, tucked under the helmet rim: an ear-shaped form with a shaded hollow, not a disc.
        sblob(Tone.FACE, 1.8f, hp(-4.4f, -0.6f), hp(-7.4f, -0.4f), hp(-9.0f, -3.2f), hp(-8.0f, -6.6f), hp(-5.0f, -6.8f), hp(-3.8f, -3.6f))
        sblob(Tone.FACE_SHADE, 0f, hp(-5.4f, -2.0f), hp(-7.2f, -2.3f), hp(-7.5f, -4.6f), hp(-5.8f, -5.3f))
        // Lips: one smooth shape, a soft light on the lower lip, and a small crease at the corner of the smile.
        sblob(Tone.LIP, 0f, hp(9.6f, -10.2f), hp(11.8f, -10.9f), hp(14.3f, -10.1f), hp(13.6f, -11.6f), hp(11.8f, -12.2f), hp(10.0f, -11.3f))
        line(Tone.SKIN_DEEP, 0.7f, hp(9.9f, -10.9f), hp(12.2f, -11.3f), hp(14.1f, -10.3f))
        soft(hp(11.9f, -11.8f), 1.9f, Tone.SKIN_HI)
        line(Tone.FACE_SHADE, 0.8f, hp(8.9f, -9.2f), hp(8.4f, -10.3f), hp(8.9f, -11.2f))

        // ---- Eye: an almond shape under a real upper lid (the lid covers the top of the iris), with a lash line ----
        run {
            sblob(Tone.EYE_W, 1.25f, hp(6.6f, -1.2f), hp(8.4f, 0.3f), hp(11.6f, 0.8f), hp(13.6f, -0.7f), hp(12.4f, -3.4f), hp(9.2f, -4.0f))
            dotAt(hp(10.8f, -1.4f), 2.5f, Tone.IRIS)
            soft(hp(10.9f, -2.7f), 1.7f, Tone.IRIS_HI)
            dotAt(hp(11.1f, -1.4f), 1.3f, Tone.INK)
            dotAt(hp(11.9f, -0.3f), 0.95f, Tone.SHINE)
            dotAt(hp(10.0f, -2.7f), 0.45f, Tone.SHINE)
            // The upper lid, in skin, over the top of the eye.
            poly(Tone.FACE, hp(6.0f, -0.7f), hp(8.4f, 1.1f), hp(11.4f, 1.6f), hp(14.0f, 0.2f), hp(14.4f, 4.6f), hp(6.0f, 4.6f))
            line(Tone.INK, 1.7f, hp(6.2f, -0.5f), hp(8.6f, 1.1f), hp(11.6f, 1.6f), hp(14.0f, 0.3f))
            if (fem) line(Tone.INK, 1.0f, hp(13.6f, 0.5f), hp(15.3f, 1.9f))
            // A brow that is thick in the middle and tapers at both ends.
            sblob(
                if (fem) Tone.HAIR_DEEP else Tone.INK, 0f,
                hp(7.0f, 3.3f), hp(9.8f, 4.7f), hp(13.6f, 4.7f), hp(13.8f, 3.7f), hp(10.2f, 3.5f), hp(7.4f, 2.5f),
            )
        }

        // ---- Hair that shows under the helmet at the nape, and a fringe tuft for her ----
        if (!fem) {
            sblob(Tone.HAIR, 1.8f, hp(-14.4f, -0.5f), hp(-13.6f, -7f), hp(-10.5f, -10.6f), hp(-8.6f, -8.2f), hp(-10.6f, -3.2f), hp(-12.6f, 1.6f))
            poly(Tone.HAIR_HI, hp(-13.2f, -3.5f), hp(-11.8f, -7.6f), hp(-10.6f, -5.4f))
        } else {
            sblob(Tone.HAIR, 1.6f, hp(-14.2f, 0.5f), hp(-13.4f, -6f), hp(-10.2f, -9.6f), hp(-8.4f, -7f), hp(-10.4f, -2f), hp(-12.6f, 1.8f))
            sblob(Tone.HAIR, 1.4f, hp(9.2f, 5.8f), hp(12.8f, 5.0f), hp(14.4f, 2.4f), hp(11.2f, 3.2f))
        }

        // ---- Chin strap along the jaw, with a small buckle ----
        run {
            val c0 = hp(-3.2f, -6.4f); val c1 = hp(-1.2f, -11.4f); val c2 = hp(3.2f, -13.4f)
            line(Tone.INK, 1.9f, c0, c1, c2)
            line(Tone.GEAR, 1.1f, c0, c1, c2)
            dotAt(hp(-1.0f, -11.6f), 1.25f, Tone.METAL)
        }

        // ---- Helmet: a smooth dome with a lime stripe, vents, a rim band, the brim and a rear dial ----
        blob(
            flat(
                hp(-15.2f, -6f), hp(-17.2f, 3f), hp(-14.6f, 11f), hp(-6f, 17.6f), hp(4f, 19.4f), hp(12.8f, 16.4f), hp(18.6f, 10.6f),
                hp(23.6f, 8.6f), hp(22.4f, 5.4f), hp(10f, 4.8f), hp(0f, 3.6f), hp(-8.5f, 0.4f), hp(-13f, -3.4f),
            ),
            Tone.HELMET,
        )
        // The shaded lower back of the shell and the underside of the brim.
        poly(Tone.HELMET_SHADE, hp(-15.2f, -6f), hp(-17.2f, 3f), hp(-14.6f, 11f), hp(-9.5f, 14.2f), hp(-10.8f, 6f), hp(-12.2f, -0.6f), hp(-13f, -4.8f))
        poly(Tone.HELMET_SHADE, hp(23.6f, 8.6f), hp(22.4f, 5.4f), hp(10f, 4.8f), hp(10.5f, 7f), hp(18f, 7.6f))
        // A darker rim band along the lower edge, with the lime stripe above it (the stripe follows the theme's accent).
        line(Tone.HELMET_RIM, 2.2f, hp(20.8f, 6.8f), hp(10f, 5.4f), hp(0f, 4.2f), hp(-8f, 1.0f), hp(-13f, -3.0f))
        line(Tone.ACCENT, 2.0f, hp(18.4f, 8.6f), hp(9f, 7.0f), hp(-0.5f, 6.0f), hp(-8.2f, 3.4f), hp(-14f, -1.4f))
        // Vents over the crown.
        line(Tone.HELMET_SHADE, 2.3f, hp(-1f, 15.2f), hp(4.4f, 15.6f))
        line(Tone.HELMET_SHADE, 2.3f, hp(-7f, 13.6f), hp(-1.8f, 14.2f))
        line(Tone.HELMET_SHADE, 2.3f, hp(6.4f, 14.0f), hp(10.6f, 12.6f))
        // The sheen: a bright crescent on the upper front (the light comes from above and ahead), and a spot on the back.
        poly(Tone.SHINE, hp(-1.5f, 17.4f), hp(5.5f, 17.4f), hp(12f, 14.6f), hp(14.2f, 12.4f), hp(8.4f, 14.6f), hp(2f, 15.8f))
        dotAt(hp(-9.5f, 13.2f), 1.4f, Tone.SHINE)
        // The rear tension dial.
        run {
            val d = hp(-15f, 5f)
            dot(d.x, d.y, 2.6f, Tone.INK)
            dot(d.x, d.y, 1.7f, Tone.METAL)
        }

        // ---- Goggles pushed up on the helmet: a strap round the shell, a dark frame and a tinted lens with a reflection ----
        run {
            line(Tone.GOGGLE, 2.2f, hp(11f, 9.6f), hp(1f, 9.2f), hp(-8f, 6.2f), hp(-15.2f, 2.2f))
            val g1 = hp(10.4f, 10.9f); val g2 = hp(2.4f, 11.4f)
            inked(Tone.GOGGLE, 6.6f, g1.x, g1.y, g2.x, g2.y)
            stroke(Tone.LENS, 4.6f, g1.x, g1.y, g2.x, g2.y)
            stroke(Tone.LENS_HI, 2.0f, g1.x - fx * 0.3f, g1.y - fy * 0.3f, g2.x, g2.y)
            val l0 = hp(8.6f, 11.9f); val l1 = hp(5.2f, 12.3f)
            line(Tone.SHINE, 1.0f, l0, l1)
        }

        // ---- An ice axe in the near hand: the shaft runs on from the forearm, and the pick bites down into the
        // rock with the adze sticking up behind. ----
        run {
            val ex = wx(p.elbowA); val ey = p.elbowA.y
            val hx2 = wx(p.handA); val hy2 = p.handA.y
            val len = hypot(hx2 - ex, hy2 - ey).coerceAtLeast(1e-3f)
            val dx = (hx2 - ex) / len
            val dy = (hy2 - ey) / len
            val tail = floatArrayOf(hx2 - dx * 9f, hy2 - dy * 9f)
            val headX = hx2 + dx * 24f
            val headY = hy2 + dy * 24f
            // Perpendicular, picked so the pick points down the slope towards the ground.
            var px = -dy
            var py = dx
            if (py < 0f) { px = -px; py = -py }
            stroke(Tone.INK, 6.4f, tail[0], tail[1], headX, headY)
            stroke(Tone.METAL, 3.4f, tail[0], tail[1], headX, headY)
            stroke(Tone.ACCENT, 3.6f, hx2 - dx * 2f, hy2 - dy * 2f, hx2 + dx * 6f, hy2 + dy * 6f)
            val pickX = headX + px * 12f + dx * 3f
            val pickY = headY + py * 12f + dy * 3f
            val adzeX = headX - px * 8f
            val adzeY = headY - py * 8f
            stroke(Tone.INK, 6f, adzeX, adzeY, headX, headY, pickX, pickY)
            stroke(Tone.GEAR, 3.2f, adzeX, adzeY, headX, headY, pickX, pickY)
        }

        // ---- Near arm on top ----
        arm(p.shoulderA, p.elbowA, p.handA, p.gripA, Tone.JACKET, Tone.SLEEVE_SHADE, Tone.GLOVE)

        return out.prims
    }

    /** A closed Catmull-Rom curve through [pts] (x0, y0, x1, y1 ...), [sub] points per span. */
    private fun smoothClosed(pts: FloatArray, sub: Int): FloatArray {
        val n = pts.size / 2
        val out = FloatArray(n * sub * 2)
        var o = 0
        for (i in 0 until n) {
            val i0 = (i - 1 + n) % n; val i1 = i; val i2 = (i + 1) % n; val i3 = (i + 2) % n
            for (s in 0 until sub) {
                val t = s / sub.toFloat()
                val t2 = t * t; val t3 = t2 * t
                for (axis in 0..1) {
                    val p0 = pts[2 * i0 + axis]; val p1 = pts[2 * i1 + axis]; val p2 = pts[2 * i2 + axis]; val p3 = pts[2 * i3 + axis]
                    out[o + axis] = 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
                }
                o += 2
            }
        }
        return out
    }

    /** The closed polygon [pts] pushed outwards by [d] along its normals (a uniform outline). */
    private fun offsetPoly(pts: FloatArray, d: Float): FloatArray {
        val n = pts.size / 2
        var area = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[2 * i] * pts[2 * j + 1] - pts[2 * j] * pts[2 * i + 1]
        }
        val sgn = if (area >= 0f) 1f else -1f
        val o = FloatArray(pts.size)
        for (i in 0 until n) {
            val a = (i - 1 + n) % n; val b = (i + 1) % n
            val tx = pts[2 * b] - pts[2 * a]; val ty = pts[2 * b + 1] - pts[2 * a + 1]
            val l = hypot(tx, ty).coerceAtLeast(1e-3f)
            o[2 * i] = pts[2 * i] + sgn * ty / l * d
            o[2 * i + 1] = pts[2 * i + 1] - sgn * tx / l * d
        }
        return o
    }

    /**
     * A boot that follows the slope under the foot and rolls with the step: [pitch] tips it toes down (heel up at
     * push-off, toes up as it reaches forward). A sole, a gaiter-height cuff, and two crampon points that bite the
     * snow.
     */
    private fun drawBoot(c: ClimberSpec, ankle: Pt, pitch: Float, tone: Int, out: Out) {
        val g = Rig.groundGrade(c, ankle.x) + pitch
        val tx = cos(g)
        val ty = sin(g)
        // Up, away from the sole.
        val ux = sin(g)
        val uy = -cos(g)
        fun pt(fwd: Float, up: Float): Pair<Float, Float> {
            val lx = ankle.x + tx * fwd + ux * up
            val ly = ankle.y + ty * fwd + uy * up
            return c.worldX(lx) to ly
        }
        val outline = arrayOf(
            pt(-7.5f, -2f), pt(-6.8f, -8.6f), pt(-2f, -10f), pt(14f, -10f), pt(19f, -8f), pt(20.5f, -4.5f),
            pt(17f, -1f), pt(9.5f, 2f), pt(5.5f, 8f), pt(-5.5f, 8f),
        )
        val arr = FloatArray(outline.size * 2)
        for (i in outline.indices) { arr[2 * i] = outline[i].first; arr[2 * i + 1] = outline[i].second }
        val sm = smoothClosed(arr, 3)
        out.prims.add(Prim(1, Tone.INK, offsetPoly(sm, HALO)))
        // Crampon points first, so the sole sits over their roots.
        for (f0 in floatArrayOf(11f, 16.5f)) {
            val a = pt(f0, -9.5f); val b = pt(f0 + 2.2f, -9.5f); val tip = pt(f0 + 1.2f, -14.5f)
            out.prims.add(Prim(1, Tone.INK, floatArrayOf(a.first - 0.8f, a.second, b.first + 0.8f, b.second, tip.first, tip.second + 1.2f)))
            out.prims.add(Prim(1, Tone.METAL, floatArrayOf(a.first, a.second, b.first, b.second, tip.first, tip.second)))
        }
        out.prims.add(Prim(1, tone, sm))
        // The sole, a toe cap, and the laces.
        val s0 = pt(-6f, -8.4f); val s1 = pt(18.5f, -7.8f)
        out.prims.add(Prim(2, Tone.SOLE, floatArrayOf(s0.first, s0.second, s1.first, s1.second), 3.6f))
        val t0 = pt(15f, -5.5f); val t1 = pt(17.5f, -2.6f)
        out.prims.add(Prim(2, Tone.GAITER, floatArrayOf(t0.first, t0.second, t1.first, t1.second), 3.4f))
        val l0 = pt(7f, 0.5f); val l1 = pt(3.5f, 5.2f)
        out.prims.add(Prim(2, Tone.SHINE, floatArrayOf(l0.first, l0.second, l1.first, l1.second), 1.2f))
    }
}

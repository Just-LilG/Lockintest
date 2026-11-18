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
}

/** The colour of each [Tone], as ARGB. (The painter swaps ACCENT for the theme's accent.) */
internal val ClimberPalette = intArrayOf(
    0xFF0B0B0C.toInt(), // INK (outline)
    0xFFEAEAF3.toInt(), // JACKET
    0xFFC9C9D6.toInt(), // JACKET_FAR
    0xFF8F8FA0.toInt(), // PANTS
    0xFF6E6E7E.toInt(), // PANTS_FAR
    0xFF34343E.toInt(), // BOOT
    0xFF42424E.toInt(), // GLOVE
    0xFFB0B0C2.toInt(), // PACK
    0xFF9494A8.toInt(), // ROLL
    0xFFF8F8FB.toInt(), // HELMET
    0xFFBDBDCB.toInt(), // FACE
    0xFF3C3C48.toInt(), // GEAR
    0xFF2E2E38.toInt(), // GOGGLE
    0xFF9A9AA8.toInt(), // ROPE
    0xFFC6F432.toInt(), // ACCENT (LockIn lime)
    0xFFFFFFFF.toInt(), // SHINE
    0xFFDCDCE6.toInt(), // JACKET_SHADE
    0xFF6A6A78.toInt(), // METAL
    0xFF7A7A8A.toInt(), // PANTS_SHADE
    0xFF50505E.toInt(), // GAITER
    0xFF1C1C24.toInt(), // SOLE
    0xFF1B2230.toInt(), // LENS
    0xFFE6E6F0.toInt(), // HOOD
    0xFFD6D6E2.toInt(), // SLEEVE_SHADE
    0xFF5E5E6C.toInt(), // GLOVE_HI
    0xFFA6A6B8.toInt(), // FACE_SHADE
    0xFF8E8EA2.toInt(), // PACK_SHADE
    0xFFA2A2B6.toInt(), // PACK_LID
    0xFFABABBD.toInt(), // PANTS_HI (lit edge of the trousers)
    0xFFA6A6BC.toInt(), // JACKET_DEEP (jacket in shade)
    0xFF585868.toInt(), // PANTS_DEEP (trousers in shade)
    0xFFC9C9D8.toInt(), // HELMET_SHADE
    0xFF76768C.toInt(), // PACK_DEEP
)

/** One drawing primitive in scene (world) coordinates. */
internal class Prim(
    /** 0 = filled circle [x, y, r]; 1 = filled polygon [x0, y0, x1, y1 ...]; 2 = round-capped polyline. */
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

        /** A point on the torso: [a] forward from its centre line, [b] up from the hip. In the local frame. */
        fun body(a: Float, b: Float) = Pt(p.hip.x + tx * b + cnx * a, p.hip.y + ty * b + cny * a)

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
            val pts = FloatArray(2 * 17)
            for (k in 0..16) {
                val a = 2.0 * PI * k / 16.0
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
         * Form shading for a limb from ([ax], [ay], radius [ra]) to ([bx], [by], [rb]), between [t0] and [t1] along it:
         * a round-capped stroke on the side facing away from the light (always inside the limb's outline), and, if
         * [hi] is given, a thin highlight on the lit side.
         */
        fun form(ax: Float, ay: Float, ra: Float, bx: Float, by: Float, rb: Float, shade: Int, hi: Int = -1, t0: Float = 0f, t1: Float = 1f) {
            val dx = bx - ax
            val dy = by - ay
            val len = hypot(dx, dy)
            if (len < 1e-3f) return
            var nx = -dy / len
            var ny = dx / len
            if (nx * lightX + ny * lightY > 0f) { nx = -nx; ny = -ny } // n points away from the light
            val u0 = t0 + (t1 - t0) * 0.10f
            val u1 = t0 + (t1 - t0) * 0.90f
            val rr0 = ra + (rb - ra) * u0
            val rr1 = ra + (rb - ra) * u1
            val rm = (rr0 + rr1) / 2f
            val off = rm * 0.64f
            stroke(
                shade, rm * 0.62f,
                ax + dx * u0 + nx * off * rr0 / rm, ay + dy * u0 + ny * off * rr0 / rm,
                ax + dx * u1 + nx * off * rr1 / rm, ay + dy * u1 + ny * off * rr1 / rm,
            )
            if (hi >= 0) {
                val v0 = t0 + (t1 - t0) * 0.12f
                val v1 = t0 + (t1 - t0) * 0.88f
                val ha = ra + (rb - ra) * v0
                val hb = ra + (rb - ra) * v1
                stroke(
                    hi, 1.7f,
                    ax + dx * v0 - nx * ha * 0.80f, ay + dy * v0 - ny * ha * 0.80f,
                    ax + dx * v1 - nx * hb * 0.80f, ay + dy * v1 - ny * hb * 0.80f,
                )
            }
        }

        fun limbForm(a: Pt, ra: Float, b: Pt, rb: Float, shade: Int, hi: Int = -1, t0: Float = 0f, t1: Float = 1f) =
            form(wx(a), a.y, ra, wx(b), b.y, rb, shade, hi, t0, t1)

        fun leg(hipJoint: Pt, knee: Pt, ankle: Pt, pitch: Float, tone: Int, shade: Int, boot: Int, near: Boolean) {
            val hxw = wx(hipJoint); val kxw = wx(knee); val axw = wx(ankle)
            // Thigh and shin, fuller where the muscle is, narrowing to the knee and the ankle.
            limb(hipJoint, 11.4f, knee, 8.6f, tone)
            limb(knee, 8.6f, ankle, 6.2f, tone)
            // The calf bulges a little below the knee.
            run {
                val cx = kxw + (axw - kxw) * 0.30f
                val cy = knee.y + (ankle.y - knee.y) * 0.30f
                dot(cx, cy, 8.0f, tone)
            }
            if (near) {
                // Thigh and shin in shade underneath, lit along the top, so the leg reads as round.
                limbForm(hipJoint, 11.4f, knee, 8.6f, Tone.PANTS_DEEP, Tone.PANTS_HI)
                limbForm(knee, 8.6f, ankle, 6.2f, Tone.PANTS_DEEP, Tone.PANTS_HI, 0f, 0.8f)
                // A harness leg loop round the top of the thigh.
                val tdx = kxw - hxw; val tdy = knee.y - hipJoint.y
                val tl = hypot(tdx, tdy).coerceAtLeast(1e-3f)
                val lx = hxw + tdx / tl * 15f; val ly = hipJoint.y + tdy / tl * 15f
                stroke(Tone.GEAR, 2.4f, lx - tdy / tl * 9.4f, ly + tdx / tl * 9.4f, lx + tdy / tl * 9.4f, ly - tdx / tl * 9.4f)
            }
            // Knee pad, a little forward of the joint.
            run {
                val mx = (hxw + axw) / 2f; val my = (hipJoint.y + ankle.y) / 2f
                val fx = kxw - mx; val fy = knee.y - my
                val fl = hypot(fx, fy).coerceAtLeast(1e-3f)
                dot(kxw + fx / fl * 3.0f, knee.y + fy / fl * 3.0f, 4.2f, shade)
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
            limb(shoulder, 8.4f, elbow, 7.0f, tone)
            limb(elbow, 7.0f, handPt, 5.6f, tone)
            // The near sleeve (the far one is already a darker tone): shaded underneath, lit on top, with a lime band
            // round the upper arm that picks the arm out against the jacket (the same accent as the zip and gaiter).
            if (shade == Tone.SLEEVE_SHADE) {
                limbForm(shoulder, 8.4f, elbow, 7.0f, Tone.JACKET_DEEP, Tone.SHINE)
                limbForm(elbow, 7.0f, handPt, 5.6f, Tone.JACKET_DEEP, Tone.SHINE, 0f, 0.85f)
                val bx = sx + (ex - sx) * 0.46f; val by = shoulder.y + (elbow.y - shoulder.y) * 0.46f
                val ux = ex - sx; val uy = elbow.y - shoulder.y
                val ul = hypot(ux, uy).coerceAtLeast(1e-3f)
                val qx = -uy / ul; val qy = ux / ul
                stroke(Tone.ACCENT, 3.4f, bx - qx * 7f, by - qy * 7f, bx + qx * 7f, by + qy * 7f)
            }
            // A crease on the inside of the bend.
            run {
                val mx = (sx + hx) / 2f; val my = (shoulder.y + handPt.y) / 2f
                val ix = mx - ex; val iy = my - elbow.y
                val il = hypot(ix, iy)
                if (il > 3f && il < 48f) stroke(Tone.INK, 1.5f, ex + ix / il * 1.5f, elbow.y + iy / il * 1.5f, ex + ix / il * 6.4f, elbow.y + iy / il * 6.4f)
            }
            // A cuff near the wrist.
            run {
                val fdx = hx - ex; val fdy = handPt.y - elbow.y
                val fl = hypot(fdx, fdy).coerceAtLeast(1e-3f)
                val cx = ex + fdx * 0.80f; val cy = elbow.y + fdy * 0.80f
                stroke(Tone.GEAR, 2.8f, cx - fdy / fl * 5.2f, cy + fdx / fl * 5.2f, cx + fdy / fl * 5.2f, cy - fdx / fl * 5.2f)
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
            blob(pk, Tone.PACK_SHADE, halo = 1.2f)
            // In shade under the top of the pack and above the lid, where the light doesn't reach.
            val dp = arrayOf(rpw(5f, 11f), rpw(18f, 9f), rpw(28f, 15f), rpw(29f, 24f), rpw(14f, 22f), rpw(4f, 24f))
            val dpa = FloatArray(12)
            for (i in dp.indices) { dpa[2 * i] = dp[i].first; dpa[2 * i + 1] = dp[i].second }
            out.prims.add(Prim(1, Tone.PACK_DEEP, dpa))
            val sd = arrayOf(rpw(5f, 38f), rpw(26f, 40f), rpw(27f, 44f), rpw(5f, 43f))
            val sda = FloatArray(8)
            for (i in sd.indices) { sda[2 * i] = sd[i].first; sda[2 * i + 1] = sd[i].second }
            out.prims.add(Prim(1, Tone.PACK_DEEP, sda))
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

        // ---- Torso: a puffy jacket, narrow at the waist and full at the chest ----
        run {
            val pts = arrayOf(
                body(12.4f, -3f), body(13.6f, 12f), body(12.2f, 28f), body(14.8f, 46f), body(13.2f, 60f),
                body(0f, 66f),
                body(-12.4f, 60f), body(-13.6f, 46f), body(-11.6f, 28f), body(-12.8f, 12f), body(-12.2f, -3f),
                body(0f, -6f),
            )
            val arr = FloatArray(pts.size * 2)
            for (i in pts.indices) { arr[2 * i] = wx(pts[i]); arr[2 * i + 1] = pts[i].y }
            blob(arr, Tone.JACKET)
        }
        run {
            // The half of the jacket away from the slope in shade, a deeper zone along the hem (the light comes from
            // above), the baffles across it, and a lime zip down the front.
            val a = body(-8.6f, 6f); val b = body(-9.2f, 56f)
            stroke(Tone.JACKET_DEEP, 6.0f, wx(a), a.y, wx(b), b.y)
            val hz = arrayOf(body(-12.2f, -2f), body(12.4f, -2f), body(13.2f, 13f), body(6f, 17f), body(-3f, 15f), body(-12.6f, 14f))
            val hza = FloatArray(hz.size * 2)
            for (i in hz.indices) { hza[2 * i] = wx(hz[i]); hza[2 * i + 1] = hz[i].y }
            out.prims.add(Prim(1, Tone.JACKET_DEEP, hza))
            for (bb in floatArrayOf(22f, 36f, 50f)) {
                val q1 = body(-12f, bb); val q2 = body(12.6f, bb)
                stroke(Tone.JACKET_DEEP, 1.5f, wx(q1), q1.y, wx(q2), q2.y)
            }
            val z1 = body(10f, 8f); val z2 = body(12.5f, 58f)
            stroke(Tone.ACCENT, 2.4f, wx(z1), z1.y, wx(z2), z2.y)
            // Highlights on the shoulder and the chest, from the same light as the helmet.
            val h1 = body(-5f, 63.5f); val h2 = body(9f, 62.5f)
            stroke(Tone.SHINE, 2.0f, wx(h1), h1.y, wx(h2), h2.y)
            val c1 = body(8f, 40f); val c2 = body(9.4f, 54f)
            stroke(Tone.SHINE, 1.6f, wx(c1), c1.y, wx(c2), c2.y)
            // The hem.
            val m1 = body(-12.6f, -1f); val m2 = body(12.8f, -1f)
            stroke(Tone.GEAR, 3.6f, wx(m1), m1.y, wx(m2), m2.y)
        }

        // ---- Harness: waist belt and shoulder strap ----
        val b1 = Pt(p.hip.x + tx * 6f - cnx * 13f, p.hip.y + ty * 6f - cny * 13f)
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
        val hx = wx(p.head)
        val hy = p.head.y
        val up = atan2(p.head.x - p.shoulder.x, -(p.head.y - p.shoulder.y)) // angle from vertical
        // Local x flips for the climber who walks left.
        val vx = sin(up) * c.dir
        val vy = -cos(up)
        val fx = cos(up) * c.dir
        val fy = sin(up)
        val sxw = wx(p.shoulder)
        taper(sxw, p.shoulder.y, 6.8f, hx, hy, 6.4f, Tone.FACE)
        // The neck gaiter, in the accent colour, with its tail streaming in the wind (the flag blows to the right).
        run {
            val gx = sxw + (hx - sxw) * 0.58f; val gy = p.shoulder.y + (hy - p.shoulder.y) * 0.58f
            taper(sxw, p.shoulder.y, 8.6f, gx, gy, 8.2f, Tone.ACCENT)
            val bx0 = sxw + (hx - sxw) * 0.30f - fx * 6.5f
            val by0 = p.shoulder.y + (hy - p.shoulder.y) * 0.30f - fy * 6.5f
            // The tail trails behind the climber (the wind from the flag's side only nudges it), and ripples.
            val tail = FloatArray(10)
            tail[0] = bx0; tail[1] = by0
            val ph = p.clock * 6.3f + (if (c.dir > 0f) 0f else 1.4f)
            val back = -c.dir
            for (i in 1..4) {
                tail[2 * i] = bx0 + back * i * 4.8f + 0.9f * i
                tail[2 * i + 1] = by0 + i * 1.5f + sin(ph - i * 0.95f) * (0.6f + 0.8f * i)
            }
            inked(Tone.ACCENT, 4.2f, *tail)
        }
        /** A point on the head: [a] forward from its centre, [b] up from it (the head's own frame, tipped with the gaze). */
        fun hp(a: Float, b: Float) = Pt(hx + fx * a + vx * b, hy + fy * a + vy * b)
        fun flat(vararg q: Pt): FloatArray {
            val o = FloatArray(q.size * 2)
            for (i in q.indices) { o[2 * i] = q[i].x; o[2 * i + 1] = q[i].y }
            return o
        }

        // ---- Face: a profile with a nose, a jaw and a cheek in shade ----
        circle(hx, hy, Rig.HEAD_R - 0.5f, Tone.FACE)
        run {
            val n = hp(Rig.HEAD_R - 0.6f, -4.6f)
            circle(n.x, n.y, 2.4f, Tone.FACE)
            val ch = hp(7.0f, -10.2f)
            circle(ch.x, ch.y, 4.6f, Tone.FACE)
            val ck = hp(3.5f, -7.5f)
            dot(ck.x, ck.y, 3.4f, Tone.FACE_SHADE)
            val m0 = hp(9f, -9.4f); val m1 = hp(12f, -9.4f)
            stroke(Tone.INK, 1.1f, m0.x, m0.y, m1.x, m1.y)
            // The chin strap of the helmet, from under the shell to the jaw.
            val c0 = hp(-1.5f, 1.5f); val c1 = hp(1.5f, -9f); val c2 = hp(8f, -11.5f)
            stroke(Tone.GEAR, 1.8f, c0.x, c0.y, c1.x, c1.y, c2.x, c2.y)
        }

        // The shadow the helmet throws on the brow (drawn first, so the shell covers its upper half).
        run {
            val f0 = hp(14f, 2.4f); val f1 = hp(0f, -0.6f); val f2 = hp(-10f, -6.5f)
            stroke(Tone.FACE_SHADE, 6.0f, f0.x, f0.y, f1.x, f1.y, f2.x, f2.y)
        }
        // ---- Helmet: a rounded shell that wraps the back of the head, with the brim built into the front ----
        blob(
            flat(
                hp(-14.2f, -9f), hp(-16f, 0f), hp(-14f, 8.5f), hp(-8f, 14.2f), hp(0f, 16.4f), hp(8f, 14.8f),
                hp(13.8f, 10.2f), hp(19f, 6.8f), hp(18f, 4.2f), hp(8f, 3.0f), hp(0f, 1.5f), hp(-8f, -2f), hp(-12.5f, -7f),
            ),
            Tone.HELMET,
        )
        // The lower back of the shell and the underside of the brim, in shade.
        out.prims.add(Prim(1, Tone.HELMET_SHADE, flat(hp(-14f, -8f), hp(-15.6f, 0.5f), hp(-13.4f, 8f), hp(-9f, 11.6f), hp(-8.5f, 6f), hp(-10.5f, -1f), hp(-12f, -6f))))
        out.prims.add(Prim(1, Tone.HELMET_SHADE, flat(hp(19f, 6.8f), hp(18f, 4.2f), hp(8f, 3.0f), hp(8.5f, 5.2f), hp(14f, 6.6f))))
        // A lime rim just inside the lower edge: the same accent as the flag.
        run {
            val r0 = hp(15.5f, 5.6f); val r1 = hp(8f, 5.2f); val r2 = hp(0f, 3.6f); val r3 = hp(-8f, 0.4f); val r4 = hp(-13f, -5.2f)
            stroke(Tone.ACCENT, 1.9f, r0.x, r0.y, r1.x, r1.y, r2.x, r2.y, r3.x, r3.y, r4.x, r4.y)
        }
        // Vents on the crown.
        for (ang in floatArrayOf(-50f, -18f, 14f)) {
            val a = ang * PI.toFloat() / 180f
            val ax = vx * cos(a) + fx * sin(a)
            val ay = vy * cos(a) + fy * sin(a)
            stroke(Tone.JACKET_SHADE, 1.5f, hx + ax * (Rig.HEAD_R - 1.4f), hy + ay * (Rig.HEAD_R - 1.4f), hx + ax * (Rig.HEAD_R + 1.4f), hy + ay * (Rig.HEAD_R + 1.4f))
        }
        // ---- Goggles: a strap round the head, and a lens that sits on the eyes, with a glint of the sun ----
        run {
            val s0 = hp(7f, -0.8f); val s1 = hp(-3f, -1.4f); val s2 = hp(-12.4f, -4.6f)
            stroke(Tone.GOGGLE, 2.6f, s0.x, s0.y, s1.x, s1.y, s2.x, s2.y)
            val g1 = hp(10.5f, 1.8f); val g2 = hp(10.5f, -2.8f)
            inked(Tone.GOGGLE, 7.4f, g1.x, g1.y, g2.x, g2.y)
            stroke(Tone.LENS, 5.2f, g1.x, g1.y, g2.x, g2.y)
            val l0 = hp(12.2f, 1.1f); val l1 = hp(12.2f, -1.4f)
            stroke(Tone.ACCENT, 1.4f, l0.x, l0.y, l1.x, l1.y)
            val gl = hp(11.4f, 2.2f)
            dot(gl.x, gl.y, 1.2f, Tone.SHINE)
        }
        // A highlight on the shell, from the same light (up and ahead).
        run {
            val hi = hp(4f, 12.2f)
            dot(hi.x, hi.y, 2.4f, Tone.SHINE)
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

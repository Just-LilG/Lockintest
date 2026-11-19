package com.godbless.lockin.intro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal class Pt(val x: Float, val y: Float)

/**
 * One climber walking up the mountain from [xs] to [xEnd] in [steps] steps.
 * [dir] is +1 for a climber walking right, -1 for one walking left.
 */
internal class ClimberSpec(
    val xs: Float,
    val dir: Float,
    val steps: Int,
    val xEnd: Float,
    val t0: Float,
    val dur: Float,
    /** Step length in scene units. 0 means "[dTotal] split into [steps]". */
    stride: Float = 0f,
    /** Draws the climber with a ponytail and a slimmer build. Movement is the same. */
    val female: Boolean = false,
) {
    val dTotal: Float = abs(xEnd - xs)
    val stepLen: Float = if (stride > 0f) stride else dTotal / steps
    fun worldX(u: Float): Float = xs + dir * u

    /** The ground's average steepness along the whole route (radians, always positive). A climber leans more than usual on steeper ground. */
    val meanSlope: Float by lazy {
        var sum = 0f
        val n = 24
        for (i in 0..n) sum += Rig.slopeAt(this, dTotal * i / n)
        sum / (n + 1)
    }
}

/** Joint positions in the climber's local frame: u runs along the walking direction, y is screen-down. */
internal class Pose(
    val hip: Pt,
    val shoulder: Pt,
    val head: Pt,
    val kneeA: Pt,
    val ankleA: Pt,
    val kneeB: Pt,
    val ankleB: Pt,
    val elbowA: Pt,
    val handA: Pt,
    val elbowB: Pt,
    val handB: Pt,
    val lean: Float,
    /** How far the backpack swings back from the torso (radians): it lags each step, and bounces on a cheer. */
    val pack: Float = 0f,
    /** Where each leg and arm joins the body. The pelvis and shoulders twist a little as each step is taken, so these differ from [hip] and [shoulder]. */
    val hipA: Pt = hip,
    val hipB: Pt = hip,
    val shoulderA: Pt = shoulder,
    val shoulderB: Pt = shoulder,
    /** How much each boot is tipped (radians, toes down positive) on top of following the slope: heel up at push-off, toes up as it reaches forward. */
    val pitchA: Float = 0f,
    val pitchB: Float = 0f,
    /** How tightly each hand is closed, 0 open to 1 fist. Hands open to reach and close on the rock. */
    val gripA: Float = 1f,
    val gripB: Float = 1f,
    /** The clock the pose was made at, so loose gear (the scarf) can ripple. */
    val clock: Float = 0f,
    /** The head's own tip relative to the torso (radians), so the face can follow where the head looks. */
    val gaze: Float = 0f,
    /** 0 to 1: how out of breath. */
    val puff: Float = 0f,
)

/**
 * What a climber is doing besides climbing, each 0 to 1 so the scene can spring smoothly between them.
 * They are weights: whatever they leave over (1 minus their sum) is plain climbing.
 */
internal class Life(
    /** Standing tall, leaning on the axe: resting, waiting for the other one, or at the top. */
    val stand: Float = 0f,
    /** Both arms up, bouncing: the goal was just reached. Builds on [stand]. */
    val cheer: Float = 0f,
    /** A long day: slower breathing, lower head, deeper crouch. Works with any of the others. */
    val tired: Float = 0f,
    /** A stumble, as when someone picks up their phone during a lock: drops and catches themselves. */
    val slip: Float = 0f,
    /**
     * 1 while the climber is really walking up, 0 once they've stopped. A climber that stops doesn't freeze mid-stride
     * (a foot in the air): the weight eases to 0 and they step together and rest, like anyone would.
     */
    val walk: Float = 1f,
) {
    companion object { val Climb = Life() }
}

/**
 * A small procedural climbing rig. Feet are planted on the real skyline and only move in swing
 * phases, the hip drops to keep the planted leg reachable (so the front knee bends on steep ground),
 * and the arms scramble in opposition to the legs.
 *
 * What makes it read as a person rather than a mechanism:
 *  - feet roll: the heel comes up at push-off, the toes lift as the foot reaches forward, and it lands heel first;
 *  - the pelvis and shoulders twist against each other with every step;
 *  - the head, the pack and the free hand trail the body a beat behind it (follow-through);
 *  - the lean follows the steepness of the ground under the climber;
 *  - arms change which way the elbow points by easing, never by jumping;
 *  - resting is a real pose with its own small life: weight shifts, a foot re-plants, a hand shakes out.
 */
internal object Rig {
    const val THIGH = 50f
    const val SHIN = 50f
    const val UARM = 33f
    const val FARM = 31f
    const val TORSO = 64f
    const val HEAD_R = 13.5f
    const val ANKLE_H = 10f

    /** How far ahead of the shoulder a hand lands on the rock (scene units). */
    const val HAND_LEAD = 100f

    /** How much of full leg length the hips may use: lower means a deeper scramble crouch. */
    const val CROUCH = 0.80f
    const val LEAN_DEG = 57f

    /** How high a full jump (see `pose`'s [jump]) lifts the hips, in scene units. */
    const val JUMP_H = 28f

    fun distance(c: ClimberSpec, t: Float): Float {
        val x = (t - c.t0) / c.dur
        if (x <= 0f) return 0f
        if (x >= 1f) return c.dTotal
        return c.dTotal * (0.35f * x + 0.65f * easeInOutSine(x))
    }

    /**
     * A smooth bump that comes round every [period] seconds and lasts [dur] seconds (0 outside it, 1 at
     * its middle). Used for the little things a climber does while standing: look up, re-plant the axe.
     */
    private fun pulse(x: Float, period: Float, dur: Float): Float {
        val f = ((x % period) + period) % period
        if (f >= dur) return 0f
        val k = sin(PI.toFloat() * f / dur)
        return k * k
    }

    private fun smooth(x: Float): Float {
        val v = clamp01(x)
        return v * v * (3f - 2f * v)
    }

    /** Quintic easing has zero velocity and acceleration at both ends: useful for planted feet and hand contacts. */
    private fun smoother(x: Float): Float {
        val v = clamp01(x)
        return v * v * v * (v * (v * 6f - 15f) + 10f)
    }

    /** Steepness (radians, positive) of the ground at local position [u], averaged over a short stretch so it doesn't jitter. */
    fun slopeAt(c: ClimberSpec, u: Float): Float {
        val a = c.worldX(u - 10f)
        val b = c.worldX(u + 10f)
        val dx = abs(b - a).coerceAtLeast(1f)
        return atan2(abs(Ground.y(b) - Ground.y(a)), dx)
    }

    /**
     * [idleT] is a clock in seconds that keeps running while the climber stands still: it drives breathing,
     * a slow weight shift, a glance up at the summit and re-planting the axe. [wave] (0 to 1) raises the
     * near arm and waves it, as when a partner's nudge lands.
     */
    fun pose(c: ClimberSpec, t: Float, idleT: Float, wave: Float = 0f, life: Life = Life.Climb, jump: Float = 0f): Pose {
        // The two climbers keep their own rhythm, so they never move in step.
        val ph = idleT + if (c.dir > 0f) 0f else 2.7f
        val d = distance(c, t)
        val step = c.stepLen
        val phi = d / (2f * step)
        // The same stride, a moment behind: what hangs off the body (head, pack) is still catching up.
        val phiLag = phi - 0.07f
        val twoPi = (2.0 * PI).toFloat()

        fun footU(o: Float, shift: Float): Float {
            val psi = phi + o
            val n = floor(psi)
            val f = psi - n
            // The boot stays completely planted for the stance phase, then rolls through a softly
            // accelerated swing. Quintic easing prevents the foot from visibly snapping at lift-off/landing.
            val s = if (f < 0.5f) 0f else smoother((f - 0.5f) / 0.5f)
            return 2f * step * (n + s) + step / 2f + shift
        }

        fun footLift(o: Float): Float {
            val psi = phi + o
            val f = psi - floor(psi)
            if (f < 0.5f) return 0f
            // A real step: clear the rock early, carry the boot through an arc, then lower it slowly
            // so the landing reads as weight-bearing rather than a foot sliding into place.
            val x = ((f - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val up = smoother((x / 0.42f).coerceIn(0f, 1f))
            val down = 1f - smoother(((x - 0.42f) / 0.58f).coerceIn(0f, 1f))
            return 18f * min(up, down)
        }

        /** How far each boot is tipped (toes down positive). Continuous all the way round the step. */
        fun footPitch(o: Float): Float {
            val psi = phi + o
            val f = psi - floor(psi)
            return if (f < 0.5f) {
                // On the ground: the body rolls over it and the heel comes up for the push-off.
                0.50f * smooth((f - 0.28f) / 0.22f)
            } else {
                val x = (f - 0.5f) / 0.5f
                // Leaving the ground heel first, then the toes lift as the foot reaches forward, and it lands flat.
                0.50f * (1f - smooth(x / 0.35f)) - 0.30f * sin(PI.toFloat() * clamp01((x - 0.15f) / 0.85f))
            }
        }

        fun ankle(u: Float, lift: Float): Pt = Pt(u, Ground.y(c.worldX(u)) - ANKLE_H - lift)

        val aA = ankle(footU(0f, 0f), footLift(0f))
        val aB = ankle(footU(0.5f, -step), footLift(0.5f))

        val reach = CROUCH * (THIGH + SHIN)
        // A slow weight shift over the planted feet.
        // The pelvis does not travel like a rigid trolley: it shifts over the loaded foot and
        // counter-swings slightly as the free leg passes through.
        val hipX = d + 2.2f * sin(ph * 0.55f) + 1.35f * sin(twoPi * phi - 0.32f)
        fun lowerBound(a: Pt): Float {
            val dx = a.x - hipX
            return a.y - sqrt(max(reach * reach - dx * dx, 1f))
        }

        // The hips are lowest just after each foot lands (taking the weight) and highest mid-stride.
        // Two soft vertical impulses per full gait cycle (one for each footfall), with a small
        // asymmetry so the body settles after landing instead of bouncing like a spring toy.
        val gaitBeat = twoPi * 2f * phi
        val bob = 2.6f * cos(gaitBeat - 0.48f) + 0.8f * sin(gaitBeat - 0.95f)
        // Tired climbers breathe deeper and slower.
        val breathRate = 2.1f - 0.7f * life.tired
        val breathe = (1.6f + 1.4f * life.tired) * sin(ph * breathRate)
        val hipY = max(lowerBound(aA), lowerBound(aB)) + 5f + bob * 0.6f + breathe + 6f * life.tired
        var hip = Pt(hipX, hipY)

        // Steeper ground, more lean into it.
        val slopeNow = slopeAt(c, d)
        val slopeLean = (0.45f * Math.toDegrees((slopeNow - c.meanSlope).toDouble()).toFloat()).coerceIn(-9f, 9f)

        val look = pulse(ph, 8.5f, 2.2f)
        // A little forward drive on push-off, followed by a softer recovery over the planted foot.
        val drive = 1.7f * sin(gaitBeat - 0.9f)
        var lean = Math.toRadians((LEAN_DEG + slopeLean + 3.2f * cos(gaitBeat) + drive - 1.4f * sin(ph * breathRate) - 6f * look * (1f - life.tired) - 10f * wave + 5f * life.tired).toDouble()).toFloat()
        // The head tips back to look at the summit, and now and then lifts right up to check on it.
        var tilt = lean * 0.38f + 0.05f * sin(ph * 1.3f) - 0.42f * look * (1f - life.tired) - 0.25f * wave + 0.22f * life.tired
        // The head stays steady while the body rocks with each step, the way eyes keep a level horizon, and it
        // settles a beat after the body does.
        tilt -= Math.toRadians((4f * cos(twoPi * 2f * phiLag - 0.35f) * 0.7f).toDouble()).toFloat()

        // Hands grab the real rock ahead, one after the other, opposite to the feet.
        fun handTarget(o: Float): Pt {
            val psi = phi + o
            val n = floor(psi)
            val f = psi - n
            val x = if (f < 0.5f) 0f else ((f - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val s = if (f < 0.5f) 0f else smoother(x)
            // Hands clear the face/helmet on the way up, then settle onto the next hold with a soft contact.
            val lift = if (f < 0.5f) 0f else 17f * sin(PI.toFloat() * x.pow(0.82f))
            val u = 2f * step * (n + s) + HAND_LEAD - 2f * step * o
            return Pt(u, Ground.y(c.worldX(u)) - 5f - lift)
        }

        /** Open while reaching, closing as it lands, shut while it holds the rock. */
        fun handGrip(o: Float): Float {
            val psi = phi + o
            val f = psi - floor(psi)
            if (f < 0.5f) return 1f
            val x = (f - 0.5f) / 0.5f
            return 1f - 0.85f * sin(PI.toFloat() * clamp01(x * 1.05f)).pow(0.7f)
        }

        // Re-planting the axe: every few seconds the lead hand lifts, swings forward and bites back in.
        val plant = pulse(ph + 3.1f, 6.5f, 1.2f)
        var handA = handTarget(0.5f).let { Pt(it.x + 10f * plant, it.y - 24f * plant) }
        var handB = handTarget(0f)
        var gripA = handGrip(0.5f)
        var gripB = handGrip(0f)
        // The axe hand loosens as it is pulled out and swung forward.
        gripA = min(gripA, 1f - 0.5f * plant)
        var footA = aA
        var footB = aB
        var pitchA = footPitch(0f)
        var pitchB = footPitch(0.5f)
        val bendLeg = -1f
        // Loose kit follows the torso a fraction late; the pack swings more on the recovery than the drive.
        var pack = 0.145f * cos(twoPi * 2f * phiLag - 1.05f) + 0.035f * sin(twoPi * phiLag)
        var puff = life.tired

        // The pelvis and shoulders twist against each other with each step (the near hip comes forward as its
        // leg swings, the near shoulder goes back).
        val twist = sin(twoPi * phi - 0.16f)

        // Where the shoulder is while climbing: the hands swing round it when they change what they're doing.
        val climbSh = Pt(hip.x + TORSO * sin(lean), hip.y - TORSO * cos(lean))

        // ----- The other things a climber does, blended in by weight -----
        // Resting is not a special case of climbing: once they stop walking they stand.
        val rest = clamp01(life.stand + (1f - life.walk))
        val wStand = clamp01(rest + life.cheer)
        var twistK = 1f - wStand
        if (wStand > 0f || life.slip > 0f) {
            fun g(u: Float) = Ground.y(c.worldX(u))

            if (wStand > 0f) {
                // Standing tall on two planted feet, the axe planted ahead, the other hand on the hip.
                // A slow weight shift: the weight rolls from one foot to the other.
                val roll = sin(ph * 0.42f)
                val fA = Pt(d + 14f, g(d + 14f) - ANKLE_H)
                // The back foot re-plants every so often, as people do when they've been standing a while.
                val stamp = pulse(ph + 1.9f, 7.5f, 0.9f) * (1f - life.cheer)
                val bx = d - 16f + 3f * stamp
                val fB = Pt(bx, g(bx) - ANKLE_H - 6f * stamp)
                val tall = 0.94f * (THIGH + SHIN)
                val sdx = d + 2.4f * roll
                fun lb(a: Pt): Float { val dx = a.x - sdx; return a.y - sqrt(max(tall * tall - dx * dx, 1f)) }
                // A cheer bounces on the toes: a quick crouch to push off, up, and a soft landing.
                val hopPh = idleT * 8.4f
                val hop = life.cheer * 9f * max(0f, sin(hopPh))
                val squat = life.cheer * 4f * max(0f, -sin(hopPh))
                val sHip = Pt(sdx, max(lb(fA), lb(fB)) + 3f + breathe - hop + squat + 1.2f * stamp)
                // Resting on a slope: lean into it a little more, and more again when tired.
                val sLean = Math.toRadians((17f + 0.3f * slopeLean - 1.2f * sin(ph * breathRate) + 4f * life.tired - 7f * life.cheer).toDouble()).toFloat()
                val sSh = Pt(sHip.x + TORSO * sin(sLean), sHip.y - TORSO * cos(sLean))
                // Looking up at the summit, now and then across at the view (and a glance back down at the rope).
                val sTilt = sLean * 0.3f - 0.18f + 0.12f * sin(ph * 0.4f) + 0.10f * sin(ph * 0.17f + 1f) + 0.15f * life.tired - 0.30f * life.cheer * (0.6f + 0.4f * sin(hopPh - 0.8f))
                // The axe: the hand rests on top of it, its shaft running down into the snow ahead.
                var sHandA = Pt(d + 40f, g(d + 40f) - 30f)
                // The free hand rests on the hip, and now and then shakes out the cold.
                val shake = pulse(ph + 5.2f, 13f, 1.5f)
                var sHandB = Pt(sHip.x + 9f + 16f * shake, sHip.y - 8f - 34f * shake + 5f * sin(idleT * 31f) * shake)
                var sGripA = 0.85f
                var sGripB = 0.55f * (1f - shake) + 0.1f * shake
                if (life.cheer > 0f) {
                    // Both arms up in a V, pumping. The arms don't pump in unison: the second follows the first.
                    val pumpA = 7f * sin(hopPh - 0.5f)
                    val pumpB = 7f * sin(hopPh - 1.1f)
                    val upA = Pt(sSh.x + 22f, sSh.y - 58f - pumpA)
                    val upB = Pt(sSh.x - 14f, sSh.y - 60f + pumpB)
                    val k = clamp01(life.cheer / max(wStand, 1e-3f))
                    sHandA = swingPt(sSh, sHandA, upA, k, forward = true)
                    sHandB = swingPt(sSh, sHandB, upB, k, forward = true)
                    sGripA = mix(sGripA, 1f, k)
                    sGripB = mix(sGripB, 1f, k)
                }
                // Stepping together as they come to rest: the foot that has the furthest to go lifts as it slides.
                val slide = wStand * (1f - wStand) * 4f
                val dA = abs(fA.x - aA.x)
                val dB = abs(fB.x - aB.x)
                val lift = 6f * slide
                hip = lerpPt(hip, sHip, wStand)
                footA = lerpPt(footA, fA, wStand).let { if (dA >= dB) Pt(it.x, it.y - lift) else it }
                footB = lerpPt(footB, fB, wStand).let { if (dA < dB) Pt(it.x, it.y - lift) else it }
                lean = mix(lean, sLean, wStand)
                tilt = mix(tilt, sTilt, wStand)
                val blendSh = lerpPt(climbSh, sSh, wStand)
                handA = swingPt(blendSh, handA, sHandA, wStand)
                handB = swingPt(blendSh, handB, sHandB, wStand)
                gripA = mix(gripA, sGripA, wStand)
                gripB = mix(gripB, sGripB, wStand)
                pitchA = mix(pitchA, 0.05f, wStand)
                pitchB = mix(pitchB, 0.05f - 0.55f * stamp, wStand)
                pack = mix(pack, 0.03f * sin(ph * breathRate) - life.cheer * 0.14f * max(0f, sin(hopPh - 0.9f)), wStand)
                puff = max(puff, 0.35f * (1f - life.walk))
            }

            if (life.slip > 0f) {
                // A stumble: the hips drop, the body pitches forward and the near hand flies up.
                val k = life.slip
                hip = Pt(hip.x - 6f * k, hip.y + 16f * k)
                lean += 0.35f * k
                tilt += 0.3f * k
                val slipSh = Pt(hip.x + TORSO * sin(lean), hip.y - TORSO * cos(lean))
                handA = swingPt(slipSh, handA, Pt(hip.x + 20f, hip.y - 95f), k * 0.8f)
                gripA = mix(gripA, 0.2f, k)
                pack -= 0.25f * k
                twistK *= 1f - k
            }
        }

        // A jump of surprise (as when a nudge lands): [jump] below 0 is the crouch before take-off and the landing
        // (the hips drop, the body folds forward), above 0 is in the air (the hips rise, the legs tuck, the head
        // tips up). The hands go with the body or the arms would stretch to the ground.
        if (jump != 0f) {
            val up = if (jump > 0f) jump * JUMP_H else jump * 9f
            hip = Pt(hip.x, hip.y - up)
            handA = Pt(handA.x, handA.y - up * 0.85f)
            handB = Pt(handB.x, handB.y - up * 0.85f)
            if (jump > 0f) {
                val tuck = jump * (JUMP_H * 0.80f + 8f)
                footA = Pt(footA.x, footA.y - tuck)
                footB = Pt(footB.x, footB.y - tuck * 0.9f)
                pitchA += 0.30f * jump
                pitchB += 0.40f * jump
                tilt -= 0.20f * jump
                pack -= 0.12f * jump
            } else {
                lean += 0.12f * -jump
                pack += 0.06f * -jump
            }
        }

        val sh = Pt(hip.x + TORSO * sin(lean), hip.y - TORSO * cos(lean))
        val head = Pt(sh.x + 27f * sin(tilt), sh.y - 27f * cos(tilt))

        // Pelvis and shoulders twist, each opposite the other, fading out when they stand.
        val tw = twist * twistK
        // Pelvis and ribcage rotate against each other. The tiny vertical offsets make the
        // shoulder line and belt line counter-roll, which sells the weight transfer.
        val hipA = Pt(hip.x - 1.9f * tw, hip.y - 0.9f * tw)
        val hipB = Pt(hip.x + 1.9f * tw, hip.y + 0.9f * tw)
        val shA = Pt(sh.x + 2.3f * tw, sh.y + 1.15f * tw)
        val shB = Pt(sh.x - 2.3f * tw, sh.y - 1.15f * tw)

        val legA = twoBone(hipA, footA, THIGH, SHIN, bendLeg)
        val legB = twoBone(hipB, footB, THIGH, SHIN, bendLeg)

        // Waving: the near arm goes up over the head and swings side to side.
        val waveTarget = Pt(sh.x + 38f + 14f * sin(idleT * 13f), sh.y - 44f)
        val targetA = if (wave > 0f) swingPt(sh, handA, waveTarget, wave) else handA
        // Both elbows always bend the same way, like a real arm. (The near elbow used to swing round to the other
        // side when standing, cheering or waving, which turned the whole arm through about 200 degrees.)
        val armA = twoBone(shA, targetA, UARM, FARM, +1f, soft = true)
        val armB = twoBone(shB, handB, UARM, FARM, +1f, soft = true)

        return Pose(
            hip = hip, shoulder = sh, head = head,
            kneeA = legA.joint, ankleA = legA.end, kneeB = legB.joint, ankleB = legB.end,
            elbowA = armA.joint, handA = armA.end, elbowB = armB.joint, handB = armB.end,
            lean = lean, pack = pack,
            hipA = hipA, hipB = hipB, shoulderA = shA, shoulderB = shB,
            pitchA = pitchA, pitchB = pitchB, gripA = gripA, gripB = gripB,
            clock = idleT, gaze = tilt - lean * 0.38f, puff = puff,
        )
    }

    private fun lerpPt(a: Pt, b: Pt, k: Float): Pt = Pt(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)

    /**
     * Moves a hand from [a] to [b] on an arc round the shoulder [root] (angle and distance both eased) instead of in a
     * straight line, which would pass right by the shoulder and make the elbow whip round. The arc goes the way an arm
     * naturally swings: forward and up.
     */
    private fun swingPt(root: Pt, a: Pt, b: Pt, k: Float, forward: Boolean = false): Pt {
        if (k <= 0f) return a
        if (k >= 1f) return b
        val ax = a.x - root.x; val ay = a.y - root.y
        val bx = b.x - root.x; val by = b.y - root.y
        val ra = hypot(ax, ay); val rb = hypot(bx, by)
        if (ra < 6f || rb < 6f) return lerpPt(a, b, k)
        val ta = atan2(ay, ax)
        var dt = atan2(by, bx) - ta
        while (dt > PI.toFloat()) dt -= 2f * PI.toFloat()
        while (dt < -PI.toFloat()) dt += 2f * PI.toFloat()
        // For a big swing (a cheer, starting with the hand low at the hip) the arm goes forward and up; going the
        // long way round the back is never what is meant. Only asked for where the swing is that big, because
        // this rule jumps where the angle crosses its limit.
        if (forward && dt > 0.6f) dt -= 2f * PI.toFloat()
        val th = ta + dt * k
        val r = ra + (rb - ra) * k
        return Pt(root.x + r * cos(th), root.y + r * sin(th))
    }

    /** Angle of the ground under local position [u], in the climber's local frame (radians). */
    fun groundGrade(c: ClimberSpec, u: Float): Float {
        val x = c.worldX(u)
        val dy = (Ground.y(x + 1f) - Ground.y(x - 1f)) / 2f
        return atan2(dy * c.dir, 1f)
    }

    class Limb(val joint: Pt, val end: Pt)

    /**
     * Two-bone inverse kinematics. [bend] is -1 or +1 and picks which side the joint bends to. With [soft], a target
     * that is nearly out of reach is pulled in gently instead of hitting a hard stop: a straightening arm has an elbow
     * that moves very fast for a tiny change in distance, which showed as a flick.
     */
    fun twoBone(root: Pt, target: Pt, a: Float, b: Float, bend: Float, soft: Boolean = false): Limb {
        val dx = target.x - root.x
        val dy = target.y - root.y
        val dist = hypot(dx, dy)
        val len = a + b
        val d = if (soft) {
            val knee = 0.80f * len
            if (dist <= knee) max(1e-3f, dist)
            else {
                val span = 0.185f * len
                knee + span * (1f - kotlin.math.exp(-(dist - knee) / span))
            }
        } else max(1e-3f, min(len - 1e-3f, dist))
        val cosA = ((a * a + d * d - b * b) / (2f * a * d)).coerceIn(-1f, 1f)
        val ang = acos(cosA)
        val th = atan2(dy, dx)
        val joint = Pt(root.x + a * cos(th + bend * ang), root.y + a * sin(th + bend * ang))
        val end = if (dist > 1e-6f) Pt(root.x + dx / dist * d, root.y + dy / dist * d) else target
        return Limb(joint, end)
    }

    /**
     * Two-bone IK where which side the joint bends to is a blend: 0 is the +1 side, 1 the -1 side. In between the
     * elbow passes through a nearly straight arm instead of snapping across.
     */
    fun twoBoneBlend(root: Pt, target: Pt, a: Float, b: Float, blend: Float): Limb {
        val k = clamp01(blend)
        if (k <= 0f) return twoBone(root, target, a, b, +1f, soft = true)
        if (k >= 1f) return twoBone(root, target, a, b, -1f, soft = true)
        val p = twoBone(root, target, a, b, +1f, soft = true)
        val m = twoBone(root, target, a, b, -1f, soft = true)
        return Limb(lerpPt(p.joint, m.joint, k), p.end)
    }
}

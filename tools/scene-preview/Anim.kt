package com.godbless.lockin.intro
import java.io.File
import javax.imageio.ImageIO
import java.awt.*
import java.awt.geom.*
import kotlin.math.*

fun sm(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
fun main() {
    val W = 720; val H = 480; val fps = 20; val secs = 16f
    val ropes = arrayOf(RopeSim(), RopeSim()); var first = true
    var f = 0
    while (f < (secs * fps).toInt()) {
        val time = f / fps.toFloat(); val t = 3f + time
        // story
        val you = 0.25f + 0.69f * sm(0.5f, 4.5f, time)
        val them = 0.35f + 0.59f * sm(1.0f, 6.5f, time)
        val youCheer = sm(4.4f, 4.8f, time) * (1f - sm(8.5f, 9.3f, time))
        val themCheer = sm(6.4f, 6.8f, time) * (1f - sm(8.5f, 9.3f, time))
        val stand = sm(4.4f, 5f, time) * (1f - sm(11.5f, 12.2f, time))
        val standT = sm(6.4f, 7f, time) * (1f - sm(11.5f, 12.2f, time))
        val hour = 13f + 10.6f * sm(8.5f, 12f, time)
        val tent = sm(12.2f, 13.2f, time)
        val bloom = sm(0f, 1.2f, time)
        val (img, g) = canvas(W, H)
        g.scene(W, H)
        val save = g.clip
        g.skyClip(); g.fx(SceneFx.back(t, hour, Weather.Clear, true, bloom)); g.clip = save
        g.mountain()
        val gr = ArrayList<FxPrim>(); SceneFx.cairns(8, bloom, gr); SceneFx.ground(You, you, bloom, t, gr); SceneFx.ground(Them, them, bloom, t + 0.7f, gr); g.fx(gr)
        val pl = Rig.pose(You, you, t, 0f, Life(stand = stand, cheer = youCheer))
        val pr = Rig.pose(Them, them, t, 0f, Life(stand = standT, cheer = themCheer))
        for ((i, pp) in listOf(You to pl, Them to pr).withIndex()) {
            val (spec, p) = pp
            val a = ClimberShapes.ropeAnchor(spec, p)
            val door = spec.worldX(p.hip.x)
            val ax = a.x + (door - a.x) * tent; val ay = a.y + (Ground.y(door) - 6f - a.y) * tent
            if (first) ropes[i].reset(ax, ay, ROPE_TIE_X, ROPE_TIE_Y, 40f)
            ropes[i].step(1f / fps, ax, ay, ROPE_TIE_X, ROPE_TIE_Y, 0.08f)
            val v = ropes[i].points(); val path = Path2D.Float(); path.moveTo(v[0], v[1]); var k=2; while(k<v.size){path.lineTo(v[k],v[k+1]); k+=2}
            g.color = Color(0x9A9AA8); g.stroke = BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(path)
        }
        first = false
        val front = ArrayList<FxPrim>()
        for ((spec, p) in listOf(You to pl, Them to pr)) {
            val gx = spec.worldX(p.hip.x); val gy = Ground.y(gx)
            if (tent > 0.01f) { val l = ArrayList<FxPrim>(); SceneFx.tent(gx, 34f * tent, true, t, l, lamp = tent); g.fx(l); if (tent > 0.9f) SceneFx.zzz(gx + 12f, gy - 42f, t, front) }
            if (tent >= 0.99f) continue
            val k = 1f - tent
            val tr = g.transform
            g.translate(gx.toDouble(), gy.toDouble()); g.scale(k.toDouble(), k.toDouble()); g.translate(-gx.toDouble(), -gy.toDouble())
            val sh = ArrayList<FxPrim>(); SceneFx.shadow(spec, p.hip.x, 1f, sh); g.fx(sh)
            g.prims(ClimberShapes.build(spec, p)); g.transform = tr
            if (tent < 0.1f) {
                val up = atan2(p.head.x - p.shoulder.x, -(p.head.y - p.shoulder.y)); val fx = cos(up) * spec.dir; val fy = sin(up)
                SceneFx.breath(spec.worldX(p.head.x) + fx * 14f, p.head.y + fy * 14f, fx, fy - 0.25f, t, if (spec === You) 0f else 1.6f, front)
            }
        }
        SceneFx.front(t, Weather.Clear, bloom, front)
        SceneFx.burst(time - 4.6f, front); SceneFx.burst(time - 6.6f, front)
        if (youCheer > 0.5f && themCheer > 0.5f) SceneFx.highFive(ROPE_TIE_X, min(pl.handA.y, pr.handA.y) - 6f, t, front)
        g.fx(front)
        g.dispose()
        ImageIO.write(img, "png", File("out/anim/f%03d.png".format(f)))
        f++
    }
}

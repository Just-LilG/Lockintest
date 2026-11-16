package com.godbless.lockin.intro
import java.io.File
import javax.imageio.ImageIO
import java.awt.*
import java.awt.geom.*

internal class Sc(val name: String, val hour: Float, val weather: Weather, val aurora: Boolean, val you: Float, val them: Float, val streak: Int,
    val youLife: Life = Life(), val themLife: Life = Life(), val youTent: Float = 0f, val themTent: Float = 0f, val burst: Float = -1f)

internal fun render(sc: Sc) {
    val W = 1080; val H = 760
    val (img, g) = canvas(W, H)
    val t = 12.3f
    g.scene(W, H)
    val save = g.clip
    g.skyClip(); g.fx(SceneFx.back(t, sc.hour, sc.weather, sc.aurora, 1f)); g.clip = save
    g.mountain()
    val ground = ArrayList<FxPrim>()
    SceneFx.cairns(sc.streak, 1f, ground)
    SceneFx.ground(You, sc.you, 1f, t, ground)
    SceneFx.ground(Them, sc.them, 1f, t, ground)
    g.fx(ground)
    val pl = Rig.pose(You, sc.you, t, 0f, sc.youLife)
    val pr = Rig.pose(Them, sc.them, t, 0f, sc.themLife)
    // ropes settled
    for ((spec, p) in listOf(You to pl, Them to pr)) {
        val a = ClimberShapes.ropeAnchor(spec, p)
        val r = RopeSim(); r.reset(a.x, a.y, ROPE_TIE_X, ROPE_TIE_Y, 40f)
        repeat(240) { r.step(1/60f, a.x, a.y, ROPE_TIE_X, ROPE_TIE_Y, 0.08f) }
        val v = r.points(); val path = Path2D.Float(); path.moveTo(v[0], v[1]); var i=2; while(i<v.size){path.lineTo(v[i],v[i+1]); i+=2}
        g.color = Color(0x9A9AA8); g.stroke = BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(path)
    }
    val front = ArrayList<FxPrim>()
    for ((spec, p, tent) in listOf(Triple(You, pl, sc.youTent), Triple(Them, pr, sc.themTent))) {
        if (tent > 0f) {
            val l = ArrayList<FxPrim>(); SceneFx.tent(spec.worldX(p.hip.x), 34f * tent, true, t, l, lamp = tent); g.fx(l)
            if (tent > 0.9f) SceneFx.zzz(spec.worldX(p.hip.x) + 10f, Ground.y(spec.worldX(p.hip.x)) - 44f, t, front)
            continue
        }
        val sh = ArrayList<FxPrim>(); SceneFx.shadow(spec, p.hip.x, 1f, sh); g.fx(sh)
        g.prims(ClimberShapes.build(spec, p))
        // breath
        val up = kotlin.math.atan2(p.head.x - p.shoulder.x, -(p.head.y - p.shoulder.y))
        val fx = kotlin.math.cos(up) * spec.dir; val fy = kotlin.math.sin(up)
        SceneFx.breath(spec.worldX(p.head.x) + fx * 14f, p.head.y + fy * 14f, fx, fy - 0.25f, 0.8f, 0f, front)
    }
    SceneFx.front(t, sc.weather, 1f, front)
    if (sc.burst >= 0f) SceneFx.burst(sc.burst, front)
    if (sc.youLife.cheer > 0f && sc.themLife.cheer > 0f) SceneFx.highFive(ROPE_TIE_X, (pl.handA.y + pr.handA.y) / 2f - 10f, t, front)
    g.fx(front)
    g.dispose()
    ImageIO.write(img, "png", File("out/" + (if (LIGHT) "light_" else "full_") + "${sc.name}.png"))
}
fun main() {
    SceneFx.light = LIGHT
    listOf(
        Sc("day", 13f, Weather.Clear, false, 0.40f, 0.62f, 3),
        Sc("dawn", 6.6f, Weather.Clear, false, 0.20f, 0.30f, 1),
        Sc("dusk", 18.4f, Weather.Cloudy, false, 0.55f, 0.94f, 6, themLife = Life(stand = 1f)),
        Sc("night", 23.5f, Weather.Clear, true, 0.70f, 0.50f, 9, youTent = 1f, themTent = 1f),
        Sc("snow", 19.5f, Weather.Snow, false, 0.30f, 0.45f, 0),
        Sc("summit", 15f, Weather.Clear, false, 0.94f, 0.94f, 12, youLife = Life(stand=1f, cheer=1f), themLife = Life(stand=1f, cheer=1f), burst = 0.45f),
    ).forEach { render(it) }
}

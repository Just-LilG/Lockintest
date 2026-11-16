package com.godbless.lockin.intro
import java.awt.*
import java.awt.geom.*
internal fun Graphics2D.fx(ps: List<FxPrim>) {
    for (p in ps) {
        val c = Color(p.color, true); val v = p.pts
        when (p.kind) {
            0 -> { color = c; fill(Ellipse2D.Float(v[0]-v[2], v[1]-v[2], 2*v[2], 2*v[2])) }
            3 -> { if (v[2] > 1f) { paint = RadialGradientPaint(v[0], v[1], v[2], floatArrayOf(0f,1f), arrayOf(c, Color(p.color and 0xFFFFFF, true))); fill(Ellipse2D.Float(v[0]-v[2], v[1]-v[2], 2*v[2], 2*v[2])) } }
            else -> { color = c
                val path = Path2D.Float(); path.moveTo(v[0], v[1]); var i=2; while (i<v.size){path.lineTo(v[i],v[i+1]); i+=2}
                if (p.kind==1) { path.closePath(); fill(path) } else { stroke = BasicStroke(p.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); draw(path) } }
        }
    }
}
internal fun Graphics2D.skyClip() {
    val v = SceneFx.skyPolygon
    val path = Path2D.Float(); path.moveTo(v[0], v[1]); var i=2; while (i<v.size){path.lineTo(v[i],v[i+1]); i+=2}; path.closePath()
    clip = path
}

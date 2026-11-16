package com.godbless.lockin.intro

import java.awt.*
import java.awt.geom.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

val PAL = intArrayOf(0xFF0B0B0C.toInt(),0xFFF6F6FA.toInt(),0xFFC9C9D6.toInt(),0xFF8F8FA0.toInt(),0xFF6E6E7E.toInt(),0xFF34343E.toInt(),0xFF42424E.toInt(),0xFFD2D2DC.toInt(),0xFFA8A8B8.toInt(),0xFFF8F8FB.toInt(),0xFFBDBDCB.toInt(),0xFF3C3C48.toInt(),0xFF2E2E38.toInt(),0xFF9A9AA8.toInt(),0xFFC6F432.toInt(),0xFFFFFFFF.toInt(),0xFFDCDCE6.toInt(),0xFF6A6A78.toInt())

internal fun Graphics2D.prims(ps: List<Prim>) {
    for (p in ps) {
        color = Color(PAL[p.tone], true)
        val v = p.pts
        when (p.kind) {
            0 -> fill(Ellipse2D.Float(v[0]-v[2], v[1]-v[2], 2*v[2], 2*v[2]))
            else -> {
                val path = Path2D.Float(); path.moveTo(v[0], v[1]); var i=2; while (i<v.size){path.lineTo(v[i],v[i+1]); i+=2}
                if (p.kind==1) { path.closePath(); fill(path) } else { stroke = BasicStroke(p.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); draw(path) }
            }
        }
    }
}

/** Sets up the same transform as ClimbScene for a W x H canvas. */
fun Graphics2D.scene(W: Int, H: Int) {
    val s = W / SCENE_W
    val oy = H - (BASELINE - SCENE_Y) * s
    translate((-SCENE_X * s).toDouble(), (oy - SCENE_Y * s).toDouble())
    scale(s.toDouble(), s.toDouble())
}

val LIGHT: Boolean = System.getProperty("light") != null
val mountainImg: BufferedImage by lazy { ImageIO.read(File("../../app/src/main/res/drawable-nodpi/" + if (LIGHT) "mountain_light.png" else "mountain.png")) }
fun Graphics2D.mountain() {
    val t = transform
    translate(87.333, 474.0); scale(1/1.5, 1/1.5)
    drawImage(mountainImg, 0, 0, null)
    transform = t
}

fun canvas(W: Int, H: Int): Pair<BufferedImage, Graphics2D> {
    val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.color = Color(if (LIGHT) 0xF4F5F9 else 0x0B0B0C); g.fillRect(0,0,W,H)
    return img to g
}

package com.godbless.lockin.intro
import java.io.File
import javax.imageio.ImageIO
import java.awt.*
import java.awt.image.BufferedImage
internal fun shot(life: Life, spec: ClimberSpec, t: Float, idle: Float, file: String) {
    val img = BufferedImage(300, 260, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0x0B0B0C); g.fillRect(0,0,300,260)
    val p = Rig.pose(spec, t, idle, 0f, life)
    val cx = spec.worldX(p.hip.x); val cy = p.hip.y
    g.translate(150.0, 160.0); g.scale(1.1, 1.1); g.translate(-cx.toDouble(), -cy.toDouble())
    g.mountain(); g.prims(ClimberShapes.build(spec, p)); g.dispose()
    ImageIO.write(img, "png", File(file))
}
fun main() {
    val lives = listOf("climb" to Life(), "stand" to Life(stand=1f), "cheer" to Life(stand=1f, cheer=1f), "sit" to Life(sit=1f), "sleep" to Life(sit=1f, sleep=1f), "tired" to Life(tired=1f), "slip" to Life(slip=1f), "half" to Life(stand=0.5f))
    for ((n, l) in lives) {
        shot(l, You, 0.30f, 3.3f, "out/z_${n}_a.png")
        shot(l, Them, 0.94f, 3.3f, "out/z_${n}_b.png")
    }
}

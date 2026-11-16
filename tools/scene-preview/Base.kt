package com.godbless.lockin.intro
import java.io.File
import javax.imageio.ImageIO
internal val You = ClimberSpec(135f, 1f, 12, 520f, 0f, 1f, stride = 31f)
internal val Them = ClimberSpec(1115f, -1f, 13, 708f, 0f, 1f, stride = 28.8f)
fun main() {
    val (img, g) = canvas(1080, 700)
    g.scene(1080, 700); g.mountain()
    val a = Rig.pose(You, 0.45f, 3f); val b = Rig.pose(Them, 0.7f, 3f)
    g.prims(ClimberShapes.build(You, a)); g.prims(ClimberShapes.build(Them, b))
    ImageIO.write(img, "png", File("/tmp/pv/base.png"))
}

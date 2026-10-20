package platform.handheld

import chisel3._
import lib.util.EphemeralSimulator._
import lib.video.PolyphaseScaler
import net.gamebub.framework.interface.VideoScalerV0
import org.scalatest.funsuite.AnyFunSuite

/**
 * The aspect scaler on a simulated rev2 LCD scan: a 480x320 picture on a rotated 320x480 panel, with
 * the ILI9488 driver's counters (sync + back porch before each line, counters that wrap outside the
 * active area) and a framebuffer read latency of 3 cycles. Every pixel inside the scaled picture is
 * compared with a reference computed in the same fixed point (both axes: the source pattern differs
 * along x and y in every channel).
 */
class LcdAspectScalerSpec extends AnyFunSuite {
  import PolyphaseScaler._

  // ILI9488 (rev2): 320 x 480 panel, rotated.
  val panelWidth = 320 // fast axis = picture rows
  val panelHeight = 480 // slow axis = picture columns
  val hBlankBefore = 6 // hSync + hBackPorch
  val vBlankBefore = 3 // vSync + vBackPorch
  val totalWidth = 420
  val totalHeight = 488
  val screenWidth = 480
  val screenHeight = 320

  val depth = 4

  /** Source pattern: every channel changes along both axes. */
  def source(x: Int, y: Int): (Int, Int, Int) = ((x * 3 + y) & 15, (y * 5 + x / 3) & 15, ((x ^ y) * 7) & 15)

  def expected(g: LcdAspectScaler.Geometry, filter: Int, dx: Int, dy: Int): (Int, Int, Int) = {
    val m = g.mode
    val fx = LcdAspectScaler.sourcePosition(dx - g.offsetX, g.inverseScaleX) & 0x1FFFF
    val fy = LcdAspectScaler.sourcePosition(dy - g.offsetY, g.inverseScaleY) & 0x1FFFF
    val (xi, yi) = (fx >> 8, fy >> 8)
    val (phX, phY) = ((fx >> 2) & 63, (fy >> 2) & 63)
    val slopeX = sharpSlopeFor(g.scaleX)
    val slopeY = sharpSlopeFor(g.scaleY)
    val wx = taps2(filter, phX.toDouble / NumPhases, slopeX)
    val wy = taps2(filter, phY.toDouble / NumPhases, slopeY)
    def cx(h: Int) = math.min(xi + h, m.width - 1)
    def cy(t: Int) = math.min(yi + t, m.height - 1)
    def chan(c: ((Int, Int, Int)) => Int): Int = {
      val acc = (0 until Taps).map { h =>
        (0 until Taps).map(t => c(source(m.x + cx(h), m.y + cy(t))) * wy(t)).sum * wx(h)
      }.sum
      math.min(15, (acc + (1 << 13)) >> 14)
    }
    (chan(_._1), chan(_._2), chan(_._3))
  }

  /** `exact` (integer scales): also check that each pixel is exactly the source pixel under the center of the
    * screen pixel, so every source pixel is `scale` screen pixels wide, the first and last ones included. */
  def run(modes: Seq[VideoScalerV0.Mode], mode: Int, filter: Int, fbWidth: Int = 256, fbHeight: Int = 240,
          exact: Boolean = false): Unit = {
    simulate(new LcdAspectScaler(modes, screenWidth, screenHeight, 0, fbWidth, fbHeight, depth, depth, depth,
      dpiWidthX = 9, dpiWidthY = 9, delay = 0)) { dut =>
      val g = dut.geometries(mode)
      dut.io.mode.poke(mode)
      dut.io.filter.poke(filter)
      dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)
      val history = scala.collection.mutable.Queue.fill(FramebufferReadLatency)(0)
      var checked = 0
      var mismatches = List.empty[String]
      for (frame <- 0 until 2; y <- 0 until totalHeight; x <- 0 until totalWidth) {
        val dpiX = (y - vBlankBefore) & 511
        val dpiY = (x - hBlankBefore) & 511
        dut.io.dpiX.poke(dpiX)
        dut.io.dpiY.poke(dpiY)
        val (r, gg, b) = { val a = history.dequeue(); source(a % fbWidth, a / fbWidth) }
        dut.io.fbReadData.r.poke(r); dut.io.fbReadData.g.poke(gg); dut.io.fbReadData.b.poke(b)
        // Frame 0 latches the mode at its start; check frame 1.
        if (frame == 1 && dut.io.inBounds.peek().litToBoolean) {
          val got = (dut.io.pixel.r.peek().litValue.toInt, dut.io.pixel.g.peek().litValue.toInt,
            dut.io.pixel.b.peek().litValue.toInt)
          val want = expected(g, filter, dpiX, dpiY)
          if (got != want && mismatches.size < 10) mismatches ::= s"($dpiX,$dpiY): got $got, want $want"
          if (exact) {
            val pure = source(g.mode.x + ((dpiX - g.offsetX) / g.scaleX.round.toInt),
              g.mode.y + ((dpiY - g.offsetY) / g.scaleY.round.toInt))
            if (got != pure && mismatches.size < 10) mismatches ::= s"($dpiX,$dpiY): got $got, source pixel $pure"
          }
          checked += 1
        }
        history.enqueue(dut.io.fbReadAddress.peek().litValue.toInt)
        dut.clock.step()
      }
      assert(dut.io.active.peek().litToBoolean)
      assert(checked == g.width * g.height, s"checked $checked of ${g.width}x${g.height}")
      assert(mismatches.isEmpty, mismatches.reverse.mkString("\n"))
    }
  }

  val smsModes = Seq(
    VideoScalerV0.Mode(0, 24, 256, 192),
    VideoScalerV0.Mode(0, 8, 256, 224),
    VideoScalerV0.Mode(0, 0, 256, 240),
  )

  test("geometry: SMS and Game Gear modes on 480x320") {
    val sizes = smsModes.map(LcdAspectScaler.geometry(_, screenWidth, screenHeight, 0)).map(g => (g.width, g.height))
    assert(sizes == Seq((427, 320), (366, 320), (342, 320)))
    val gg = LcdAspectScaler.geometry(VideoScalerV0.Mode(0, 0, 160, 144), screenWidth, screenHeight, 0)
    assert((gg.width, gg.height) == (356, 320))
  }

  test("SMS 192 lines, Sharp Bilinear") { run(smsModes, 0, VideoScalerV0.FilterSharpBilinear) }
  test("SMS 224 lines, Bilinear") { run(smsModes, 1, VideoScalerV0.FilterBilinear) }
  test("SMS 240 lines, Sharp Bilinear") { run(smsModes, 2, VideoScalerV0.FilterSharpBilinear) }
  test("Game Gear 160x144, Sharp Bilinear") {
    run(Seq(VideoScalerV0.Mode(0, 0, 160, 144)), 0, VideoScalerV0.FilterSharpBilinear, fbWidth = 160, fbHeight = 144)
  }

  // rev4: an 800x480 panel with the picture centered on column 372 (HandheldTop's displayOffsetX = -28). Every
  // mode must stay on the panel.
  test("geometry: the SMS and Game Gear modes on the rev4 panel (800x480, offset -28)") {
    for (m <- smsModes ++ Seq(VideoScalerV0.Mode(0, 0, 160, 144))) {
      val g = LcdAspectScaler.geometry(m, 800, 480, -28)
      assert(g.offsetX >= 0 && g.offsetX + g.width <= 800 && g.offsetY >= 0 && g.offsetY + g.height <= 480, s"$m: $g")
      assert(g.offsetX + g.width / 2 - 372 <= 1 && 372 - (g.offsetX + g.width / 2) <= 1, s"$m not centered on 372: $g")
    }
  }
}

package platform.handheld

import chisel3._
import chisel3.util._
import lib.video.{ColorRGB, PolyphaseScaler}
import net.gamebub.framework.interface.VideoScalerV0

object LcdAspectScaler {
  /** Fractional bits of the inverse scale: fine enough that the source position does not drift across
    * the screen (at most 480 * 2^-16 = 0.007 source pixels), so integer scales stay exact. */
  val InverseScaleBits = 16

  /** Source position of screen pixel `rel` (relative to the picture), 9.8 fixed point: taken at the center
    * of the screen pixel, `(rel + 0.5) * inverse - 0.5`, so that the first and last source pixels are as wide
    * as the others; rounded to the scaler's phase step (1/64) and clamped at 0. The reference for the
    * hardware in `toFix` below. */
  def sourcePosition(rel: Int, inverse: Int): Int = {
    val t = (((2L * rel + 1) * inverse) >> (InverseScaleBits - 7)).toInt - 128
    math.max(0, (t + 2) & ~3)
  }

  /** Placement of one mode on the screen. */
  case class Geometry(
    mode: VideoScalerV0.Mode,
    /** Source pixels per screen pixel, 0.16 fixed point (rounded up so the picture fits). */
    inverseScaleX: Int,
    inverseScaleY: Int,
    offsetX: Int,
    offsetY: Int,
    width: Int,
    height: Int,
    scaleX: Double,
    scaleY: Double,
  )

  /**
   * Largest aspect-correct scale of the mode that fits the screen, centered on column
   * `screenWidth / 2 + displayOffsetX` (rev4: 800 columns, centered on 372). The picture is at most
   * `screenWidth - 2 * |displayOffsetX|` wide, so that it stays on the panel around that center.
   */
  def geometry(mode: VideoScalerV0.Mode, screenWidth: Int, screenHeight: Int, displayOffsetX: Int): Geometry = {
    val usableWidth = screenWidth - 2 * displayOffsetX.abs
    val scaleY = math.min(screenHeight.toDouble / mode.height, usableWidth / (mode.width * mode.pixelAspect))
    val scaleX = scaleY * mode.pixelAspect
    val one = 1 << InverseScaleBits
    val inverseX = math.ceil(one / scaleX).toInt
    val inverseY = math.ceil(one / scaleY).toInt
    // Screen pixels whose source coordinate falls inside the window.
    val width = math.ceil(mode.width.toDouble * one / inverseX).toInt
    val height = math.ceil(mode.height.toDouble * one / inverseY).toInt
    require(width <= usableWidth && height <= screenHeight)
    Geometry(mode, inverseX, inverseY, (screenWidth - width) / 2 + displayOffsetX, (screenHeight - height) / 2,
      width, height, scaleX, scaleY)
  }

  /**
   * Integer filter: the largest whole scale of the mode's window that fits the same usable area as
   * [[geometry]] (one scale for both axes, every source pixel a square of screen pixels, at least 1x),
   * centered the same way. Only the window is shown: the parts of the framebuffer outside it, which the
   * core does not write in that mode, stay off the screen (Game Gear 160x144 in its 256x192 framebuffer
   * kept a stale picture there; 2x on rev2, 3x on rev4).
   */
  def integerGeometry(mode: VideoScalerV0.Mode, screenWidth: Int, screenHeight: Int, displayOffsetX: Int): Geometry = {
    val usableWidth = screenWidth - 2 * displayOffsetX.abs
    val scale = math.max(1, math.min(screenHeight / mode.height, usableWidth / mode.width))
    val one = 1 << InverseScaleBits
    val inverse = math.ceil(one.toDouble / scale).toInt
    val width = math.ceil(mode.width.toDouble * one / inverse).toInt
    val height = math.ceil(mode.height.toDouble * one / inverse).toInt
    require(width <= usableWidth && height <= screenHeight, s"mode $mode does not fit at 1x")
    Geometry(mode, inverse, inverse, (screenWidth - width) / 2 + displayOffsetX, (screenHeight - height) / 2,
      width, height, scale.toDouble, scale.toDouble)
  }
}

/**
 * Aspect-correct scaling of the core's framebuffer on an LCD that scans the picture column by column
 * (rotated panel: `dpiX`, the picture's horizontal axis, advances once per panel line; `dpiY` is the fast
 * axis). Computes the geometry of every mode of [[VideoScalerV0]] at elaboration and drives a
 * [[PolyphaseScaler]] from the display position.
 *
 * `dpiX` and `dpiY` are the display driver's counters: outside the active area they wrap around (they
 * are `counter - (sync + back porch)`). The fast-axis coordinate is computed `delay` pixels ahead,
 * modulo the counter width, so the first pixels of a line read the right source rows; this needs at
 * least `delay` cycles of sync + back porch before each line (the ILI9488 driver has 6).
 *
 * @param delay cycles from this module's pixel output to the display (the core's video filter latency)
 */
class LcdAspectScaler(
  modes: Seq[VideoScalerV0.Mode],
  screenWidth: Int,
  screenHeight: Int,
  displayOffsetX: Int,
  fbWidth: Int,
  fbHeight: Int,
  colorDepthR: Int,
  colorDepthG: Int,
  colorDepthB: Int,
  dpiWidthX: Int,
  dpiWidthY: Int,
  delay: Int,
) extends Module {
  import LcdAspectScaler._

  /** Aspect-correct placement of each mode (Sharp Bilinear, Bilinear, Nearest). */
  val geometries = modes.map(geometry(_, screenWidth, screenHeight, displayOffsetX))
  /** Whole-scale placement of each mode (Integer). */
  val integerGeometries = modes.map(integerGeometry(_, screenWidth, screenHeight, displayOffsetX))
  /** Selected by `(integer ? modes.size : 0) + mode`. */
  private val allGeometries = geometries ++ integerGeometries
  for (g <- geometries) {
    require(g.mode.x + g.mode.width <= fbWidth && g.mode.y + g.mode.height <= fbHeight,
      s"mode ${g.mode} outside the ${fbWidth}x$fbHeight framebuffer")
  }
  private val color = ColorRGB(colorDepthR, colorDepthG, colorDepthB)

  val io = IO(new Bundle {
    val dpiX = Input(UInt(dpiWidthX.W))
    val dpiY = Input(UInt(dpiWidthY.W))
    /** From the core (already in this clock domain); applied at the start of a frame. */
    val mode = Input(UInt(log2Ceil(modes.size.max(2)).W))
    val filter = Input(UInt(2.W))

    val fbReadAddress = Output(UInt(log2Ceil(fbWidth * fbHeight).W))
    val fbReadData = Input(color)

    /** The framebuffer read port, the pixel and the bounds come from here (always, Integer included). */
    val active = Output(Bool())
    /** The display position is inside the scaled picture. */
    val inBounds = Output(Bool())
    val pixel = Output(color)
  })

  // Frame start: the first pixel of the first line (once per frame, also after an interrupted frame).
  val atOrigin = io.dpiX === 0.U && io.dpiY === 0.U
  val frameStart = atOrigin && !RegNext(atOrigin, false.B)

  val regMode = RegInit(0.U(io.mode.getWidth.W))
  val regFilter = RegInit(0.U(2.W))
  when (frameStart) {
    regMode := Mux(io.mode < modes.size.U, io.mode, 0.U)
    // Every 2-bit value is a filter: Integer (whole scale) or Sharp Bilinear, Bilinear, Nearest (aspect scale).
    regFilter := io.filter
  }
  // Integer is shown here too, at a whole scale of the mode's window and with the Nearest filter (exact at an
  // integer scale), instead of the platform's integer scale of the whole framebuffer.
  io.active := true.B
  val integer = regFilter === VideoScalerV0.FilterInteger.U
  val geometryIndex = Mux(integer, regMode +& modes.size.U, regMode)

  def selectBy(f: (Geometry, Int) => Int, width: Int): UInt =
    MuxLookup(geometryIndex, f(allGeometries.head, 0).U(width.W))(
      allGeometries.zipWithIndex.map { case (g, i) => i.U -> f(g, i).U(width.W) })
  def select(f: Geometry => Int, width: Int): UInt = selectBy((g, _) => f(g), width)
  val offsetX = select(_.offsetX, 10)
  val offsetY = select(_.offsetY, 10)
  val width = select(_.width, 10)
  val height = select(_.height, 10)
  val inverseX = select(_.inverseScaleX, InverseScaleBits + 2)
  val inverseY = select(_.inverseScaleY, InverseScaleBits + 2)

  // Sharp Bilinear slope of each mode (it depends on the scale: at an integer scale the slope must equal it
  // for every screen pixel to be one source pixel).
  val slopesX = geometries.map(g => PolyphaseScaler.sharpSlopeFor(g.scaleX)).distinct
  val slopesY = geometries.map(g => PolyphaseScaler.sharpSlopeFor(g.scaleY)).distinct
  val scaler = Module(new PolyphaseScaler(
    fbWidth, fbHeight, colorDepthR, colorDepthG, colorDepthB,
    sharpSlopesX = slopesX,
    sharpSlopesY = slopesY,
  ))
  // The integer geometries use Nearest, which has no slope: any index.
  def slopeIndex(slopes: Seq[Int], scale: Double, i: Int): Int =
    if (i < geometries.size) slopes.indexOf(PolyphaseScaler.sharpSlopeFor(scale)) else 0
  scaler.io.slopeSelectX := selectBy((g, i) => slopeIndex(slopesX, g.scaleX, i), log2Ceil(slopesX.size.max(2)))
  scaler.io.slopeSelectY := selectBy((g, i) => slopeIndex(slopesY, g.scaleY, i), log2Ceil(slopesY.size.max(2)))
  scaler.io.windowX := select(_.mode.x, log2Ceil(fbWidth))
  scaler.io.windowY := select(_.mode.y, log2Ceil(fbHeight))
  scaler.io.windowWidth := select(_.mode.width, log2Ceil(fbWidth + 1))
  scaler.io.windowHeight := select(_.mode.height, log2Ceil(fbHeight + 1))
  scaler.io.filter := Mux(integer, VideoScalerV0.FilterNearest.U, regFilter)
  scaler.io.frameStart := frameStart
  scaler.io.fbReadData := io.fbReadData
  io.fbReadAddress := scaler.io.fbReadAddress
  io.pixel := scaler.io.pixelOut

  // Slow axis: constant along a panel line. Outside the picture's columns (left border, vertical
  // blanking) it is held at 0, so the prefetch loads the first columns ahead of the picture.
  val dpiXInScreen = io.dpiX >= offsetX && io.dpiX < screenWidth.U
  val relX = Mux(dpiXInScreen, io.dpiX - offsetX, 0.U)
  // Fast axis: `PolyphaseScaler.Latency + delay` pixels ahead, modulo the counter width. Outside the
  // picture the coordinate is past the window and the scaler clamps it to the last row.
  val ahead = PolyphaseScaler.Latency + delay
  val relY = (io.dpiY + ahead.U - offsetY)(dpiWidthY - 1, 0)

  // As sourcePosition: center of the screen pixel, rounded to 1/64, clamped at 0.
  def toFix(rel: UInt, inverse: UInt): UInt = {
    val t = ((Cat(rel, 1.U(1.W)) * inverse) >> (InverseScaleBits - 7)).zext - 126.S
    Mux(t < 0.S, 0.U, (t.asUInt & ~3.U(t.getWidth.W))(16, 0))
  }
  scaler.io.srcXfix := toFix(relX, inverseX)
  scaler.io.srcYfix := toFix(relY, inverseY)

  io.inBounds := io.dpiX >= offsetX && io.dpiX < offsetX + width &&
    io.dpiY >= offsetY && io.dpiY < offsetY + height
}

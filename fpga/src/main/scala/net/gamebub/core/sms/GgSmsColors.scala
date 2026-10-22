package net.gamebub.core.sms

import chisel3._
import chisel3.util._

/** CRC-32 as zlib computes it (reflected, polynomial 0xEDB88320, initial value and final XOR 0xFFFFFFFF). */
object Crc32 {
  val Polynomial = 0xEDB88320L

  /** Software reference: one byte into the running register (not inverted). */
  def update(crc: Long, byte: Int): Long = {
    var c = (crc ^ (byte & 0xFF)) & 0xFFFFFFFFL
    for (_ <- 0 until 8) {
      c = if ((c & 1) != 0) (c >>> 1) ^ Polynomial else c >>> 1
    }
    c
  }

  /** Software reference: the CRC-32 of `data`. */
  def apply(data: Seq[Int]): Long = data.foldLeft(0xFFFFFFFFL)(update) ^ 0xFFFFFFFFL

  /** Hardware: one byte into the running register (not inverted); combinational, 8 bit steps. */
  def update(crc: UInt, byte: UInt): UInt = {
    val start = crc ^ byte.pad(32)
    (0 until 8).foldLeft(start) { (c, _) => Mux(c(0), (c >> 1) ^ Polynomial.U(32.W), c >> 1) }
  }
}

/**
 * CRC-32 of a ROM file while the host loads it, without the 512-byte copier header (the same CRC as
 * Genesis Plus GX and the No-Intro databases).
 *
 * The bytes come in address order, one at a time (the core's download replay). Whether the file has a
 * copier header is only known at the end, from its size (size mod 1024 = 512, as the core's ROM mapping
 * decides), so two CRCs run side by side: from byte 0 and from byte 512.
 *
 * The host transfers 32-bit words, so the last word can carry up to 3 bytes past the end of the file. The
 * register before each byte of the current word is kept, and the result is taken at the file size. This
 * relies on every byte of a word being fed (the replay always feeds 4).
 *
 * @param addressWidth width of the byte address in the file
 */
class RomCrc32(addressWidth: Int) extends Module {
  val io = IO(new Bundle {
    /** A new file: clear (before the first byte). */
    val start = Input(Bool())
    /** One byte of the file at `address`. */
    val valid = Input(Bool())
    val address = Input(UInt(addressWidth.W))
    val data = Input(UInt(8.W))
    /** The file size, in bytes (used once every byte is in). */
    val size = Input(UInt(addressWidth.W))
    /** CRC-32 of the first `size` bytes, without the copier header if `size` says there is one. */
    val crc = Output(UInt(32.W))
    /** The file has a 512-byte copier header (size mod 1024 = 512). */
    val header = Output(Bool())
  })

  /** One CRC: `run` after every byte so far, `before(j)` before byte j of the current word. */
  class Stream(firstAddress: Int) {
    val run = RegInit("hFFFFFFFF".U(32.W))
    val before = Reg(Vec(4, UInt(32.W)))
    when (io.start) {
      run := "hFFFFFFFF".U
    } .elsewhen (io.valid && io.address >= firstAddress.U) {
      before(io.address(1, 0)) := run
      run := Crc32.update(run, io.data)
    }
    /** After the first `size` bytes: the whole last word, or its first size mod 4 bytes. */
    def result: UInt = Mux(io.size(1, 0) === 0.U, run, before(io.size(1, 0)))
  }

  val whole = new Stream(0)
  val withoutHeader = new Stream(512)
  io.header := io.size(9, 0) === 512.U
  io.crc := ~Mux(io.header, withoutHeader.result, whole.result)
}

/**
 * Game Gear games that run in the console's Master System compatibility mode: they need the SMS palette
 * (and the Start button as the SMS Pause button) while the screen stays the 160x144 Game Gear window.
 * Their ROM header does not say so (Out Run Europa reports region 7, Game Gear International), so they are
 * recognised by the CRC-32 of the ROM without its copier header.
 *
 * The list is the SYSTEM_GGMS entries with the Sega mapper of Genesis Plus GX's `core/cart_hw/sms_cart.c`
 * (Copyright (C) 2007-2025 Eke-Eke (Genesis Plus GX); non-commercial licence, see THIRD-PARTY.md).
 */
object GgSmsColors {
  val Crcs: Seq[(Long, String)] = Seq(
    0x59840FD6L -> "Castle of Illusion - Starring Mickey Mouse",
    0xCC521975L -> "Cave Dude (proto)",
    0x1D93246EL -> "Olympic Gold (A)",
    0xA2F9C7AFL -> "Olympic Gold (B)",
    0x01EAB89DL -> "Out Run Europa",
    0xF037EC00L -> "Out Run Europa (US)",
    0xE5F789B9L -> "Predator 2",
    0x311D2863L -> "Prince of Persia (A)",
    0x45F058D6L -> "Prince of Persia (B)",
    0x56201996L -> "R.C. Grand Prix",
    0x10DBBEF4L -> "Super Kick Off",
    0xC8381DEFL -> "Taito Chase H.Q.",
    0xDA8E95A9L -> "WWF Wrestlemania Steel Cage Challenge",
    0x6630E5FDL -> "Aerial Assault (TW)",
    0x6F8E46CFL -> "Alex Kidd in Miracle World (TW)",
    0x5E4B454EL -> "Argos no Juujiken (TW)",
    0x98F64975L -> "Black Belt (TW)",
    0x9942B69BL -> "Castle of Illusion - Starring Mickey Mouse (J)",
    0x55F929CEL -> "Choplifter (TW)",
    0xAD9FF469L -> "Cyber Shinobi, The (TW)",
    0xFB163003L -> "Doki Doki Penguin Land - Uchuu-Daibouken (TW)",
    0xF4F848C2L -> "Double Dragon (TW)",
    0x96E16FE4L -> "E-SWAT (v1) (TW)",
    0xB948752EL -> "Final Bubble Bobble (TW)",
    0x44136A72L -> "Forgotten Worlds (TW)",
    0x6FE448A5L -> "Great Basketball (TW)",
    0xB6207F0DL -> "Hokuto no Ken (TW)",
    0x4762E022L -> "Kung Fu Kid (TW)",
    0x7EAED675L -> "Lord of Sword (TW)",
    0x3382D73FL -> "Olympic Gold (TW)",
    0x354BEE78L -> "Paperboy (v1) (TW)",
    0xCAFD2D83L -> "Prince of Persia (TW)",
    0xCACDF759L -> "Quartet (TW)",
    0xE532716FL -> "R-Type (TW)",
    0x9C76FB3AL -> "Rastan Saga (J)",
    0x7D59283BL -> "Scramble Spirits (TW)",
    0x89EFCC22L -> "Secret Command (TW)",
    0xD0263024L -> "Seishun Scandal (TW)",
    0xAB67C6BDL -> "Shadow Dancer - The Secret of Shinobi (TW)",
    0xAC2EA669L -> "Shadow of the Beast (TW)",
    0x63A7F906L -> "Strider (TW)",
    0xD282EF71L -> "Submarine Attack (TW)",
    0x7BB81E3DL -> "Taito Chase H.Q. (J)",
    0x98CF1254L -> "Thunder Blade (TW)",
  )
  require(Crcs.map(_._1).distinct.size == Crcs.size)

  /** The ROM with this CRC-32 runs in Master System mode. */
  def matches(crc: UInt): Bool = VecInit(Crcs.map { case (c, _) => crc === c.U(32.W) }).asUInt.orR

  /** Config.colors: 0 = Auto (from the list), 1 = Game Gear, 2 = Master System (3 = Game Gear). */
  val ColorsAuto = 0
  val ColorsGameGear = 1
  val ColorsSms = 2
}

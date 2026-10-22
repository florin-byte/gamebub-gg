package net.gamebub.core.sms

import chisel3._
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

/** The ROM CRC-32 of the Game Gear core (Master System colors detection), on synthetic data only. */
class RomCrc32Spec extends AnyFunSuite {
  test("software reference: the CRC-32 check value") {
    assert(Crc32("123456789".map(_.toInt)) == 0xCBF43926L)
    assert(Crc32(Seq()) == 0L)
  }

  test("the list: 44 CRCs, Out Run Europa (US) in it") {
    assert(GgSmsColors.Crcs.size == 44)
    assert(GgSmsColors.Crcs.exists(_._1 == 0xF037EC00L))
  }

  /** Pseudo-random bytes (a fixed LCG): synthetic ROM contents. */
  def synthetic(n: Int, seed: Int): Seq[Int] =
    Iterator.iterate(seed)(x => x * 1103515245 + 12345).drop(1).take(n).map(x => (x >>> 16) & 0xFF).toSeq

  /** Feeds the files as the core's replay does: whole 32-bit words (the last one padded with 0xA5). */
  def run(files: Seq[(Seq[Int], Long, Boolean)]): Unit = {
    simulate(new RomCrc32(addressWidth = 24)) { dut =>
      dut.reset.poke(true); dut.clock.step(); dut.reset.poke(false)
      for ((file, want, header) <- files) {
        dut.io.start.poke(true); dut.clock.step(); dut.io.start.poke(false)
        val padded = file ++ Seq.fill((4 - file.size % 4) % 4)(0xA5)
        for ((b, a) <- padded.zipWithIndex) {
          dut.io.valid.poke(true)
          dut.io.address.poke(a)
          dut.io.data.poke(b)
          dut.clock.step()
          dut.io.valid.poke(false)
          if (a % 7 == 0) dut.clock.step() // gaps, as between replayed words
        }
        dut.io.size.poke(file.size)
        assert(dut.io.header.peek().litToBoolean == header)
        assert(dut.io.crc.peek().litValue == BigInt(want), f"size ${file.size}: want $want%08X")
      }
    }
  }

  test("hardware: CRC of the whole file, any size") {
    val sizes = Seq(4096, 1027, 2049, 8190, 32 * 1024)
    run(sizes.zipWithIndex.map { case (n, i) =>
      val d = synthetic(n, i + 1)
      (d, Crc32(d), false)
    })
  }

  test("hardware: the 512-byte copier header is left out") {
    val rom = synthetic(16 * 1024, 7)
    val header = Seq.fill(512)(0x55)
    val odd = synthetic(1024 + 3, 9)
    run(Seq(
      (header ++ rom, Crc32(rom), true),
      (header ++ odd, Crc32(header ++ odd), false), // 1539 bytes: size mod 1024 is not 512, so no header
      (rom, Crc32(rom), false),
    ))
  }

  test("hardware: the list matches") {
    class Match extends Module {
      val io = IO(new Bundle {
        val crc = Input(UInt(32.W))
        val sms = Output(Bool())
      })
      io.sms := GgSmsColors.matches(io.crc)
    }
    simulate(new Match) { dut =>
      for ((crc, _) <- GgSmsColors.Crcs) {
        dut.io.crc.poke(crc)
        assert(dut.io.sms.peek().litToBoolean)
      }
      for (crc <- Seq(0L, 0xFFFFFFFFL, 0xF037EC01L, 0xCBF43926L)) {
        dut.io.crc.poke(crc)
        assert(!dut.io.sms.peek().litToBoolean)
      }
    }
  }
}

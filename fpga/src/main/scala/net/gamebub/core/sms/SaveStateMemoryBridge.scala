package net.gamebub.core.sms

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface

object SaveStateMemoryBridge {
  object State extends ChiselEnum {
    val idle, low, high, respond = Value
  }
}

/**
 * Connects the save-state machine's 64-bit Avalon-style memory port (MiSTer's DDRAM: a command is
 * accepted in a cycle where `busy` is low, read data comes back later with `dataReadValid`) to a 32-bit
 * memory interface. Each 64-bit word is two 32-bit accesses, low half first, so the bytes are in the
 * same order as in MiSTer's DDR3 (little endian). Halves without enabled bytes are not written.
 *
 * Word address `wordBase` maps to byte address `byteBase`; one command at a time.
 */
class SaveStateMemoryBridge(wordBase: BigInt, byteBase: BigInt, addressWidth: Int) extends Module {
  import SaveStateMemoryBridge.State

  val io = IO(new Bundle {
    val address = Input(UInt(29.W))
    val dataWrite = Input(UInt(64.W))
    val byteEnable = Input(UInt(8.W))
    val write = Input(Bool())
    val read = Input(Bool())
    val dataRead = Output(UInt(64.W))
    val dataReadValid = Output(Bool())
    val busy = Output(Bool())

    val mem = Flipped(new MemoryInterface(addressWidth, 32))
  })

  val regState = RegInit(State.idle)
  val regAddress = Reg(UInt(addressWidth.W))
  val regData = Reg(UInt(64.W))
  val regByteEnable = Reg(UInt(8.W))
  val regWrite = Reg(Bool())

  io.busy := regState =/= State.idle
  io.dataRead := regData
  io.dataReadValid := regState === State.respond

  io.mem.enable := regState === State.low || regState === State.high
  io.mem.write := regWrite
  io.mem.address := regAddress + Mux(regState === State.high, 4.U, 0.U)
  io.mem.dataWrite := Mux(regState === State.high, regData(63, 32), regData(31, 0))
  io.mem.writeStrobe := Mux(regState === State.high, regByteEnable(7, 4), regByteEnable(3, 0))

  switch (regState) {
    is (State.idle) {
      when (io.read || io.write) {
        regAddress := (byteBase.U +& ((io.address - wordBase.U) << 3))(addressWidth - 1, 0)
        regData := io.dataWrite
        regByteEnable := Mux(io.read, "hFF".U, io.byteEnable)
        regWrite := io.write
        regState := Mux(io.write && io.byteEnable(3, 0) === 0.U,
          Mux(io.byteEnable(7, 4) === 0.U, State.idle, State.high), State.low)
      }
    }
    is (State.low) {
      when (io.mem.done) {
        when (!regWrite) { regData := Cat(regData(63, 32), io.mem.dataRead) }
        regState := Mux(regWrite && regByteEnable(7, 4) === 0.U, State.idle, State.high)
      }
    }
    is (State.high) {
      when (io.mem.done) {
        when (!regWrite) { regData := Cat(io.mem.dataRead, regData(31, 0)) }
        regState := Mux(regWrite, State.idle, State.respond)
      }
    }
    is (State.respond) {
      regState := State.idle
    }
  }
}

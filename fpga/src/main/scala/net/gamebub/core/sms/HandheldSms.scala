package net.gamebub.core.sms

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, MemoryMap, PipelineInterfaceBridge, PipelineMemoryArbiter, PipelineMemoryInterface, RegisterMap}
import lib.mem.sdram.BurstSdramController
import lib.video.ColorRGB
import net.gamebub.framework.Core
import net.gamebub.framework.interface._
import xilinx.MMCM

object HandheldSms {
  /** MMCM VCO: 50 MHz * 23.625 = 1181.25 MHz. System = VCO / 22 = 53.693182 MHz (NTSC master clock
   *  53.693175 MHz), also used for the SDRAM; host SPI = VCO / 7. */
  val mmcmVcoHz: Double = 50_000_000.toDouble * 23.625

  /** VDP frame: 342 pixels x 262 lines at 53.693175 MHz / 10 (NTSC). */
  val framePeriod: Double = (342 * 262).toDouble / (53_693_175.0 / 10)

  object CommandState extends ChiselEnum {
    val idle, busy, error, done = Value
  }

  /** Register 0x0000, written by the core settings (settings.json). */
  class Config extends Bundle {
    val reserved = UInt(24.W)
    /** Bits 7-6: scaling (VideoScalerV0: 0 = integer, 1 = Sharp Bilinear, 2 = Bilinear, 3 = Nearest) */
    val filter = UInt(2.W)
    /** Bit 5: SG-1000 (TMS9918 palette) */
    val sg = Bool()
    /** Bit 4: no sprite limit per line */
    val noSpriteLimit = Bool()
    /** Bit 3: FM sound (YM2413) */
    val fm = Bool()
    /** Bits 2-1: console region, 0 = from the ROM header, 1 = US/Europe, 2 = Japan */
    val region = UInt(2.W)
    /** Bit 0: PAL video (50 Hz) */
    val pal = Bool()
  }

  /** Video output: 256x240 (SMS window) or 160x144 (Game Gear), 4 bits per channel. */
  def videoInterface(gameGear: Boolean): VideoV0 = new VideoV0(
    videoWidth = if (gameGear) 160 else 256,
    videoHeight = if (gameGear) 144 else 240,
    colorDepthR = 4,
    colorDepthG = 4,
    colorDepthB = 4,
    framePeriod = framePeriod,
  )

  /** Display modes for the platform's aspect scaler: the game picture in the framebuffer. The SMS window
    * is 240 lines with the active lines centered (192, 224 or 240); the Game Gear shows 160x144. Pixels
    * are taken as square, as in the gamebub-0.1 scaler. */
  def scalerInterface(gameGear: Boolean): VideoScalerV0 = new VideoScalerV0(
    if (gameGear) Seq(VideoScalerV0.Mode(0, 0, 160, 144))
    else Seq(
      VideoScalerV0.Mode(0, 24, 256, 192),
      VideoScalerV0.Mode(0, 8, 256, 224),
      VideoScalerV0.Mode(0, 0, 256, 240),
    )
  )

  /** Save state (file 2): the SMS_MiSTer save-state slot, 0x3000 64-bit words. It is kept in SDRAM at
    * StateSdramBase (host address 0x3000_0000 + StateSdramBase), where savestates.sv's slot 0 (DDR word
    * address StateWordBase) is mapped. */
  val FileState = 2
  val StateBytes = 0x3000 * 8
  val StateSdramBase = 0x0100_0000
  val StateWordBase = BigInt(0x07C00000)

  /** Core file IDs (files.json). */
  val FileRom = 0
  val FileSave = 1

  /** Cartridge RAM saved as the .sav file. */
  val SaveBytes = 32 * 1024
}

/** Sega Master System core (also SG-1000 through the `sg` setting). */
class HandheldSms extends HandheldSmsBase(gameGear = false)

/** Sega Game Gear core. */
class HandheldGg extends HandheldSmsBase(gameGear = true)

/**
 * Sega Master System / Game Gear core around SMS_MiSTer (`fpga/vendor/sms_mister/`).
 *
 * The parts of the MiSTer top level (SMS.sv) that this replaces:
 *  - clocks: 53.69 MHz system clock and the clock enables of SMS.sv;
 *  - ROM: loaded by the host into SDRAM (file 0) and read back for the Z80; the bytes written by the
 *    host are replayed into the core's download port (ROMAD/ROMDT/ROMEN), which the mapper detection
 *    uses, as the MiSTer download stream does;
 *  - work RAM and cartridge RAM in block RAM; the cartridge RAM is file 1 (.sav), reported as empty
 *    (0 bytes) when the game did not write it, so no .sav is created for games without saves;
 *  - video: a 256x240 window (SMS) centered on the 192/224/240 active lines, the border lines drawn by
 *    the VDP; 160x144 for the Game Gear.
 */
class HandheldSmsBase(gameGear: Boolean) extends Module with Core {
  import HandheldSms._

  val displayDivider = (mmcmVcoHz / ClocksV0.getClockDisplayHz(framePeriod)._1).floor.toInt
  val io = IO(new Bundle {
    val clocks = new ClocksV0(
      clockSystemHz = (mmcmVcoHz / 22).toInt,
      clockDisplayHz = (mmcmVcoHz / displayDivider).toInt,
      clockSpiHz = (mmcmVcoHz / 7).toInt,
    )
    val video = videoInterface(gameGear)
    val videoScaler = scalerInterface(gameGear)
    val audio = new AudioV0()
    val host = new HostV0()
    val input = new InputV0()
    val sdram = new SdramV0()
  })

  // Clocks
  val mmcm = Module(new MMCM(
    clockInHz = 50_000_000,
    divide = 1,
    multiply = 23.625,
    clockOutConfig = Seq(
      MMCM.ClockOut(22),             // System (also the SDRAM clock)
      MMCM.ClockOut(displayDivider), // Display
      MMCM.ClockOut(7),              // Host SPI
    )
  ))
  mmcm.io.clockIn := io.clocks.clockIn50M
  mmcm.io.powerDown := false.B
  io.clocks.clockOutSystem := mmcm.io.clockOuts(0)
  io.clocks.clockOutDisplay := mmcm.io.clockOuts(1)
  io.clocks.clockOutSpi := mmcm.io.clockOuts(2)
  io.clocks.locked := mmcm.io.locked

  val core = Module(new SmsCore(gameGear))
  io.host <> core.io.host
  io.video <> core.io.video
  io.videoScaler <> core.io.videoScaler
  io.audio <> core.io.audio
  core.io.buttons := io.input.buttons


  //////////////////////////////////
  // SDRAM controller
  //////////////////////////////////
  // The SDRAM runs on the system clock (53.69 MHz), without a clock crossing. At 107 MHz (2x) the
  // data read back from the SDRAM was corrupted on the console (13 Oct 2026: ROM read-back through
  // the host path differed from the file and between reads); the gamebub-0.1 SMS core had the same
  // result at 107.5 MHz and worked with the SDRAM at the system clock. Eli's cores run it at up to
  // 67 MHz (GBA). The board's SDRAM pins have no I/O timing constraints.
  {
    val clockSdramHz = io.clocks.clockSystemHz
    val config = BurstSdramController.Config(
      clockFrequency = clockSdramHz,
      accessLength = 2,
      timeRsc = (2 * 1_000_000_000) / clockSdramHz, /* 2 clocks */
      timeWr = (2 * 1_000_000_000) / clockSdramHz, /* 2 clocks */
      enableBurst = false,
    )
    val controller = Module(new BurstSdramController(config))
    controller.io.mem <> core.io.sdram

    io.sdram.clock := clock
    io.sdram.cke := controller.io.signals.cke
    io.sdram.cs := controller.io.signals.cs
    io.sdram.ras := controller.io.signals.ras
    io.sdram.cas := controller.io.signals.cas
    io.sdram.we := controller.io.signals.we
    io.sdram.dqm := controller.io.signals.dqm
    io.sdram.bank := controller.io.signals.bank
    io.sdram.address := controller.io.signals.address
    controller.io.signals.dataIn := io.sdram.dataIn
    io.sdram.dataOut := controller.io.signals.dataOut
    io.sdram.dataDir := controller.io.signals.dataDir
  }
}

/**
 * The SMS core logic, without the clock generation and the SDRAM controller (so that it can be
 * simulated): host interface, ROM/replay, RAMs, the SMS_MiSTer system and video entities, video
 * window, audio, controller. Runs on the system clock; SDRAM through a pipelined memory interface.
 */
class SmsCore(gameGear: Boolean) extends Module {
  import HandheldSms._

  val io = IO(new Bundle {
    val host = new HostV0()
    val video = videoInterface(gameGear)
    val videoScaler = scalerInterface(gameGear)
    val audio = new AudioV0()
    val buttons = Input(new InputV0.Buttons)
    val sdram = Flipped(new PipelineMemoryInterface(addressWidth = 25, dataWidth = 32))
  })

  // Core state
  val regCoreSetup = RegInit(false.B)
  val regCoreReset = RegInit(true.B)
  val regCoreFocus = RegInit(false.B)
  val regCoreResetOnce = RegInit(false.B)
  regCoreResetOnce := false.B
  val configReg = RegInit(0.U.asTypeOf(new Config))

  // ROM state: set when file 0 has been loaded (and its size is known)
  val regRomLoaded = RegInit(false.B)
  val regRomMask = RegInit(0.U(24.W))
  val regRomMask512 = RegInit(0.U(24.W))
  val regRomHeader512 = RegInit(false.B)
  val regHeaderRegion = RegInit(0.U(4.W))
  // Cartridge RAM written by the game since the .sav was loaded
  val regSaveDirty = RegInit(false.B)
  // ROM read statistics (SDRAM latency, in system clocks), cleared by a write
  val statRegRomReads = RegInit(0.U(32.W))
  val statRegRomSlowReads = RegInit(0.U(32.W))
  val statRegRomMaxLatency = RegInit(0.U(16.W))
  // Save state: a save or load is requested from savestates.sv (held until it freezes the core: it
  // ignores requests for 500 ms after an operation), then in progress until it releases the core.
  val regStateSave = RegInit(false.B)
  val regStateLoad = RegInit(false.B)
  val regStateFrozen = RegInit(false.B)
  // Signature of the loaded ROM, as SMS.sv computes it from the download stream (rejects a state saved
  // with another ROM).
  val regGameId = RegInit(0.U(32.W))

  //////////////////////////////////
  // Host memory map
  //////////////////////////////////
  val registerInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val sdramHost = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  val saveInterface = Wire(new MemoryInterface(addressWidth = 15, dataWidth = 32))
  val commandInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val memoryMap = MemoryMap(
    addressWidth = 32,
    dataWidth = 32,
    entries = Seq(
      0x0.U(4.W) -> registerInterface,
      0x3.U(4.W) -> sdramHost,
      0x4.U(4.W) -> saveInterface,
      0xF0.U(8.W) -> commandInterface,
    ))
  io.host.mem.unsafe :<>= memoryMap.unsafe
  memoryMap.writeStrobe := "b1111".U

  registerInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      0x0000 -> RegisterMap.Entry.rw(configReg),
      0x1000 -> RegisterMap.Entry.rw(statRegRomReads),
      0x1004 -> RegisterMap.Entry.rw(statRegRomSlowReads),
      0x1008 -> RegisterMap.Entry.rw(statRegRomMaxLatency),
      0x2000 -> RegisterMap.Entry.w(regCoreResetOnce),
    )
  )

  //////////////////////////////////
  // ROM download replay
  //////////////////////////////////
  // While file 0 is written, each 32-bit word the host writes to SDRAM is also fed byte by byte to
  // the core's download port. The next host write waits until the previous word has been replayed.
  val regReplayActive = RegInit(false.B)
  val regReplayBytes = RegInit(0.U(3.W))
  val regReplayWord = Reg(UInt(32.W))
  val regReplayAddress = Reg(UInt(25.W))
  val regReplayTimer = RegInit(0.U(2.W))
  val replayEnable = WireDefault(false.B)
  val replayAddress = WireDefault(regReplayAddress)
  val replayData = WireDefault(regReplayWord(7, 0))
  when (regReplayBytes =/= 0.U) {
    regReplayTimer := regReplayTimer + 1.U
    when (regReplayTimer === 0.U) {
      // One byte every 4 clocks.
      replayEnable := true.B
      regReplayWord := regReplayWord >> 8
      regReplayAddress := regReplayAddress + 1.U
      regReplayBytes := regReplayBytes - 1.U
      // ROM header region code (byte 0x7FFF, upper nibble).
      when (regReplayAddress === 0x7FFF.U) {
        regHeaderRegion := regReplayWord(7, 4)
      }
      regGameId := Cat(regGameId(30, 0), regGameId(31)) ^ regReplayWord(7, 0) ^
        Cat(regReplayAddress(0), regReplayAddress(8), regReplayAddress(16))
    }
  }

  // SDRAM: host (with the replay tap) and ROM reads
  val sdramArbiter = Module(new PipelineMemoryArbiter(addressWidth = 25, dataWidth = 32, n = 3))
  val holdHostWrite = regReplayActive && regReplayBytes =/= 0.U
  locally {
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.dest <> sdramArbiter.io.initiator(0)
    val source = bridge.io.source
    source.address := sdramHost.address
    source.write := sdramHost.write
    source.dataWrite := sdramHost.dataWrite
    source.writeStrobe := sdramHost.writeStrobe
    source.enable := sdramHost.enable && !holdHostWrite
    sdramHost.dataRead := source.dataRead
    sdramHost.done := source.done
    when (regReplayActive && source.done && sdramHost.write) {
      regReplayWord := sdramHost.dataWrite
      regReplayAddress := sdramHost.address
      regReplayBytes := 4.U
      regReplayTimer := 0.U
    }
  }

  //////////////////////////////////
  // Commands
  //////////////////////////////////
  val commandHostState = RegInit(CommandState.idle)
  val regCommandHost = Reg(Vec(4, UInt(32.W)))
  val regRomEndPending = RegInit(false.B)
  commandInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries =
      regCommandHost.zipWithIndex.map { case (reg, i) => (0x0000 + (4 * i) -> RegisterMap.Entry.rw(reg)) }
  )
  io.host.commandHost.busy := commandHostState === CommandState.busy
  io.host.commandHost.done := commandHostState === CommandState.done
  io.host.commandHost.error := commandHostState === CommandState.error
  when (io.host.commandHost.request) {
    when (commandHostState === CommandState.idle) {
      val command = regCommandHost(0)(15, 0)
      val fileId = regCommandHost(1)(15, 0)
      for (reg <- regCommandHost) {
        reg := 0.U
      }
      commandHostState := CommandState.done

      when (command === HostV0.CommandGetStatus.U) {
        when (regCoreSetup) {
          regCommandHost(0) := Mux(regCoreReset, HostV0.StatusCoreHalt.U, HostV0.StatusCoreRun.U)
        } .otherwise {
          regCommandHost(0) := HostV0.StatusSetup.U
        }
      } .elsewhen (command === HostV0.CommandSetupComplete.U) {
        regCoreSetup := true.B
      } .elsewhen (command === HostV0.CommandCoreRun.U) {
        regCoreReset := false.B
      } .elsewhen (command === HostV0.CommandCoreHalt.U) {
        regCoreReset := true.B
      } .elsewhen (command === HostV0.CommandNotifyFocus.U) {
        regCoreFocus := regCommandHost(1)(0)
      } .elsewhen (command === HostV0.CommandFileWriteStart.U) {
        when (fileId === FileRom.U) {
          regReplayActive := true.B
          regRomLoaded := false.B
          regHeaderRegion := 0.U
          regGameId := "h811C9DC5".U
        } .elsewhen (fileId === FileSave.U) {
          regSaveDirty := false.B
        }
      } .elsewhen (command === HostV0.CommandFileWriteEnd.U) {
        when (fileId === FileRom.U) {
          // Finish once the last word has been replayed.
          val size = regCommandHost(2)(23, 0)
          regRomMask := smear(size - 1.U)
          regRomMask512 := smear(size - 513.U)
          regRomHeader512 := size(9, 0) === 512.U
          regRomEndPending := true.B
          commandHostState := CommandState.busy
        } .elsewhen (fileId === FileState.U) {
          // The host has written a state into SDRAM: restore it.
          regStateLoad := regRomLoaded
          commandHostState := Mux(regRomLoaded, CommandState.busy, CommandState.error)
        }
      } .elsewhen (command === HostV0.CommandFileReadStart.U) {
        when (fileId === FileSave.U) {
          // Nothing to save unless the game wrote its cartridge RAM.
          regCommandHost(0) := Mux(regSaveDirty, SaveBytes.U, 0.U)
        } .elsewhen (fileId === FileState.U) {
          // Save the state into SDRAM; the host reads it once the command is done.
          regStateSave := regRomLoaded
          commandHostState := Mux(regRomLoaded, CommandState.busy, CommandState.error)
        }
      } .elsewhen (command(15, 8) === 0x03.U) {
        // Other file commands: nothing to do.
      } .otherwise {
        commandHostState := CommandState.error
      }
    }
  } .otherwise {
    commandHostState := CommandState.idle
    // The host gave up waiting (timeout): stop requesting. An operation already started completes.
    regStateSave := false.B
    regStateLoad := false.B
  }
  when (regRomEndPending && regReplayBytes === 0.U) {
    regRomEndPending := false.B
    regReplayActive := false.B
    regRomLoaded := true.B
    commandHostState := CommandState.done
  }
  io.host.commandCore.request := false.B

  /** All bits below the highest set bit, set: the address mask of a ROM of size `x + 1`. */
  private def smear(x: UInt): UInt = {
    val bits = (0 until x.getWidth).map(i => (x >> i).asUInt.orR)
    VecInit(bits).asUInt
  }

  //////////////////////////////////
  // SMS core
  //////////////////////////////////
  // A save state is taken with the menu open (no focus): the core runs until savestates.sv has reached
  // a clean point and frozen it (at most about a frame), and until it releases it again.
  val stateFreeze = Wire(Bool())
  val stateBusy = regStateSave || regStateLoad || regStateFrozen || stateFreeze
  val running = regRomLoaded && !regCoreReset && (regCoreFocus || stateBusy)
  val coreReset = regCoreReset || regCoreResetOnce || !regRomLoaded

  // Clock enables, as SMS.sv: VDP 1/5, pixel 1/10, CPU 1/15, sprites 1/2 of the system clock. They
  // stop while the core is paused.
  val clockDivider = RegInit(0.U(5.W))
  clockDivider := Mux(clockDivider === 29.U, 0.U, clockDivider + 1.U)
  private def at(steps: Int*): Bool = steps.map(clockDivider === _.U).reduce(_ || _)
  val ceVdp = RegNext(running && at(29, 24, 19, 14, 9, 4), false.B)
  val cePix = RegNext(running && at(29, 19, 9), false.B)
  val ceCpu = RegNext(running && at(24, 9), false.B)
  val ceSp = RegNext(running && clockDivider(0), false.B)

  val video = Module(new SmsVideo)
  val sms = Module(new SmsSystem)
  val sp = sms.io

  // Defaults: every input not used below is inactive.
  sp.elements.foreach { case (_, data) =>
    if (chisel3.reflect.DataMirror.directionOf(data) == chisel3.ActualDirection.Input && data.isInstanceOf[UInt]) {
      data := 0.U
    }
  }
  sp.clk_sys := clock
  // Frozen during a save state; savestates.sv and the video counters get the enables unfrozen (SMS.sv).
  sp.ss_freeze := stateFreeze
  sp.ce_cpu := ceCpu && !stateFreeze
  sp.ce_vdp := ceVdp && !stateFreeze
  sp.ce_pix := cePix && !stateFreeze
  sp.ce_sp := ceSp && !stateFreeze
  sp.gg := gameGear.B
  sp.ggres := gameGear.B
  sp.RESET_n := !coreReset
  sp.dbr := regRomLoaded
  val pal = configReg.pal && !gameGear.B
  sp.pal := pal
  sp.region := Mux(configReg.region === 0.U, regHeaderRegion === 3.U, configReg.region === 2.U)
  sp.fm_ena := configReg.fm && !gameGear.B
  sp.sp64 := configReg.noSpriteLimit
  sp.palettemode := configReg.sg && !gameGear.B
  sp.ROMCL := clock.asUInt.asBool
  sp.ROMAD := replayAddress
  sp.ROMDT := replayData
  sp.ROMEN := replayEnable

  // Controller (pins active low); Start is the SMS Pause button and the Game Gear Start button.
  val buttons = io.buttons
  sp.j1_up := !buttons.up
  sp.j1_down := !buttons.down
  sp.j1_left := !buttons.left
  sp.j1_right := !buttons.right
  sp.j1_tl := !buttons.a
  sp.j1_tr := !buttons.b
  sp.j1_th := true.B
  sp.j1_start := !buttons.start
  sp.j1_coin := true.B
  sp.j1_a3 := !buttons.start
  sp.pause := !buttons.start
  for (pin <- Seq(sp.j2_up, sp.j2_down, sp.j2_left, sp.j2_right, sp.j2_tl, sp.j2_tr, sp.j2_th,
                  sp.j2_start, sp.j2_coin, sp.j2_a3)) {
    pin := true.B
  }
  sp.E0 := 0xFF.U
  sp.F2 := 0xFF.U
  sp.F3 := 0xFF.U
  sp.sk1100_row_data := 0xFFF.U

  // Video counters
  video.io.clk := clock
  video.io.ce_pix := cePix
  video.io.pal := pal
  video.io.border := (!gameGear).B
  video.io.ggres := gameGear.B
  video.io.mask_column := sp.mask_column
  video.io.cut_mask := false.B
  video.io.smode_M1 := sp.smode_M1
  video.io.smode_M2 := sp.smode_M2
  video.io.smode_M3 := sp.smode_M3
  video.io.smode_M4 := sp.smode_M4
  sp.x := video.io.x
  sp.y := video.io.y
  sp.vcounter_cpu := video.io.vcounter_cpu

  //////////////////////////////////
  // Save states (SMS_MiSTer savestates.sv)
  //////////////////////////////////
  val states = Module(new SmsSaveStates)
  val ss = states.io
  stateFreeze := ss.ss_freeze
  ss.clk := clock
  ss.reset_n := !coreReset
  ss.ss_save := regStateSave
  ss.ss_load := regStateLoad
  ss.ss_slot := 0.U
  ss.ss_bios_mode := false.B
  ss.ss_game_id := regGameId
  ss.vblank := video.io.vblank
  ss.x := video.io.x
  ss.cpu_ce := ceCpu
  ss.vdp_ce := ceVdp
  ss.pix_ce := cePix
  ss.sp_ce := ceSp
  ss.systeme := false.B
  // Z80
  ss.z80_reg := sp.z80_reg_out
  sp.z80_dir := ss.z80_dir
  sp.z80_set := ss.z80_set
  ss.z80_m1_n := sp.z80_m1_n
  ss.z80_mreq_n := sp.z80_mreq_n
  ss.z80_iset := sp.z80_iset
  // VDP registers, CRAM, VRAM
  ss.vdp_regs := sp.vdp_regs_out
  sp.vdp_regs_in := ss.vdp_regs_in
  sp.vdp_regs_set := ss.vdp_regs_set
  ss.cram_out := sp.vdp_cram_out
  sp.ss_cram_A := ss.cram_A
  sp.ss_cram_D := ss.cram_D
  sp.ss_cram_wr := ss.cram_wr
  sp.ss_vram_en := ss.vram_en
  sp.ss_vram_A := ss.vram_A
  ss.vram_D := sp.ss_vram_D
  sp.ss_vram_WE := ss.vram_WE
  sp.ss_vram_WA := ss.vram_WA
  sp.ss_vram_WD := ss.vram_WD
  // PSG and FM
  ss.psg_out := sp.psg_out
  sp.psg_in := ss.psg_in
  sp.psg_set := ss.psg_set
  ss.psg_ext_out := sp.psg_ext_out
  ss.psg2_ext_out := sp.psg2_ext_out
  sp.psg_ext_in := ss.psg_ext_in
  sp.psg2_ext_in := ss.psg2_ext_in
  ss.psg_div_out := sp.psg_div_out
  sp.psg_div_in := ss.psg_div_in
  ss.audio_control_out := sp.audio_control_out
  sp.audio_control_in := ss.audio_control_in
  ss.audio_quiescent := sp.audio_quiescent
  ss.audio_phase_out := sp.audio_phase_out
  sp.audio_phase_in := ss.audio_phase_in
  sp.audio_phase_set := ss.audio_phase_set
  sp.audio_ext_set := ss.audio_ext_set
  // Mapper, Master System Evolution, EEPROM, I/O
  ss.mapper_out := sp.mapper_out
  sp.mapper_in := ss.mapper_in
  sp.mapper_set := ss.mapper_set
  ss.evolution_out := sp.evolution_ss_out(95, 0) // a 96-bit wire in SMS.sv
  sp.evolution_ss_in := ss.evolution_in
  sp.evolution_ss_set := ss.evolution_set
  ss.eeprom_out := sp.eeprom_ss_out
  sp.eeprom_ss_in := ss.eeprom_in
  sp.eeprom_ss_set := ss.eeprom_set
  ss.io_out := sp.io_state_out
  sp.io_state_in := ss.io_in
  sp.io_state_set := ss.io_set
  // Video counters
  ss.video_state_out := video.io.video_state_out
  video.io.video_state_in := ss.video_state_in
  video.io.video_state_set := ss.video_state_set
  // System E (second VDP and PSG): not used.
  ss.vdp2_regs := sp.vdp2_regs_out
  ss.cram2_out := sp.vdp2_cram_out
  ss.vram2_D := sp.ss_vram2_D
  ss.psg2_out := sp.psg2_out

  // Operation finished: savestates.sv has frozen and released the core.
  when (stateFreeze) {
    regStateFrozen := true.B
    regStateSave := false.B
    regStateLoad := false.B
  } .elsewhen (regStateFrozen) {
    regStateFrozen := false.B
    when (commandHostState === CommandState.busy) {
      commandHostState := CommandState.done
      regCommandHost(0) := StateBytes.U // read start: size of the state file
    }
  }

  // Memory: savestates.sv's slot 0 in SDRAM.
  locally {
    val memory = Module(new SaveStateMemoryBridge(StateWordBase, BigInt(StateSdramBase), addressWidth = 25))
    memory.io.address := ss.DDRAM_ADDR
    memory.io.dataWrite := ss.DDRAM_DIN
    memory.io.byteEnable := ss.DDRAM_BE
    memory.io.write := ss.DDRAM_WE
    memory.io.read := ss.DDRAM_RD
    ss.DDRAM_DOUT := memory.io.dataRead
    ss.DDRAM_DOUT_READY := memory.io.dataReadValid
    ss.DDRAM_BUSY := memory.io.busy
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.dest <> sdramArbiter.io.initiator(2)
    bridge.io.source <> memory.io.mem
  }

  // Work RAM, 16 KiB (savestates.sv reads and writes it while the core is frozen)
  val workRam = SyncReadMem(16 * 1024, UInt(8.W))
  sp.ram_q := workRam.readWrite(
    Mux(stateFreeze, Mux(ss.wram_WE, ss.wram_WA, ss.wram_A), sp.ram_a),
    Mux(stateFreeze, ss.wram_WD, sp.ram_d),
    true.B,
    Mux(stateFreeze, ss.wram_WE, sp.ram_we))
  ss.wram_D := sp.ram_q

  // Cartridge RAM, 32 KiB, as 32-bit words: byte accesses from the core, word accesses from the host
  // (file 1; core files are transferred in 32-bit words).
  val saveRam = SRAM.masked(SaveBytes / 4, Vec(4, UInt(8.W)), 0, 0, 2)
  val corePort = saveRam.readwritePorts(0)
  // savestates.sv reads and writes it while the core is frozen (states of the Dahjee mapper).
  val nvramAddress = Mux(stateFreeze, Mux(ss.nvram_WE, ss.nvram_WA, ss.nvram_A), sp.nvram_a)
  val nvramWrite = Mux(stateFreeze, ss.nvram_WE, sp.nvram_we)
  corePort.enable := true.B
  corePort.address := nvramAddress(14, 2)
  corePort.isWrite := nvramWrite
  corePort.writeData := VecInit(Seq.fill(4)(Mux(stateFreeze, ss.nvram_WD, sp.nvram_d)))
  corePort.mask.get := UIntToOH(nvramAddress(1, 0), 4).asBools
  sp.nvram_q := corePort.readData(RegNext(nvramAddress(1, 0)))
  ss.nvram_D := sp.nvram_q
  when (sp.nvram_we && running) {
    regSaveDirty := true.B
  }
  val hostPort = saveRam.readwritePorts(1)
  hostPort.enable := saveInterface.enable
  hostPort.address := saveInterface.address(14, 2)
  hostPort.isWrite := saveInterface.write
  hostPort.writeData := saveInterface.dataWrite.asTypeOf(Vec(4, UInt(8.W)))
  // The host always transfers whole 32-bit words. (A constant all-ones mask comes out of firtool as
  // 4'h1, writing only the first byte; the mask follows the write enable instead.)
  hostPort.mask.get := VecInit(Seq.fill(4)(saveInterface.write))
  saveInterface.dataRead := hostPort.readData.asUInt
  saveInterface.done := RegNext(saveInterface.enable && !saveInterface.done, false.B)

  //////////////////////////////////
  // ROM reads from SDRAM
  //////////////////////////////////
  // The core has no read strobe (rom_rd is not driven by system.vhd): a read starts as soon as the
  // address differs from the one last read, so that a new address does not wait behind a read of
  // the previous one. The last byte read is kept.
  val romAddress = Mux(regRomHeader512,
    (sp.rom_a + 512.U) & regRomMask512,
    sp.rom_a & regRomMask)
  val regRomReading = RegInit(false.B)
  val regRomReadAddress = Reg(UInt(24.W))
  val regRomData = RegInit(0xFF.U(8.W))
  val regRomDataValid = RegInit(false.B)
  locally {
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.dest <> sdramArbiter.io.initiator(1)
    val rom = bridge.io.source
    rom.enable := regRomReading
    rom.write := false.B
    rom.address := Cat(regRomReadAddress(23, 2), 0.U(2.W))
    rom.dataWrite := DontCare
    rom.writeStrobe := DontCare
    when (!regRomLoaded) {
      regRomDataValid := false.B
    }
    when (!regRomReading && regRomLoaded && (!regRomDataValid || romAddress =/= regRomReadAddress)) {
      regRomReading := true.B
      regRomReadAddress := romAddress
    }
    val regLatency = RegInit(0.U(16.W))
    when (regRomReading) {
      regLatency := regLatency + 1.U
    } .otherwise {
      regLatency := 0.U
    }
    when (regRomReading && rom.done) {
      statRegRomReads := statRegRomReads + 1.U
      when (regLatency > 16.U) {
        statRegRomSlowReads := statRegRomSlowReads + 1.U
      }
      when (regLatency > statRegRomMaxLatency) {
        statRegRomMaxLatency := regLatency
      }
      regRomReading := false.B
      regRomDataValid := true.B
      regRomData := rom.dataRead.asTypeOf(Vec(4, UInt(8.W)))(regRomReadAddress(1, 0))
    }
  }
  sp.rom_do := regRomData

  //////////////////////////////////
  // Video
  //////////////////////////////////
  // The color on the output belongs to the position sampled at the previous pixel clock enable.
  val pixelValid = RegNext(cePix, false.B)
  val pixelX = RegEnable(video.io.x, cePix)
  val pixelY = RegEnable(video.io.y, cePix)
  val color = sp.color
  io.video.data.r := color(3, 0)
  io.video.data.g := color(7, 4)
  io.video.data.b := color(11, 8)
  val lines224 = sp.smode_M1 && sp.smode_M2
  val lines240 = sp.smode_M3 && sp.smode_M2
  // Scaler mode: the index in scalerInterface's modes.
  io.videoScaler.mode := (if (gameGear) 0.U else Mux(lines240, 2.U, Mux(lines224, 1.U, 0.U)))
  io.videoScaler.filter := configReg.filter
  if (gameGear) {
    // 160x144 window from the VDP blanking (ggres).
    io.video.hblank := video.io.hblank
    io.video.vblank := video.io.vblank
    io.video.dataEnable := pixelValid && !video.io.hblank && !video.io.vblank
  } else {
    // 256x240 window centered on the active lines (192, 224 or 240); the VDP draws the border.
    val top = Mux(lines240, 0.U, Mux(lines224, 8.U, 24.U))
    val activeLines = Mux(lines240, 240.U, Mux(lines224, 224.U, 192.U))
    val inWindow = (pixelY < activeLines + top) || (pixelY >= 512.U - top)
    val inLine = pixelX < 256.U
    io.video.hblank := !(inWindow && inLine)
    io.video.vblank := !inWindow
    io.video.dataEnable := pixelValid && inWindow && inLine
  }

  //////////////////////////////////
  // Audio
  //////////////////////////////////
  io.audio.left := sp.audioL.asSInt
  io.audio.right := sp.audioR.asSInt

  io.sdram <> sdramArbiter.io.target
}

package net.gamebub.core.sms

import _root_.circt.stage.ChiselStage

/** SystemVerilog of SmsCore for the Verilator simulation (scripts/rev2/sms_test_sim.sh core). */
object SimSms extends App {
  val gameGear = args.contains("--game-gear")
  ChiselStage.emitSystemVerilogFile(
    new SmsCore(gameGear),
    args.filterNot(_ == "--game-gear"),
    firtoolOpts = Array("--preserve-aggregate=1d-vec"),
  )
}

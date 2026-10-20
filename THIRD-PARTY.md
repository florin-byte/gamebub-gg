# Third-party notices

This repository is a port of other people's work. Every component keeps its own copyright headers and license text;
this page lists them in one place, with what was written for this port.

## The Master System / Game Gear core (`fpga/vendor/sms_mister/`)

From [SMS_MiSTer](https://github.com/MiSTer-devel/SMS_MiSTer) @ `e7a4086` (local changes listed in
`fpga/vendor/sms_mister/README.md`). SMS_MiSTer is a port of Ben's Sega Master System implementation for the Papilio
(http://fpga-hacks.blogspot.de/), extended by the MiSTer contributors.

**SMS_MiSTer has no license file**, and most of its files (`system.vhd`, `vdp*.vhd`, `video.vhd`, `io.vhd`, the
mapper and memory files, `savestates.sv`, `cheatcodes.sv`, ...) carry no license header. They remain their authors'
work; they are included here as the community distributes the MiSTer cores. If you are one of the authors and want
something changed or removed, please open an issue.

| Component | Files | Authors | License (from the file headers) |
| --- | --- | --- | --- |
| T80 (Z80 CPU) | `rtl/T80/` | Daniel Wallner; changes by Sorgelig and others | BSD-style |
| VM2413 (YM2413 FM sound) | `rtl/VM2413/` | Mitsutaka Okazaki | BSD-style **with a non-commercial clause**: "Redistributions may not be sold, nor may they be used in a commercial product or activity without specific prior written permission". The Game Gear core never enables FM (`fm_ena` is tied off for the Game Gear in `HandheldSms.scala`), but the files are part of the sources |
| 93C46 serial EEPROM (cartridges that save to an EEPROM) | `rtl/eeprom_93c46.vhd` (ported 1:1 from Genesis Plus GX's `eeprom_93c.c`), `rtl/cart_eeprom.vhd` | Eke-Eke (Genesis Plus GX); the VHDL port: SMS_MiSTer contributors | Genesis Plus GX's license for `eeprom_93c.c`: BSD-style **with a non-commercial clause** ("Redistributions may not be sold, nor may they be used in a commercial product or activity") and a complete-source condition for modified versions |
| JT89 (SN76489 sound) | `rtl/jt89/` | Jose Tejada Gomez (jotego) | GPL-3.0-or-later (the `.v` file headers) |
| Audio mixer | `rtl/AudioMix.vhd` | Alastair M. Robinson | copyright notice only, no license text |
| MC8123 and System 1 decryption, `parts.v` (arcade System E) | `rtl/MC8123.v`, `rtl/SEGASYS1_PRGDEC.v`, `rtl/parts.v` | blackwine (MC8123, algorithm from MAME); MiSTer-X | copyright notices only, no license text |

## Everything else

| Component | Where | Authors | License |
| --- | --- | --- | --- |
| Game Bub FPGA framework, platform and built-in cores (boot, Game Boy, Game Boy Advance) | `fpga/` (except the files listed here as other authors' or Florin's) | Eli Lipsitz and Game Bub contributors | CERN-OHL-S-2.0 (`LICENSE-CERN-OHL-S`) |
| Game Bub firmware and scripts | `firmware/`, `fpga/scripts/` | Eli Lipsitz and Game Bub contributors | GPL-3.0-only (`LICENSE-GPL-V3`) |
| Audio decimator, audio rate adapter, DC blocker, low-priority memory mux, `BUFG` / `PLL` wrappers; the SDRAM controller's `readCaptureFalling` option | `fpga/src/main/scala/lib/audio/AudioDecimator.scala`, `AudioRateAdapter.scala`, `AudioDcBlocker.scala`, `lib/mem/PipelineMemoryLowPriorityMux.scala`, `xilinx/BUFG.scala`, `xilinx/PLL.scala`, `lib/mem/sdram/BurstSdramController.scala` | minuitfranck, from his Mega Drive port for the Game Bub | CERN-OHL-S-2.0, as part of the framework |
| Line buffer and line read cache, `BUFGCE` wrapper, byte-port bridge, SRAM host adapter; the options `fair` / `handover` (`MemoryArbiter`), `registeredOutputs` / `handover` (`AsyncSramController`), `safeWrites` (`PipelineMemoryBurstCdc`), `passThroughUntilLoaded` (`ColorCorrection`), `prechargeAll` (`BurstSdramController`) | `fpga/src/main/scala/lib/mem/cache/LineBuffer.scala`, `LineReadCache.scala`, `xilinx/BUFGCE.scala`, `lib/mem/sram/BytePortBridge.scala`, `SramHostAdapter.scala`, and the modules named | Jeremy O'Brien (neutralinsomniac), from his SNES port for the Game Bub (the bridge and the adapter through minuitfranck's NES port) | CERN-OHL-S-2.0, as part of the framework |
| `VideoV0.framePeriodSlow` and the ILI9488 driver's slow line (50 Hz pictures on the rev2 LCD) | `fpga/src/main/scala/net/gamebub/framework/interface/VideoV0.scala`, `platform/handheld/display/ILI9488.scala` | minuitfranck, from his NES port for the Game Bub | CERN-OHL-S-2.0, as part of the framework |
| HDMI transmitter (Eli Lipsitz's fork of hdl-util/hdmi; a submodule upstream, included as files) | `fpga/verilog/hdmi/` | Sameer Puri and contributors | MIT or Apache-2.0 (`fpga/verilog/hdmi/LICENSE-MIT`, `LICENSE-APACHE`) |
| PicoRV32 (the boot design's soft CPU) | `fpga/verilog/picorv32.v` | Claire Xenia Wolf | ISC |
| Mill launchers | `fpga/mill`, `fpga/mill.bat` | The Mill project (com-lihaoyi/mill 1.1.7) | MIT |
| Chisel, Mill, firtool, ESP-IDF and the Rust crates | build time only, not in this repository | their projects | Apache-2.0, MIT and others |

## Written for this port (Florin)

GPL-2.0-or-later (`LICENSE-GPL-V2`, `LICENSE-GPL-V3`):

- the Master System / Game Gear glue: `fpga/src/main/scala/net/gamebub/core/sms/`, `fpga/src/main/vhdl/sms/`,
  `fpga/sim/sms/`, `scripts/rev2/sms_test_sim.sh`, `sdcard/`;
- the LCD aspect scaler: `fpga/src/main/scala/platform/handheld/LcdAspectScaler.scala`,
  `lib/video/PolyphaseScaler.scala`, `net/gamebub/framework/interface/VideoScalerV0.scala` and its wiring in
  `HandheldTop.scala`, `fpga/src/test/scala/platform/handheld/LcdAspectScalerSpec.scala`;
- the Game Boy save-state walker `fpga/src/main/scala/net/gamebub/core/gameboy/GbSaveStateWalker.scala` and the
  simulator `fpga/sim/test_main.cpp`;
- `fpga/src/test/scala/lib/mem/sdram/BurstSdramControllerSpec.scala`;
- the build scripts `scripts/rev2/make_vivado_bundle.py`, `pack_vivado_bundles.py`, `finish_bitstream.py`;
- the "Retro Bub" logo (pixel art; the letters "e" and "Bub" keep the pixels of the original Game Bub logo's font):
  `firmware/handheld/res/logo.png` and the two theme masks `logo-face.png`, `logo-shadow.png`, the boot design's
  `fpga/src/main/resources/logo.png`, all generated by `scripts/rev2/make_logo.py`;
- the firmware additions: `firmware/handheld/src/core/on_demand.rs` (on-demand core files: save states),
  `core/cheats.rs`, `util/atomic_file.rs`, `util/localtime.rs`, `res/ui/theme.slint`,
  `res/ui/components/scrolling_text.slint`. They are part of a GPL-3.0-only program, so the firmware as a whole is
  GPL-3.0-only.

Florin's changes to Eli Lipsitz's files (for example `HandheldTop.scala`, the firmware's `core/`, `ui/` and
`bitstream/` modules, `XpmFifoAsync.scala`, `build_core.py`) are under those files' licenses.

## Not included

No BIOS and no games.

"Game Bub" is Eli Lipsitz's project and trademark; this core is unofficial and uses neither the name nor the logo as
its own. "Sega", "Master System" and "Game Gear" are trademarks of their owners.

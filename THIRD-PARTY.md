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

## Genesis Plus GX: Game Gear games in Master System mode (CRC-32 list)

- Used in: `fpga/src/main/scala/net/gamebub/core/sms/GgSmsColors.scala` (`GgSmsColors.Crcs`), for the Colors setting
  (Auto).
- What: the CRC-32 values (data only, no code) of the `SYSTEM_GGMS` entries with the Sega mapper in the game database
  of `core/cart_hw/sms_cart.c`: the Game Gear games that run in the console's Master System compatibility mode.
- Source: https://github.com/ekeeke/Genesis-Plus-GX, `core/cart_hw/sms_cart.c` (master at
  `49c584764893b0505ac7f768a754f97330fa4392`).
- Authors: Eke-Eke (Genesis Plus GX).
- License: the Genesis Plus GX non-commercial license, reproduced below from the file's header. In particular,
  redistributions may not be sold, nor used in a commercial product or activity. (The 93C46 EEPROM in the table above
  comes from Genesis Plus GX too, under the same kind of license.)

```
Genesis Plus
SG-1000, Master System & Game Gear cartridge hardware support

Copyright (C) 2007-2025  Eke-Eke (Genesis Plus GX)

Credits to Ben Sittler and Omar Cornut at smspower.org for Korean mappers
reverse-engineering and description

Redistribution and use of this code or any derivative works are permitted
provided that the following conditions are met:

 - Redistributions may not be sold, nor may they be used in a commercial
   product or activity.

 - Redistributions that are modified from the original source must include the
   complete source code, including the source code for all components used by a
   binary built from the modified sources. However, as a special exception, the
   source code distributed need not include anything that is normally distributed
   (in either source or binary form) with the major components (compiler, kernel,
   and so on) of the operating system on which the executable runs, unless that
   component itself accompanies the executable.

 - Redistributions must reproduce the above copyright notice, this list of
   conditions and the following disclaimer in the documentation and/or other
   materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

## Everything else

| Component | Where | Authors | License |
| --- | --- | --- | --- |
| Game Bub FPGA framework, platform and built-in cores (boot, Game Boy, Game Boy Advance) | `fpga/` (except the files listed here as other authors' or Florin's) | Eli Lipsitz and Game Bub contributors | CERN-OHL-S-2.0 (`LICENSE-CERN-OHL-S`) |
| Game Bub firmware and scripts | `firmware/`, `fpga/scripts/` | Eli Lipsitz and Game Bub contributors | GPL-3.0-only (`LICENSE-GPL-V3`) |
| Audio decimator, audio rate adapter, DC blocker, low-priority memory mux, `BUFG` / `PLL` wrappers; the SDRAM controller's `readCaptureFalling` option | `fpga/src/main/scala/lib/audio/AudioDecimator.scala`, `AudioRateAdapter.scala`, `AudioDcBlocker.scala`, `lib/mem/PipelineMemoryLowPriorityMux.scala`, `xilinx/BUFG.scala`, `xilinx/PLL.scala`, `lib/mem/sdram/BurstSdramController.scala` | minuitfranck, from his Mega Drive port for the Game Bub | CERN-OHL-S-2.0, as part of the framework |
| Line buffer and line read cache, `BUFGCE` wrapper, byte-port bridge, SRAM host adapter; the options `fair` / `handover` (`MemoryArbiter`), `registeredOutputs` / `handover` (`AsyncSramController`), `safeWrites` (`PipelineMemoryBurstCdc`), `passThroughUntilLoaded` (`ColorCorrection`), `prechargeAll` (`BurstSdramController`) | `fpga/src/main/scala/lib/mem/cache/LineBuffer.scala`, `LineReadCache.scala`, `xilinx/BUFGCE.scala`, `lib/mem/sram/BytePortBridge.scala`, `SramHostAdapter.scala`, and the modules named | Jeremy O'Brien (neutralinsomniac), from his SNES port for the Game Bub (the bridge and the adapter through minuitfranck's NES port) | CERN-OHL-S-2.0, as part of the framework |
| `VideoV0.framePeriodSlow` and the ILI9488 driver's slow line (50 Hz pictures on the rev2 LCD) | `fpga/src/main/scala/net/gamebub/framework/interface/VideoV0.scala`, `platform/handheld/display/ILI9488.scala` | minuitfranck, from his NES port for the Game Bub | CERN-OHL-S-2.0, as part of the framework |
| HDMI transmitter (Eli Lipsitz's fork of hdl-util/hdmi; a submodule upstream, included as files) | `fpga/verilog/hdmi/` | Sameer Puri and contributors | MIT or Apache-2.0 (`fpga/verilog/hdmi/LICENSE-MIT`, `LICENSE-APACHE`) |
| Ark Pixel Font (the firmware's UI font, 12 px proportional, regular and bold) | `firmware/handheld/res/fonts/` | TakWolf (https://github.com/TakWolf/ark-pixel-font) | SIL Open Font License 1.1 (`firmware/handheld/res/fonts/LICENSE-OFL`) |
| PicoRV32 (the boot design's soft CPU) | `fpga/verilog/picorv32.v` | Claire Xenia Wolf | ISC |
| Mill launchers | `fpga/mill`, `fpga/mill.bat` | The Mill project (com-lihaoyi/mill 1.1.7) | MIT |
| Chisel, Mill, firtool, ESP-IDF and the Rust crates | build time only, not in this repository | their projects | Apache-2.0, MIT and others |

## Written for this port (Florin)

GPL-2.0-or-later (`LICENSE-GPL-V2`, `LICENSE-GPL-V3`):

- the Master System / Game Gear glue: `fpga/src/main/scala/net/gamebub/core/sms/` (including the CRC-32 code in
  `GgSmsColors.scala`; the CRC list in it is Genesis Plus GX data, see above), `fpga/src/main/vhdl/sms/`,
  `fpga/sim/sms/`, `fpga/src/test/scala/net/gamebub/core/sms/RomCrc32Spec.scala`, `scripts/rev2/sms_test_sim.sh`,
  `sdcard/`;
- the LCD aspect scaler: `fpga/src/main/scala/platform/handheld/LcdAspectScaler.scala`,
  `lib/video/PolyphaseScaler.scala`, `net/gamebub/framework/interface/VideoScalerV0.scala` and its wiring in
  `HandheldTop.scala`, `fpga/src/test/scala/platform/handheld/LcdAspectScalerSpec.scala`;
- the shared stock save-state controller `fpga/src/main/scala/net/gamebub/core/StockSaveStateSlots.scala`
  and its protocol harness `fpga/sim/stock_savestates/`;
- the Game Boy save-state walker `fpga/src/main/scala/net/gamebub/core/gameboy/GbSaveStateWalker.scala` and the
  simulator `fpga/sim/test_main.cpp`;
- `fpga/src/test/scala/lib/mem/sdram/BurstSdramControllerSpec.scala`;
- the build scripts `scripts/rev2/make_vivado_bundle.py`, `pack_vivado_bundles.py`, `finish_bitstream.py`;
- the custom logo (pixel art; the letters "e" and "Bub" keep the pixels of the original Game Bub logo's font):
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

# Sega Game Gear core for the Game Bub

This is the Sega Game Gear core for the [Game Bub](https://github.com/elipsitz/gamebub), loaded from the SD card. It
is the MiSTer core ([SMS_MiSTer](https://github.com/MiSTer-devel/SMS_MiSTer)) glued to Eli's Game Bub framework
(1.1.0-beta2). It is not an official Game Bub core, I just ported it.

The repo is my fork of the Game Bub code, cut down to what the Game Gear needs: the FPGA framework, the core, the
firmware and the scripts to build it. The same sources also build the Master System / SG-1000 version of the core
(`HandheldSms`), but this repo is about the Game Gear.

If you only want to play, take the zip from the [releases](../../releases), you don't need to build anything.

## New since v0.1.0 (not released yet)

- Scaling has a fourth option, Nearest.
- Colors: Auto / Game Gear / Master System, for the Game Gear games that run in Master System mode.
- Swap A/B in the core's menu.

I tested all three on my rev2 console on 22 Oct 2026 (Colors only with Out Run Europa). rev4 is not tested yet. The
bitstreams for this are not uploaded yet, the ones in the v0.1.0 release don't have these features. Build the
bitstream yourself (see [Building the bitstream](#building-the-bitstream)) or wait for the next release. The json
files in `sdcard/` are already the new ones, so use them with a new bitstream: with the v0.1.0 bitstream, Nearest
looks like Sharp Bilinear, Colors does nothing and Swap A/B shows up in the menu but does not swap.

## Does it work?

- rev2: yes, this is what I use myself. Games, the `.sav` battery saves, the scaler and save states (with my
  firmware) work.
- rev4: yes, tested by other users, but not by me, because I don't own a rev4 Game Bub, only a rev2 one. The
  bitstream in the release is built on my machine with Vivado 2025.2 (timing is met, no critical warnings).

## Install

1. Copy `sdcard/cores/Retro-Bub.GG` to the `cores/` folder on your SD card and put the bitstream next to the json
   files (`gg-gamebub_rev4.bit` for rev4, `gg-gamebub_rev2.bit` for rev2).
2. Games are `.gg`. The battery save goes next to the rom as `<rom>.sav`, it works with the stock firmware too.

## Settings

- Region: Auto (from the rom header) / US-Europe / Japan.
- No Sprite Limit: no limit of sprites per line (less flicker, but not like the real console).
- Scaling: Sharp Bilinear / Bilinear / Nearest / Integer. The picture is 160x144, scaled to the full height of the
  screen. Nearest is the same size as the bilinear ones, but without blending, every screen pixel is one Game Gear
  pixel. The scale is not a whole number (10/3 on rev4), so some pixels are one line wider than the others. If you
  don't like that, use Sharp Bilinear, or Integer for a smaller picture with even pixels.
- Colors: Auto / Game Gear / Master System. Some Game Gear games run in the console's Master System mode (Castle of
  Illusion, Out Run Europa, Prince of Persia, Olympic Gold and a lot of Japanese and Taiwanese ones) and need the
  Master System palette, with the Game Gear one the colors are wrong. Auto finds them by the CRC-32 of the rom (the
  list is from Genesis Plus GX) and switches to the Master System colors, the screen stays 160x144. In that mode
  Start is the Master System Pause button, like on the real console. Game Gear and Master System force one palette,
  for a hack or a dump that is not in the list. Reset the game after you change it.
- Swap A/B (right before Reset): swaps A and B for this core only, at once, without a restart.
  It's off by default and every core keeps its own value. The menus are not swapped. With Settings > Dock > Swap A/B
  and X/Y also on, a docked controller has A and B normal again (X and Y stay swapped). I didn't test it with a dock.
  With the stock firmware it should work the same, the entry is in its `settings.json`. I tested that json on rev2
  with my firmware, not with the stock one and not on rev4.
- Reset.

## Save states

The save states are in the bitstream (the MiSTer save state engine), but they need fw changes as well, to move the
state between the core and the SD card while the game runs. Eli's firmware does not have that, so on the stock
firmware there are no save states. The `.sav` battery saves work anyway.

- Stock firmware: use `sdcard/cores/Retro-Bub.GG`. Everything works except save states. The other folder
  works with it too: the State file is marked read only, so the stock firmware doesn't try to write it at exit and
  there is no error. State Slot, Save State and Load State are in the menu but do nothing there. Swap A/B works in
  both folders, with both firmwares. I didn't test the other folder on the stock firmware yet, I only read Eli's code.
- My firmware (`firmware/handheld/`): use `sdcard/savestate-firmware/cores/Retro-Bub.GG`. Then you have State Slot,
  Save State and Load State (`.ss0` to `.ss3` next to the rom). I tested it on rev2. For rev4 it
  compiles (`cargo check --features rev4`) but nobody ran it on a rev4 yet. Build it like the stock one (ESP-IDF
  5.4.3, `cargo build --release --features rev4`).

## How the save states work in my firmware

Eli's firmware only moves files between the card and the core at the start (the rom, the `.sav`) and at the exit
(the `.sav` back). A save state has to move while the game runs, so I added "on demand" files to the firmware
(`firmware/handheld/src/core/on_demand.rs`). It is generic, any core on the SD card can use it, only with json.

This is what happens when you press Save State in the menu:

1. The firmware sends the core the normal file command `FILE_READ_START` for the State file (file 2 for the Game
   Gear).
2. The game is already paused, because the menu is open. The core runs its save state engine (the MiSTer one),
   which writes the whole machine into SDRAM, and it answers with the size of the state. If it answers 0, the core
   has no save states and you get "Not supported by the core".
3. The firmware reads the state from the core over SPI and writes it next to the rom as `<rom>.ss0` to `.ss3`,
   depending on the slot. It first writes a temporary file and renames it only at the end, so a cut in the middle
   does not break the old state.
4. `FILE_READ_END`, and the game goes on.

Load State is the same the other way: the firmware writes the `.ssN` file into the core's SDRAM between
`FILE_WRITE_START` and `FILE_WRITE_END`, and at `FILE_WRITE_END` the core restores the machine from it. The firmware
waits at most 3.5 seconds for the core at every step, and shows a message if something fails.

In the json files it looks like this (see `sdcard/savestate-firmware/`):

- `files.json`: a file with `"on_demand": true` and the core's address for it. The firmware does not load or save
  it at start and exit, only when you ask.
- `settings.json`: a list with `"file_slots": 2` (the State Slot, the slot is the number in `.ssN`, and the firmware
  puts a `*` next to the slots that already have a file), and two actions with `"file": 2`, one of them with
  `"load": true` (Save State and Load State).

## How Swap A/B works

The swap is in the FPGA, not in the firmware. The platform (`HandheldTop.scala`) has a new write-only register,
`0x1018` (`REG_CTRL_INPUT` in the firmware), bit 0 swaps A and B on the way to the core, for the console's buttons
and for a docked gamepad. The firmware reads the buttons before the swap, so the menus are never swapped.

The firmware adds the "Swap A/B" checkbox itself (`core/info.rs`, `add_swap_ab_setting`), right before the core's
Reset entry, only when the core says its bitstream has the register: `"hardware": {"swap_ab": true}` in
`core.json`. The setting id `0x7FF0` is reserved for it. The value is saved per core like the other settings, in
`/sdcard/settings/<core id>.json`. Eli's firmware ignores the unknown `swap_ab` field (serde skips unknown fields),
so the same `core.json` works there. For the stock firmware the entry is written in
`sdcard/cores/Retro-Bub.GG/settings.json` instead: a plain checkbox at `0xF1001018` (mask 0), which any firmware
writes to the FPGA like the other settings. When a `settings.json` already has an entry for that register, my
firmware does not add a second one. A bitstream built before this change has nothing at
`0x1018`, so don't put `swap_ab` in a core.json that goes with such a bitstream.

The built-in Game Boy and GBA cores don't show it yet (`swap_ab: false`, their bitstreams in the firmware don't have
the register). For them the value would go in the KVS (`gb-swap-ab`, `gba-swap-ab`). Their "Reset Core" entry is
now the last one in the menu, like in the other cores.

## How the colors work

While the firmware loads the rom, the core already copies every byte into the MiSTer code. `RomCrc32`
(`GgSmsColors.scala`) takes the same bytes and computes the CRC-32 (the No-Intro one, without the 512 byte copier
header if the file has one). At the end it compares it with the 44 games of the list. If it is there and Colors is
Auto, the core runs the MiSTer code with `gg` off (Master System palette and Pause) and `ggres` on (the Game Gear
screen). The setting is bits 9-8 of the core's register `0x0`. The list has the `SYSTEM_GGMS` games with the Sega
mapper from Genesis Plus GX's `sms_cart.c`, it has 9 more with other mappers (Codemasters, Korean) that are not
in mine.

Nearest is filter 3 of the scaler (`VideoScalerV0.FilterNearest`, value `0xC0` in `settings.json`): the scaler
takes the source pixel nearest to the sample position, a tie goes right or down.

If you want save states in your own firmware, these are the parts to take: `core/on_demand.rs`, the `on_demand`
flag and the `file` / `file_slots` keys in `core/info.rs`, and the few lines in `core/settings.rs` that send these
actions to `on_demand.rs` instead of writing them to the core.

## Building the bitstream

What you need: Java 17, Python 3 with `edalize==0.6.7` (see `fpga/framework/requirements.txt`) and Vivado (I use
2025.2). On an Apple Silicon Mac the `firtool` that Chisel downloads needs Rosetta 2. On Windows the scripts use
`fpga/mill.bat` by themselves.

1. Generate the Vivado project, from the root of the repo:

   ```bash
   python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.sms.HandheldGg --name gg --target gamebub_rev4
   ```

   You get `dist/vivado/gg-gamebub_rev4.zip` (and the same folder unpacked next to it). The script is in
   `scripts/rev2/` only because it started there, `--target` is the board (`gamebub_rev1` to `gamebub_rev4`).

2. Run Vivado in that folder: `make` on Linux, `build.bat` on Windows (set `VIVADO` to your `vivado.bat` if you have
   more than one). At the end you have `gg-gamebub_rev4.bit`.

Build from a git clone, the firmware build needs the commit hash.

I use `make_vivado_bundle.py` and not Eli's `fpga/scripts/build_core.py` because it tells Vivado the right language
for every file: `VM2413` has to be read as VHDL-93 (it uses `Release`, which is a keyword in VHDL-2008), and so does
`xilinx_ram.vhd` (a shared variable for the dual port RAM).

## Where things are

- `fpga/src/main/scala/net/gamebub/core/sms/`: my glue for the core. `HandheldGg` is `HandheldSmsBase` with
  `gameGear = true`. `GgSmsColors.scala` is the CRC-32 and the list of games for Colors.
- `fpga/vendor/sms_mister/`: the MiSTer code. Its `README.md` says what I changed and why.
- `fpga/src/main/vhdl/sms/xilinx_ram.vhd`: the RAMs the MiSTer code expects, for Xilinx.
- `fpga/src/main/scala/platform/handheld/LcdAspectScaler.scala`, `lib/video/PolyphaseScaler.scala`,
  `net/gamebub/framework/interface/VideoScalerV0.scala`: the scaler.
- `sdcard/`: the files for the SD card.
- `scripts/rev2/`: my scripts. `make_vivado_bundle.py` makes the Vivado project for a core and a board,
  `pack_vivado_bundles.py` packs several of them to build at the same time on Windows, `finish_bitstream.py` adds the
  git revision to a bitstream and makes the compressed `.bit.hs`, `sms_test_sim.sh` is a simulation of the core,
  `make_logo.py` makes the logo. The other scripts in `scripts/` are Eli's.

## License and credits

- My code here (the Game Gear glue, the scaler, the scripts, what I added in the firmware; the list is in
  `THIRD-PARTY.md`) is GPL-2.0-or-later (`LICENSE-GPL-V2`, `LICENSE-GPL-V3`). The firmware as a whole stays
  GPL-3.0-only, because it is Eli's GPL-3.0-only program.
- Eli's framework and firmware, also with my changes in his files, keep his licenses: CERN-OHL-S-2.0 for `fpga/`
  (`LICENSE-CERN-OHL-S`), GPL-3.0-only for the firmware and the scripts (`LICENSE-GPL-V3`).
- The MiSTer core: SMS_MiSTer has no license file and most of its files have no license header. Some parts have
  their own licenses, two of them with a non-commercial clause (the FM sound chip VM2413 and the 93C46 EEPROM from
  Genesis Plus GX). Everything is listed in `THIRD-PARTY.md`.

Thanks to Ben for the original Master System core, to the SMS_MiSTer people, to Eli for the Game Bub, to
minuitfranck and Jeremy O'Brien for the parts I took from their ports, to Toady M for testing on rev4, and to Eke-Eke
for Genesis Plus GX, where the list of Game Gear games in Master System mode comes from (non-commercial license, see
`THIRD-PARTY.md`).

"Game Bub" is Eli's project and trademark, this is a community port. "Sega" and "Game Gear" belong to their owners.
No bios and no games in here.

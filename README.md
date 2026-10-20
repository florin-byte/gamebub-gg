# Sega Game Gear core for the Game Bub

This is the Sega Game Gear core for the [Game Bub](https://github.com/elipsitz/gamebub), loaded from the SD card. It
is the MiSTer core ([SMS_MiSTer](https://github.com/MiSTer-devel/SMS_MiSTer)) glued to Eli's Game Bub framework
(1.1.0-beta2). It is not an official Game Bub core, I just ported it.

The repo is my fork of the Game Bub code, cut down to what the Game Gear needs: the FPGA framework, the core, the
firmware and the scripts to build it. The same sources also build the Master System / SG-1000 version of the core
(`HandheldSms`), but this repo is about the Game Gear.

If you only want to play, take the zip from the [releases](../../releases), you don't need to build anything.

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
- Scaling: Sharp Bilinear / Bilinear / Integer. The picture is 160x144, scaled to the full height of the screen.
- Reset.

## Save states

The save states are in the bitstream (the MiSTer save state engine), but they need fw changes as well, to move the
state between the core and the SD card while the game runs. Eli's firmware does not have that, so on the stock
firmware there are no save states. The `.sav` battery saves work anyway.

- Stock firmware: use `sdcard/cores/Retro-Bub.GG`. Everything works except save states. Don't use the other folder
  with it, the State file will give an error when you exit the game.
- My firmware (`firmware/handheld/`): use `sdcard/savestate-firmware/cores/Retro-Bub.GG`. Then you have State Slot,
  Save State and Load State (`.ss0` to `.ss3` next to the rom). I tested it on rev2. For rev4 it compiles
  (`cargo check --features rev4`) but nobody ran it on a rev4 yet. Build it like the stock one (ESP-IDF 5.4.3,
  `cargo build --release --features rev4`).

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
  `gameGear = true`.
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
minuitfranck and Jeremy O'Brien for the parts I took from their ports, and to Toady M for testing on rev4.

"Game Bub" is Eli's project and trademark, this is a community port. "Sega" and "Game Gear" belong to their owners.
No bios and no games in here.

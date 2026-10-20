# SMS_MiSTer (vendored)

Sega Master System / Game Gear / SG-1000 core from the MiSTer project, used by the `sms` core of this
repository (`fpga/src/main/scala/net/gamebub/core/sms/`).

- Source: https://github.com/MiSTer-devel/SMS_MiSTer
- Commit: `e7a4086` (12 Oct 2026)
- Files: the RTL used by `system.vhd` and `video.vhd`, and the save-state machine `savestates.sv` (unchanged;
  its DDRAM port is mapped to SDRAM by `SaveStateMemoryBridge`), copied from `rtl/` (local changes below). The MiSTer top level
  (`SMS.sv`), the MiSTer framework (`sys/`) and the Altera RAM wrappers (`sprom.vhd`, `spram.vhd`,
  `dpram.vhd`) are not included: this repository provides its own top level and Xilinx RAM entities with
  the same names and ports (`fpga/src/main/vhdl/sms/xilinx_ram.vhd`).
- The upstream repository has no top-level license file; the files carry their own headers. Check the
  license of each file before publishing this repository.

To update: copy the same files from a newer upstream checkout and record the new commit here.

## Local changes

- `rtl/jt89/jt89.vhd` and `system.vhd` (BUNDLE-JT89-05): package `jt89` renamed to
  `jt89_components`, with the corresponding import updated. Vivado 2025.2 otherwise leaves the
  Verilog module `jt89` unresolved: an isolated A/B synthesis reproduces one black box with the
  original package name and zero with the new name. Component/module names and logic are unchanged.
- `rtl/jt89/jt89.v` and `jt89.vhd` (BUNDLE-JT89-04): external `soundL`/`soundR` ports renamed to
  lowercase `soundl`/`soundr` for Vivado's VHDL-to-Verilog binding and consistent GHDL conversion.
  Internal mixer ports and audio logic are unchanged. Recheck this adaptation when updating
  upstream; Vivado confirmation is pending.
- `rtl/system.vhd`: `fm: work.opll` → `fm: entity work.opll` (the form without `entity` is accepted by
  Quartus but is not standard VHDL; GHDL rejects it).
- `rtl/T80/T80.vhd`, `T80s.vhd`, `T80pa.vhd`: the same change for `work.T80*` instances.
- `rtl/vdp_sprites.vhd`: the state constants get an explicit `(2 downto 0)` range, so the
  `'0' & COMPARE` choices are locally static as the standard requires.
- `rtl/mapper_ctrl.vhd`: `when others => null;` added to three `case A(1 downto 0)` statements
  (std_logic has nine values, so the standard requires full coverage).
- `rtl/T80/T80.vhd`: `ioq and x"7"` (9 bits and 4 bits) → `ioq and "000000111"`, as in the T80 of
  the Genesis core.
- `rtl/cheatcodes.sv`: `genie_ovr` / `genie_data` declared `output reg` (they are assigned in
  `always_comb`; Vivado rejects procedural assignments to a port without `reg`, Quartus accepts them),
  and the parameters moved into the module header (they were used in the port list before their
  declaration).
- `rtl/MC8123.v`: `begin`/`end` around the bodies of the seven `decrypt_type_*` functions (several
  statements in a function body are SystemVerilog; Vivado rejects them in a Verilog-2001 file).
- `rtl/parts.v`: defaults for the `DLROM` parameters and `ram[0:1023]` instead of `ram[1024]`
  (SystemVerilog forms in a Verilog-2001 file; Vivado reads `.v` files as Verilog-2001).
- `rtl/VM2413/controller.vhd`, `voicememory.vhd`, `voicerom.vhd`: `array (T'range)` on an integer
  subtype → `array (T)` (`'range` needs an array prefix).
- `rtl/VM2413/controller.vhd`, `envelopegenerator.vhd`: `when others => null;` added to five `case`
  statements on 2-bit vectors that list only "00".."11".

The VHDL-93 files pass `ghdl -a --std=93c -fsynopsys -fexplicit`, the VHDL-2008 ones
`--std=08 -fsynopsys` (without `-frelaxed`), apart from the shared variable of `xilinx_ram.vhd`, which
is read as VHDL-93 for that reason.

## VHDL standard

`rtl/VM2413/` is VHDL-93 (it uses `Release` as an identifier, a reserved word in VHDL-2008); everything
else is VHDL-2008 (expressions in port maps).

### Optional external BIOS allocation

The local `system` entity adds integer generic `EXTERNAL_BIOS` (default 1). GameBub's SMS/GG wrapper passes 0: these cores start cartridges directly, matching the previous firmware. The generate block removes external SMS/GG BIOS RAM without changing the vendored mapper or CPU behavior. Set 1 when integrating BIOS downloads; this requires the existing BIOS control ports and a firmware file-transfer path.

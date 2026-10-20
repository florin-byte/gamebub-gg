#!/usr/bin/env bash
# Builds a simulation of the vendored SMS_MiSTer core (system + video) and runs a ROM through it,
# saving the screen as PPM images.
#
# The VHDL is converted to Verilog with GHDL (simulation only; Vivado builds the VHDL itself) and run
# with Verilator. Differences from the hardware build: the VM2413 FM chip is replaced by a silent stub
# (it does not compile as VHDL-2008, and GHDL needs one standard per library), the RAMs start without
# initial values, ROM reads take one clock.
#
# Usage (from the repository root):
#   scripts/rev2/sms_test_sim.sh build
#   scripts/rev2/sms_test_sim.sh run <rom> <out dir> [GG|SG|PAL]
#   scripts/rev2/sms_test_sim.sh core               (SmsCore: the core as the firmware drives it)
#   scripts/rev2/sms_test_sim.sh core-run <rom> <out dir>
# Environment for core-run: FRAMES, EVERY, START, LATENCY (SDRAM wait cycles), CONFIG (register 0).
# Environment for run: FRAMES (default 300), EVERY (default 60), START (frames to press start, e.g. 200,300).
# Needs ghdl (6.x), Verilator and Python 3.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$REPO/build/sms-sim"
VENDOR="$REPO/fpga/vendor/sms_mister/rtl"
SIM="$REPO/fpga/sim/sms"

build() {
    mkdir -p "$OUT"
    rm -rf "$OUT/work" && mkdir -p "$OUT/work"
    # Simulation copy of the RAM entities without initial values (huge constants otherwise).
    perl -pe 's/((?:signal|shared variable)\s+mem\s*:\s*mem_t)\s*:=\s*\(others => \(others => .0.\)\)/$1/' \
        "$REPO/fpga/src/main/vhdl/sms/xilinx_ram.vhd" > "$OUT/xilinx_ram_sim.vhd"
    {
        echo "$OUT/xilinx_ram_sim.vhd"
        echo "$SIM/opll_stub.vhd"
        for f in T80/T80_Reg T80/T80_MCode T80/T80_ALU T80/T80 T80/T80s T80/T80pa jt89/jt89 video \
                 vdp_sprite_shifter vdp_sprites vdp_main vdp_cram vdp_background vdp mapper_detect \
                 mapper_ctrl memory_decode memory_bus evolution_rom_address evolution_mapper \
                 eeprom_93c46 cart_eeprom io AudioMix system; do
            echo "$VENDOR/$f.vhd"
        done
    } > "$OUT/files.txt"
    local GHDL=(--std=08 -frelaxed -fsynopsys --workdir="$OUT/work")
    ghdl -i "${GHDL[@]}" $(cat "$OUT/files.txt") > "$OUT/ghdl.log" 2>&1
    # `ghdl -m` links executables into the current directory.
    (cd "$OUT" && ghdl -m "${GHDL[@]}" system && ghdl -m "${GHDL[@]}" video) >> "$OUT/ghdl.log" 2>&1
    ghdl --synth "${GHDL[@]}" --out=verilog -gMAX_SPPL=63 -gEXTERNAL_BIOS=0 system > "$OUT/system_sim.v" 2>> "$OUT/ghdl.log"
    ghdl --synth "${GHDL[@]}" --out=verilog video > "$OUT/video_sim.v" 2>> "$OUT/ghdl.log"
    # Never-written ROMs come out as huge X constants, beyond Verilator's number width limit.
    perl -pi -e "s/= \d{5,}'bX;/= '0;/" "$OUT/system_sim.v"
    python3 "$SIM/make_tb.py" "$OUT/system_sim.v" "$OUT/tb_sms.sv"
    (cd "$OUT" && verilator --cc --exe --build -j 8 -O2 --top-module tb_sms +1364-2005ext+v \
        tb_sms.sv system_sim.v video_sim.v \
        "$VENDOR/jt89/jt89.v" "$VENDOR/jt89/jt89_mixer.v" "$VENDOR/jt89/jt89_noise.v" \
        "$VENDOR/jt89/jt89_tone.v" "$VENDOR/jt89/jt89_vol.v" "$VENDOR/MC8123.v" \
        "$VENDOR/SEGASYS1_PRGDEC.v" "$VENDOR/parts.v" "$VENDOR/cheatcodes.sv" "$SIM/tb_main.cpp" \
        -Wno-fatal -Wno-WIDTH -Wno-lint -Wno-style -Wno-PROCASSWIRE --x-assign fast --x-initial fast \
        -CFLAGS "-O2 -std=c++17" --Mdir obj -o tb_sms > verilator.log 2>&1) \
        || { echo "Verilator build failed, see $OUT/verilator.log"; exit 1; }
    echo "Simulator: $OUT/obj/tb_sms"
}

core() {
    [ -f "$OUT/system_sim.v" ] || build
    (cd "$REPO/fpga" && ./mill -i --no-build-lock root.runMain net.gamebub.core.sms.SimSms \
        --target-dir="$OUT/core") > "$OUT/core-mill.log" 2>&1 \
        || { echo "Elaboration failed, see $OUT/core-mill.log"; exit 1; }
    # GHDL converts `system` with its generics fixed (the values the core passes: MAX_SPPL = 63,
    # EXTERNAL_BIOS = 0), so the converted module has no parameters.
    perl -0pi -e 's/system #\([^()]*(\([^()]*\)[^()]*)*\)\s*sms/system sms/' "$OUT/core/SmsCore.sv"
    local generated
    # The elaboration also lists the core's VHDL (simulated here from the GHDL conversion).
    generated=$(grep -v '^\s*$' "$OUT/core/filelist.f" | grep -v '\.vhd$' | sed "s|^|$OUT/core/|")
    (cd "$OUT" && verilator --cc --exe --build -j 8 -O2 --top-module SmsCore +1364-2005ext+v \
        $generated system_sim.v video_sim.v "$SIM/core_main.cpp" \
        -Wno-fatal -Wno-WIDTH -Wno-lint -Wno-style -Wno-PROCASSWIRE --x-assign fast --x-initial fast \
        -CFLAGS "-O2 -std=c++17" --Mdir core-obj -o sms_core > core-verilator.log 2>&1) \
        || { echo "Verilator build failed, see $OUT/core-verilator.log"; exit 1; }
    echo "Simulator: $OUT/core-obj/sms_core"
}

run() {
    local rom="$1" dir="$2" mode="${3:-}"
    mkdir -p "$dir"
    python3 -c "import sys; d=open(sys.argv[1],'rb').read(); open(sys.argv[2],'w').write('\n'.join('%02x'%b for b in d))" \
        "$rom" "$dir/rom.hex"
    local size
    size=$(wc -c < "$rom" | tr -d ' ')
    (cd "$dir" && env ${mode:+$mode=1} OUT=shot "$OUT/obj/tb_sms" +rom=rom.hex +romsize="$size")
}

case "${1:-}" in
    build) build ;;
    run) shift; run "$@" ;;
    core) core ;;
    core-run) shift; mkdir -p "$2"; (cd "$2" && "$OUT/core-obj/sms_core" "$1" shot) ;;
    *) echo "usage: $0 build | run <rom> <out dir> [GG|SG|PAL]"; exit 2 ;;
esac

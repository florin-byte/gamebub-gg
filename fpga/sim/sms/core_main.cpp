// Simulation of SmsCore (the SMS core without clocks and SDRAM controller), driven like the firmware
// drives a core: the ROM is written through the host memory interface between FILE_WRITE_START and
// FILE_WRITE_END, then SETUP_COMPLETE, CORE_RUN and NOTIFY_FOCUS. The SDRAM is a model of the pipelined
// memory interface with a configurable latency; the video stream is written to a framebuffer the way
// the platform does (rows advance on hblank edges, restart on vblank). Screens are saved as PPM.
//
// Usage: sms_core <rom file> [out prefix]
// Environment: FRAMES (300), EVERY (60), START (frames to press start, e.g. 200,300),
//              LATENCY (SDRAM wait cycles, default 6), CONFIG (register 0 value, default 8 = FM on),
//              STATE_TEST=A,B: save a state (file 2) at frame A with the menu open, load it back at frame B,
//              and compare the screen 30 frames after each (shot_state_saved/loaded.ppm); STATE_NO_LOAD=1
//              skips the load (control: the screens must then differ).

#include "VSmsCore.h"
#include "verilated.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>
#include <string>
#include <vector>

static VSmsCore* top;
static std::vector<uint32_t> sdram(8 * 1024 * 1024, 0);  // 32 MiB, 32-bit words
static int latency = 6;
static bool pending = false, pendingWrite = false;
static uint32_t pendingAddress = 0;
static int waitCount = 0;
static uint64_t cycles = 0;

// Core outputs sampled before the clock edge of the last tick (with that cycle's inputs).
static struct {
    bool hostDone, commandDone, commandError;
    uint32_t hostDataRead;
    bool dataEnable, hblank, vblank;
    uint8_t r, g, b;
} sampled;

static void tick() {
    // Model outputs for this cycle.
    bool ready = !pending || waitCount == 0;
    top->io_sdram_ready = ready;
    top->io_sdram_dataRead = (pending && !pendingWrite) ? sdram[(pendingAddress >> 2) % sdram.size()] : 0;
    top->clock = 0;
    top->eval();
    // Sample the core's requests before the edge.
    bool enable = top->io_sdram_enable;
    uint32_t address = top->io_sdram_address;
    bool isWrite = top->io_sdram_isWrite;
    uint32_t dataWrite = top->io_sdram_dataWrite;
    sampled.hostDone = top->io_host_mem_done;
    sampled.hostDataRead = top->io_host_mem_dataRead;
    sampled.commandDone = top->io_host_commandHost_done;
    sampled.commandError = top->io_host_commandHost_error;
    sampled.dataEnable = top->io_video_dataEnable;
    sampled.hblank = top->io_video_hblank;
    sampled.vblank = top->io_video_vblank;
    sampled.r = top->io_video_data_r;
    sampled.g = top->io_video_data_g;
    sampled.b = top->io_video_data_b;
    top->clock = 1;
    top->eval();
    cycles++;
    static int debug = getenv("DEBUG_SDRAM") ? 20 : 0;
    if (debug > 0 && (enable || pending)) {
        debug--;
        fprintf(stderr, "[%llu] ready=%d enable=%d addr=%08x write=%d data=%08x pending=%d host en=%d done=%d a=%08x\n",
                (unsigned long long)cycles, ready, enable, address, isWrite, dataWrite, pending,
                top->io_host_mem_enable, top->io_host_mem_done, top->io_host_mem_address);
    }
    if (ready) {
        if (pending && pendingWrite) sdram[(pendingAddress >> 2) % sdram.size()] = dataWrite;
        pending = false;
        if (enable) {
            pending = true;
            pendingAddress = address;
            pendingWrite = isWrite;
            waitCount = latency;
        }
    } else {
        waitCount--;
    }
}

static void hostWrite(uint32_t address, uint32_t data) {
    top->io_host_mem_enable = 1;
    top->io_host_mem_write = 1;
    top->io_host_mem_address = address;
    top->io_host_mem_dataWrite = data;
    int guard = 0;
    do { tick(); } while (!sampled.hostDone && ++guard < 100000);
    top->io_host_mem_enable = 0;
    if (guard >= 100000) { fprintf(stderr, "host write 0x%08x timed out\n", address); exit(1); }
}

static uint32_t hostRead(uint32_t address) {
    top->io_host_mem_enable = 1;
    top->io_host_mem_write = 0;
    top->io_host_mem_address = address;
    int guard = 0;
    do { tick(); } while (!sampled.hostDone && ++guard < 100000);
    top->io_host_mem_enable = 0;
    return sampled.hostDataRead;
}

static uint32_t command(std::vector<uint32_t> words, long limit = 1000000) {
    for (size_t i = 0; i < words.size(); i++) hostWrite(0xF0000000u + 4 * i, words[i]);
    top->io_host_commandHost_request = 1;
    long guard = 0;
    do { tick(); } while (!sampled.commandDone && !sampled.commandError && ++guard < limit);
    bool error = sampled.commandError;
    top->io_host_commandHost_request = 0;
    tick();
    uint32_t result = hostRead(0xF0000000u);
    if (error || guard >= limit) { fprintf(stderr, "command 0x%04x failed\n", words[0]); exit(1); }
    if (limit > 1000000) fprintf(stderr, "command 0x%04x %u: %ld cycles\n", words[0], words.size() > 1 ? words[1] : 0, guard);
    return result;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    if (argc < 2) { fprintf(stderr, "usage: sms_core <rom> [out prefix]\n"); return 2; }
    std::string out = argc > 2 ? argv[2] : "shot";
    int frames = atoi(getenv("FRAMES") ? getenv("FRAMES") : "300");
    int every = atoi(getenv("EVERY") ? getenv("EVERY") : "60");
    latency = atoi(getenv("LATENCY") ? getenv("LATENCY") : "6");
    uint32_t config = strtoul(getenv("CONFIG") ? getenv("CONFIG") : "8", nullptr, 0);
    std::set<int> starts;
    if (getenv("START")) {
        std::string s = getenv("START");
        for (size_t p = 0; p < s.size();) { starts.insert(atoi(s.c_str() + p)); p = s.find(',', p); if (p == std::string::npos) break; p++; }
    }

    FILE* f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 1; }
    std::vector<uint8_t> rom;
    for (int c; (c = fgetc(f)) != EOF;) rom.push_back(c);
    fclose(f);
    while (rom.size() % 4) rom.push_back(0xFF);

    top = new VSmsCore;
    top->reset = 1;
    for (int i = 0; i < 10; i++) tick();
    top->reset = 0;
    tick();

    // Load the ROM (file 0) as CoreManager::load_files does.
    command({0x0300, 0});
    for (size_t i = 0; i < rom.size(); i += 4) {
        uint32_t word = rom[i] | (rom[i + 1] << 8) | (rom[i + 2] << 16) | (uint32_t(rom[i + 3]) << 24);
        hostWrite(0x30000000u + i, word);
    }
    command({0x0301, 0, (uint32_t)rom.size(), 0});
    // The SDRAM must now hold the ROM.
    for (size_t i = 0; i < rom.size(); i += 4) {
        uint32_t word = rom[i] | (rom[i + 1] << 8) | (rom[i + 2] << 16) | (uint32_t(rom[i + 3]) << 24);
        if (sdram[i >> 2] != word) { fprintf(stderr, "SDRAM mismatch at 0x%zx\n", i); return 1; }
    }
    // SAVE_TEST: load a pattern as the .sav (file 1), read it back at the end.
    bool saveTest = getenv("SAVE_TEST") != nullptr;
    auto pattern = [](uint32_t i) { return 0xA5000000u ^ (i * 0x01030507u); };
    if (saveTest) {
        command({0x0300, 1});
        for (uint32_t i = 0; i < 8192; i++) hostWrite(0x40000000u + 4 * i, pattern(i));
        command({0x0301, 1, 32768, 0});
    }
    hostWrite(0x00000000u, config);
    command({0x0102});           // setup complete
    command({0x0100});           // core run
    command({0x0200, 1});        // focus
    fprintf(stderr, "ROM loaded (%zu bytes) in %llu cycles\n", rom.size(), (unsigned long long)cycles);

    const int width = 256, height = 240;
    std::vector<uint32_t> fb(width * height, 0);
    int fx = 0, fy = 0, frame = 0, held = 0;
    bool prevVblank = false, prevHblank = false;
    int stateSaveAt = -1, stateLoadAt = -1, compareAt = -1;
    std::string compareName;
    std::vector<uint32_t> state, savedShot;
    if (getenv("STATE_TEST")) sscanf(getenv("STATE_TEST"), "%d,%d", &stateSaveAt, &stateLoadAt);
    auto dumpFrame = [&](const std::string& name) {
        FILE* o = fopen(name.c_str(), "wb");
        fprintf(o, "P6 %d %d 255\n", width, height);
        for (auto px : fb) { uint8_t rgb[3] = {uint8_t(px >> 16), uint8_t(px >> 8), uint8_t(px)}; fwrite(rgb, 1, 3, o); }
        fclose(o);
    };
    while (frame < frames) {
        tick();
        bool vblank = sampled.vblank, hblank = sampled.hblank;
        if (vblank) {
            fx = 0; fy = 0;
        } else if (hblank) {
            if (!prevHblank) { fx = 0; fy++; }
        } else if (sampled.dataEnable) {
            if (fx < width && fy < height) {
                uint32_t r = sampled.r * 17, g = sampled.g * 17, b = sampled.b * 17;
                fb[fy * width + fx] = (r << 16) | (g << 8) | b;
            }
            fx++;
        }
        if (vblank && !prevVblank) {
            frame++;
            if (frame == compareAt) {
                dumpFrame(out + "_" + compareName + ".ppm");
                if (compareName == "state_saved") {
                    savedShot = fb;
                } else {
                    int diff = 0;
                    for (size_t i = 0; i < fb.size(); i++) diff += fb[i] != savedShot[i];
                    fprintf(stderr, "state test: %d of %zu pixels differ between 30 frames after save and after load\n",
                            diff, fb.size());
                }
            }
            if (frame == stateSaveAt || frame == stateLoadAt) {
                // As the firmware does: menu open (no focus), then the on-demand transfer of file 2.
                command({0x0200, 0});
                const uint32_t base = 0x31000000u, words = 0x3000 * 2;
                if (frame == stateSaveAt) {
                    uint32_t size = command({0x0302, 2}, 200000000);
                    state.resize(words);
                    for (uint32_t i = 0; i < words; i++) state[i] = hostRead(base + 4 * i);
                    command({0x0303, 2});
                    fprintf(stderr, "state saved: size %u, header %08x %08x magic %08x game %08x\n",
                            size, state[0], state[1], state[2], state[3]);
                    compareName = "state_saved";
                } else if (getenv("STATE_NO_LOAD")) {
                    // Control run: the comparison must fail without the load.
                    compareName = "state_not_loaded";
                } else {
                    command({0x0300, 2});
                    for (uint32_t i = 0; i < words; i++) hostWrite(base + 4 * i, state[i]);
                    command({0x0301, 2, 0x3000 * 8, 0}, 200000000);
                    compareName = "state_loaded";
                }
                command({0x0200, 1});
                compareAt = frame + 30;
            }
            if (starts.count(frame)) held = 6;
            top->io_buttons_start = held > 0;
            if (held > 0) held--;
            if (frame % every == 0) {
                char name[512];
                snprintf(name, sizeof name, "%s_%d.ppm", out.c_str(), frame);
                FILE* o = fopen(name, "wb");
                fprintf(o, "P6 %d %d 255\n", width, height);
                for (auto px : fb) { uint8_t rgb[3] = {uint8_t(px >> 16), uint8_t(px >> 8), uint8_t(px)}; fwrite(rgb, 1, 3, o); }
                fclose(o);
                fprintf(stderr, "frame %d (%llu cycles)\n", frame, (unsigned long long)cycles);
            }
        }
        prevVblank = vblank;
        prevHblank = hblank;
    }
    fprintf(stderr, "ROM reads: %u, slower than 16 clocks: %u, max latency: %u clocks\n",
            hostRead(0x00001000u), hostRead(0x00001004u), hostRead(0x00001008u));
    uint32_t saveSize = command({0x0302, 1});
    fprintf(stderr, "save file size reported: %u\n", saveSize);
    if (saveTest) {
        int bad = 0;
        for (uint32_t i = 0; i < 8192; i++) {
            uint32_t v = hostRead(0x40000000u + 4 * i);
            if (i < 4) fprintf(stderr, "  word %u: read %08x expected %08x\n", i, v, pattern(i));
            bad += v != pattern(i);
        }
        fprintf(stderr, "save read back: %d of 8192 words differ\n", bad);
    }
    command({0x0303, 1});
    delete top;
    return 0;
}

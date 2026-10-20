// Runs the SMS testbench: saves the screen (256x240 window of the VDP x/y) every N frames.
#include "Vtb_sms.h"
#include "verilated.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>
#include <string>
#include <vector>

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    int frames = atoi(getenv("FRAMES") ? getenv("FRAMES") : "300");
    int every = atoi(getenv("EVERY") ? getenv("EVERY") : "60");
    std::string out = getenv("OUT") ? getenv("OUT") : "shot";
    std::set<int> starts;
    if (getenv("START")) { std::string s = getenv("START"); size_t p = 0; while (p < s.size()) { starts.insert(atoi(s.c_str() + p)); p = s.find(',', p); if (p == std::string::npos) break; p++; } }
    Vtb_sms* top = new Vtb_sms;
    top->cfg_gg = getenv("GG") != nullptr; top->cfg_pal = getenv("PAL") != nullptr; top->cfg_sg = getenv("SG") != nullptr;
    top->joy = 0;
    std::vector<uint32_t> fb(256 * 240, 0);
    int frame = 0, held = 0; bool prev_vbl = false; bool prev_ce = false; long cycles = 0;
    while (frame < frames) {
        top->clk = 0; top->eval(); top->clk = 1; top->eval(); cycles++;
        if (!top->ready) continue;
        bool ce = top->ce_pix;
        if (ce && !prev_ce && !top->hblank && !top->vblank && top->vx < 256 && top->vy < 240) {
            uint32_t c = top->color;
            uint32_t r = (c & 0xF) * 17, g = ((c >> 4) & 0xF) * 17, b = ((c >> 8) & 0xF) * 17;
            fb[top->vy * 256 + top->vx] = (r << 16) | (g << 8) | b;
        }
        prev_ce = ce;
        bool vbl = top->vblank;
        if (vbl && !prev_vbl) {
            frame++;
            if (starts.count(frame)) held = 6;
            top->joy = held > 0 ? 0x40 : 0; if (held > 0) held--;
            if (frame % every == 0) {
                char name[256]; snprintf(name, sizeof name, "%s_%d.ppm", out.c_str(), frame);
                FILE* f = fopen(name, "wb"); fprintf(f, "P6 256 240 255\n");
                for (auto px : fb) { uint8_t rgb[3] = {(uint8_t)(px >> 16), (uint8_t)(px >> 8), (uint8_t)px}; fwrite(rgb, 1, 3, f); }
                fclose(f); fprintf(stderr, "frame %d (%ld cycles)\n", frame, cycles);
            }
        }
        prev_vbl = vbl;
    }
    delete top;
    return 0;
}

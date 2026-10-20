# SD card files

| folder | for |
|---|---|
| `cores/Retro-Bub.GG/` | the stock Game Bub firmware (1.1 or later): everything but save states |
| `savestate-firmware/cores/Retro-Bub.GG/` | a firmware with on-demand core files (`firmware/handheld/` of this repository): adds State Slot, Save State and Load State |

Copy one `Retro-Bub.GG` folder to the card's `cores/` folder and put the uncompressed bitstream next to the JSON
files (`gg-gamebub_rev4.bit` for rev4, `gg-gamebub_rev2.bit` for rev2; `core.json` lists both).

# SD card files

| folder | for |
|---|---|
| `cores/Retro-Bub.GG/` | stock beta3 firmware: four slots in a 384 KiB `.ggstates` container, written at normal exit |
| `savestate-firmware/cores/Retro-Bub.GG/` | a firmware with on-demand core files (`firmware/handheld/` of this repository): individual `.ss0`–`.ss3` files, written immediately |

Copy one `Retro-Bub.GG` folder to the card's `cores/` folder and put the uncompressed bitstream next to the JSON
files (`gg-gamebub_rev4.bit` for rev4, `gg-gamebub_rev2.bit` for rev2; `core.json` lists both).

These JSONs must ship with the matching framework-v2 unified bitstream. I tested the on-demand path on rev2
on 23 Oct 2026. The stock path is left for other users to test; the new rev4 build is hardware-untested. Stock save states created during a session are lost if the game does not exit
normally. Existing individual slot files are not automatically converted into the stock container.

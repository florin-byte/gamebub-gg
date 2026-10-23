# SD card files

| folder | for |
|---|---|
| `cores/Retro-Bub.GG/` | stock beta3 firmware: four slots in a 384 KiB `.ggstates` container, written at normal exit |
| `savestate-firmware/cores/Retro-Bub.GG/` | a firmware with on-demand core files (`firmware/handheld/` of this repository): individual `.ss0`–`.ss3` files, written immediately |

Copy one `Retro-Bub.GG` folder to the card's `cores/` folder and put the uncompressed bitstream next to the JSON
files (`gg-gamebub_rev4.bit` for rev4, `gg-gamebub_rev2.bit` for rev2; `core.json` lists both).

These JSONs prepare a new unified bitstream and must ship with that matching bitstream. The new stock path
is not hardware-tested yet. Stock save states created during a session are lost if the game does not exit
normally. Existing individual slot files are not automatically converted into the stock container.

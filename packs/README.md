# OpenOBD PID packs

Community sets of manufacturer PIDs / data identifiers, downloadable from the app
(*More > PID packs > Browse the community catalog*).

## Adding a pack

1. In the app, find the values with the **Identifier explorer** or add them in **Custom PIDs**,
   check they make sense while driving, then use **PID packs > Share as a pack**.
2. Put the `.openobd-pack.json` file in this folder with a short name (`opel-astra-j-1.6cdti.json`).
3. Add an entry to `index.json` (`id`, `name`, `makes`, `models`, `years`, `engines`, `pids`, `file`)
   and set `"verified": true` only for values you tested on a real car.
4. Open a pull request.

Packs may only contain read requests (services 01, 02, 09, 21, 22, 1A, AA). The app refuses
anything that clears, writes or runs routines.

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

PIDs in a pack may only be read requests (services 01, 02, 09, 21, 22, 1A, AA). The app refuses
PIDs that clear, write or run routines.

## Codings (optional)

A pack can also carry `codings`: settings stored in a module's data identifier. Nothing is written
when the pack is imported. A coding is only written when the user picks an option in
*Brand features > Codings & adaptations*, and every write:

1. reads the whole identifier and saves it as a backup (before anything is sent),
2. writes the changed copy (UDS `2E`, in the extended session `10 03`),
3. reads it back; if it does not match, writes the backup back and checks again,
4. stays in the history so it can be undone.

`"risk": "DANGEROUS"` (the default) shows an explanation and a second confirmation and needs the car
parked with the engine off; use `"SAFE"` only for comfort settings (lights, locks, displays).
OpenOBD never sends security access (`27`), so modules that need it answer "locked" and nothing changes.

```json
"codings": [{
  "id": "drl", "name": "Daytime running lights", "description": "…",
  "header": "726", "receive": "72E", "did": "0101", "byte": 0, "mask": 4,
  "options": [{"label": "Off", "value": "00"}, {"label": "On", "value": "04"}],
  "risk": "SAFE", "tested": true, "source": "forum thread URL"
}]
```

`byte` counts from the first data byte after the identifier; `mask` is the bits the setting uses
(decimal in JSON) and every option `value` is hex, already in position. Only publish codings you tested
on a real car (`"tested": true`), and say where they came from.

`routines` (`"start": "3101…"`, optional `"stop": "3102…"`, `"engineRunning": true` for a
regeneration) are always treated as dangerous.

## Community data from OBDb

The app can also import read-only values from [OBDb](https://obdb.community) (CC BY-SA 4.0,
OBDb contributors), converted on the phone. `app/src/main/assets/obdb/index.json` lists the makes and
models; regenerate it from `https://obdb.community/data/matrix_data.json` when new models appear.

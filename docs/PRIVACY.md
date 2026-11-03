# OpenOBD privacy policy

_Last updated: November 3, 2026_

OpenOBD is an open-source car diagnostics app. It has no accounts, no ads, no analytics and no crash
reporting, and its developers receive no data from it.

## Data stored on your device

Everything the app records stays in its private storage on your phone:

- **Vehicle data** read from your car: VIN, trouble codes, sensor values, module information.
- **Your entries**: garage vehicles, maintenance items, fuel log, custom PIDs, settings.
- **Recordings**: CSV data logs and saved trips. If you turn on GPS tracks, trips include the route you drove.

Uninstalling the app deletes all of it. You can also delete vehicles, trips, logs and scan history in the app.

## Data that leaves your device

Only in these cases, and only when you trigger them:

- **Sharing**: when you tap Share on a report, data log or PID list, the content goes to the app you choose.
- **Maps**: when you open a trip with a GPS track, map images are downloaded from OpenStreetMap's tile servers.
  Like any website, those servers see your IP address and which map area is requested. Their privacy policy:
  https://osmfoundation.org/wiki/Privacy_Policy
- **Vehicle lookups** (Model & specifications screen): only when you search, the app sends what you typed or
  picked to the matching public service:
  - the make, model and year you browse to the European Environment Agency (EEA catalog, EU countries)
  - your licence plate to the Dutch RDW (Netherlands) or the UK DVLA (with your own API key)
  - your VIN to the US NHTSA, and the year, make and model you browse to the US EPA (fueleconomy.gov)

  These are government open-data services; no account or personal data is sent.
- **Web search**: the "Search web" button opens your browser with a search for the trouble code.
- **Wi-Fi adapters**: the app connects to the OBD adapter on your local network. Nothing is sent to the internet.

## Permissions

| Permission | Why |
|---|---|
| Bluetooth (connect / scan) | Talk to Bluetooth and Bluetooth LE OBD adapters. |
| Location | Android 11 and older require it to scan for Bluetooth LE devices. If you turn on GPS tracks, it's also used to record trip routes while the app is connected to your car, including with the screen off. Location is never sent anywhere. |
| Notifications | Show the connection status while the app keeps the link to your car open. |
| Foreground service | Keep the connection and data logging running in the background. |
| Internet / network state | Wi-Fi OBD adapters and map tiles. |

## Children

The app isn't directed at children.

## Changes and contact

Changes to this policy are published in this file in the project repository. Questions:
https://github.com/Garcy99/OpenOBD/issues

# OpenOBD privacy policy

_Last updated: November 8, 2026_

OpenOBD is an open-source car diagnostics app. It has no accounts, no ads, no analytics and no crash
reporting, and its developers receive no data from it.

## Data stored on your device

Everything the app records stays in its private storage on your phone:

- **Vehicle data** read from your car: VIN, trouble codes, sensor values, module information.
- **Your entries**: garage vehicles, maintenance items, fuel log, custom PIDs, codings, settings.
- **Coding backups**: the value a module held before each coding you write, so it can be undone.
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
- **Community data and packs**: when you open the community pack catalog or pick a make / model from OBDb, the app
  downloads that file from GitHub (raw.githubusercontent.com). GitHub sees your IP address and which file is
  requested; nothing about you or your car is sent. Its privacy statement:
  https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement
- **Terrain for 3D models**: when you create a 3D model of a trip with terrain, the app downloads elevation tiles of
  that area from Terrain Tiles (AWS Open Data, s3.amazonaws.com). Amazon sees your IP address and which map tiles are
  requested (so roughly where the trip was); nothing else is sent. Tiles are kept in the app's cache. AWS privacy
  notice: https://aws.amazon.com/privacy/
- **Google account sync** (optional, off until you connect your Google account in Settings): your cars, maintenance,
  fuel log, trips with their GPS routes, health samples, battery tests, coding history, scan history and some
  preferences are stored in a private app folder of **your own Google Drive** (appDataFolder) and read back on your
  other devices. Sign-in goes through Google Play services; the app only gets permission for its own folder, never
  for your other Drive files, and never sees your password. No OpenOBD server receives anything. API keys, adapter
  settings and recorded logs are not uploaded. Disconnect in Settings to stop and withdraw the app's access; you can
  delete the folder's data from Google Drive → Settings → Manage apps → OpenOBD → Delete hidden app data. Google's
  privacy policy: https://policies.google.com/privacy
- **Map data for 3D maps**: when you open the 3D map of a trip, the app asks the Overpass API (overpass-api.de, or
  overpass.kumi.systems if that one fails) for the lakes, main roads and buildings inside a rectangle around the trip.
  The server sees your IP address and that rectangle (so roughly where the trip was); the route itself, your car and
  your account are not sent. Answers are kept in the app's cache. Overpass API: https://overpass-api.de/
- **AI assistant** (optional, off until you enter your own Anthropic API key): when you ask a question or send a
  photo of a warning light, the question, the trouble codes and values involved, the car's make / model / year and
  the photo go to Anthropic's API under your key. The VIN and your location are never sent. Anthropic's privacy
  policy: https://www.anthropic.com/legal/privacy
- **Web search**: the "Search web" button opens your browser with a search for the trouble code.
- **Wi-Fi adapters**: the app connects to the OBD adapter on your local network. Nothing is sent to the internet.

Everything else, including what the app reads from or writes to your car, stays between the app and your adapter.

## Permissions

| Permission | Why |
|---|---|
| Bluetooth (connect / scan) | Talk to Bluetooth and Bluetooth LE OBD adapters. |
| Location | Android 11 and older require it to scan for Bluetooth LE devices. If you turn on GPS tracks, it's also used to record trip routes while the app is connected to your car, including with the screen off. Location is never sent anywhere. |
| Notifications | Show the connection status while the app keeps the link to your car open. |
| Foreground service | Keep the connection and data logging running in the background. |
| Internet / network state | Wi-Fi OBD adapters, map tiles, vehicle lookups, community data and the optional AI assistant. |
| Display over other apps | Only if you turn on floating gauges, to show them over navigation apps. |
| Camera (through the system camera app) | Only when you take a photo of a warning light for the AI assistant. |
| USB | Talk to USB OBD cables, mainly on car head units. |

## Children

The app isn't directed at children.

## Changes and contact

Changes to this policy are published in this file in the project repository. Questions:
https://github.com/Garcy99/OpenOBD/issues

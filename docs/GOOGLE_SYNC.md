# Google account sync

OpenOBD can keep a user's data the same on every phone (and, later, in a desktop program) by syncing with a
private folder in **their own Google Drive** (Drive's *appDataFolder*). There is no OpenOBD server: each user's data
stays in their account, only OpenOBD can see that folder, and the user can remove it at any time from
Google Drive → Settings → Manage apps.

## What syncs

| In Drive | Contents |
|---|---|
| `garage.json` | Cars (with specs, custom PIDs, codings), maintenance, fuel log, trips, health samples, battery tests, coding history |
| `history.json` | Trouble-code scans |
| `preferences.json` | Units, theme, language, dashboard, alerts, fuel price and similar preferences |
| `track-<trip id>.json` | The GPS route of each trip, with what the car was doing at every point |

Not synced: API keys, the adapter and connection settings, and recorded CSV logs (they stay on each phone).

The formats are the app's own JSON (see `GarageData`, `ScanRecord`, `SyncedPrefs` and `TrackPoint` in the source);
fields may be added in later versions, so readers should ignore unknown fields.

## How it merges

Each phone keeps the copy it last synced (outside Android backups). On every sync it compares, record by record
(by id), this phone's data, the copy in Drive and that last synced copy: whatever changed on one side since the
last sync is taken, so edits and deletions made on different devices all survive. If the same record changed on
both, the phone that is syncing wins; a record deleted on one side but edited on the other is kept. Routes are
uploaded or downloaded file by file. If another device uploads during a sync, the merge runs again.

It syncs when the app opens and when the user leaves it (at most every couple of minutes), and on "Sync now".

## Setting it up (once, by whoever publishes the app)

Google identifies the app by its package name and the SHA-1 fingerprint of the key that signs it, so sign-in only
works for builds whose key is registered. **Use a permanent release key** (see the release section of the README):
with a key that changes per release, sign-in breaks after every update.

1. Open <https://console.cloud.google.com/>, create a project (for example "OpenOBD").
2. **APIs & Services → Library**: enable the **Google Drive API**.
3. **APIs & Services → OAuth consent screen** (Google Auth Platform → Branding / Audience / Data access):
   - User type **External**; app name "OpenOBD", support e-mail, developer contact e-mail.
   - Data access → add the scope `https://www.googleapis.com/auth/drive.appdata` ("See, create, and delete its own
     configuration data in your Google Drive"). It is a non-sensitive scope.
   - While the app is in **Testing**, only the Google accounts added as test users (up to 100) can sign in. Publish
     it to **In production** when ready; with only non-sensitive scopes no security review is needed.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `com.openobd`
   - SHA-1 certificate fingerprint of the release key:
     `keytool -list -v -keystore openobd-release.jks -alias openobd` (the line `SHA1:`).
   - Create one more Android client for each other key you install from (for example a debug key), with the same
     package name and that key's SHA-1.
5. Nothing has to go into the app or the repository: Google Play services match the installed app by package name
   and signature. A desktop program will need its own **Desktop app** OAuth client in the same project, so that it
   can open the same Drive folder.

If sign-in shows "Google sign-in is not set up for this build of the app yet", the SHA-1 of the key that signed the
installed APK is not registered (`keytool -printcert -jarfile OpenOBD.apk` shows it).

# Releasing OpenOBD

## 1. Choose the final package name (once, before the first Play upload)

`applicationId` in `app/build.gradle.kts` is currently `com.openobd`. Google Play package names are global
and **can never change after the first upload**, and `com.openobd` may already be taken. A name you control is
safer, for example `io.github.garcy99.openobd`. Only `applicationId` needs to change (keep `namespace`).
Changing it makes a new app on phones that already have the old build installed.

## 2. Create your signing key (once, and keep it safe)

```bash
keytool -genkeypair -v -keystore openobd-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias openobd
```

Keep `openobd-release.jks` and its passwords backed up somewhere safe and **never commit them**.
Without the key you can't publish updates. Play App Signing lets Google reset an upload key, but only for Play installs.

## 3. Build a signed release locally

Create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=openobd-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=openobd
keyPassword=YOUR_KEY_PASSWORD
```

```bash
./gradlew assembleRelease bundleRelease
```

- APK (direct install): `app/build/outputs/apk/release/app-release.apk`
- App Bundle (Google Play): `app/build/outputs/bundle/release/app-release.aab`

Without `keystore.properties`, release builds are signed with the debug key. They install fine for
testing, but you can't publish them.

## 4. Automatic releases from GitHub

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `OPENOBD_KEYSTORE_BASE64` | output of `base64 -w0 openobd-release.jks` |
| `OPENOBD_KEYSTORE_PASSWORD` | store password |
| `OPENOBD_KEY_ALIAS` | `openobd` |
| `OPENOBD_KEY_PASSWORD` | key password |

For each release:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` (500 characters max).
3. Tag and push:

```bash
git tag v1.3.0
```

```bash
git push origin v1.3.0
```

Or, without tagging locally: **Actions → Release → Run workflow**. It creates the tag from `versionName`
(for example `v1.8.0`) and publishes the release with the APK and App Bundle.

Without the signing secrets the workflow still runs, but publishes a **pre-release** signed with a
temporary key. It installs fine for testing, but each such build has a different key, so a phone has to
uninstall the previous copy before installing it, and it cannot go on Google Play.

The `Release` workflow runs the tests, builds the signed APK and AAB, and attaches them to a GitHub release.

## 5. Google Play

1. Create a developer account (one-time fee) and a new app.
2. Use **Play App Signing**, with your key as the upload key.
3. Upload the `.aab` to **Internal testing** first. New personal developer accounts must run a closed test
   (currently at least 12 testers for 14 days) before production access. Check the current Play Console requirements.
4. **Store listing**: text, icon, feature graphic and screenshots are in `fastlane/metadata/android/` for English
   and Spanish. Upload them by hand or with `fastlane supply`.
5. **Privacy policy URL**: publish `docs/PRIVACY.md` (for example with GitHub Pages) and paste the link.
6. **Data safety** form: the app doesn't collect or share data with the developer. Location (optional GPS tracks)
   and car data stay on the device; nothing is transmitted.
7. **Foreground service declaration**: the app declares `connectedDevice` (OBD link) and `location` (trip GPS tracks)
   service types. Play asks for a short explanation and a video. If you'd rather avoid the location declaration,
   remove the `location` type and the GPS tracks option from the Play build.
8. Complete the content rating questionnaire and target audience (adults).

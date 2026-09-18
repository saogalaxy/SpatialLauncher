# Release signing (Meta Store)

Quest **debug** APKs (`assembleDebug`) are for sideload/dev. Store uploads need a **release-signed** APK.

## One-time setup

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\create_release_keystore.ps1
```

Creates (both **gitignored**):

- `signing/spatial-launcher-release.jks`
- `keystore.properties`

**Back these up.** Losing the keystore means you cannot ship updates under the same signing identity.

Or copy `keystore.properties.example` → `keystore.properties` and point `storeFile` at an existing `.jks`.

## Build

```powershell
.\gradlew.bat :app:assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

Confirm signing (should NOT say `CN=Android Debug`):

```powershell
keytool -printcert -jarfile app\build\outputs\apk\release\app-release.apk
```

Upload that APK via Meta Quest Developer Hub → App Distribution → release channel.

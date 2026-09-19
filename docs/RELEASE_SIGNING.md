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

## Store packaging checklist (2D panel)

Release manifest must satisfy [VRC Packaging.1](https://developers.meta.com/horizon/resources/publish-quest-req/):

- `targetSdk 34`, `compileSdk 34`, `minSdk` 29–34
- `android:installLocation="auto"`
- Launch activity: `excludeFromRecents="true"`, `MAIN` + `LAUNCHER` + `com.oculus.intent.category.2D`
- `com.oculus.supportedDevices` = `quest2|questpro|quest3|quest3s`
- APK Signature Scheme **v2+**, arm64-only, APK &lt; 1 GB

Store assets live in `docs/store-assets/` (icon, covers, hero, screenshots, trailers). Upload those in the Developer Dashboard Product Details page.

Privacy policy URL (must stay public HTTPS):

`https://github.com/saogalaxy/SpatialLauncher/blob/main/docs/PRIVACY.md`

## Dashboard permission justifications (Security.2)

Paste when the submission form asks. Only **RECORD_AUDIO** is on Meta’s review-requiring list in the current build.

**RECORD_AUDIO**  
Used only for the optional Listen feature. Captures the *cast app’s playback mix* via MediaProjection audio (MEDIA/GAME usages); the headset microphone is never opened. If the user denies the permission, Listen stays off and cast / OCR / Browser / Books continue normally.

**FOREGROUND_SERVICE_MEDIA_PROJECTION** (expect questions even if not on the review list)  
Required by Android / Horizon OS so `MediaProjection.createVirtualDisplay()` can run while the user casts another app into the Spatial Launcher panel for stereo / OCR / Listen. The service posts a system foreground notification while capture is active and stops when casting ends.

**Not declared (intentionally)**  
`POST_NOTIFICATIONS`, `WAKE_LOCK`, `FOREGROUND_SERVICE_DATA_SYNC` — unused after Qwen removal; status uses the in-app banner.

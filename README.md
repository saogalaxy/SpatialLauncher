# Spatial Launcher

Public Meta Quest app: cast 2D apps into a spatial panel with optional **3D depth**, **OCR→TTS**, **Listen** (speech→translate→speak), **browser translate**, and **EPUB / My Books** (PC import from Novel Translator).

**Repo:** https://github.com/saogalaxy/SpatialLauncher

## Preview

![Dock / home](docs/screenshots/01-dock-home.jpg)

![Cast + source open](docs/screenshots/02-cast-source-open.jpg)

![OCR zones](docs/screenshots/05-ocr-zones.jpg)

![Listen](docs/screenshots/07-listen.jpg)

![Browser](docs/screenshots/08-browser.jpg)

Full set: [docs/screenshots/](docs/screenshots/). Shoot order: [docs/SCREENSHOTS.md](docs/SCREENSHOTS.md)

## Quick install (Quest)

1. Enable **Developer Mode** on the Quest  
2. Plug in USB (or Wi‑Fi ADB) and accept debugging  
3. **`git clone`** this repo (not only the ZIP) so Gradle wrapper + assets stay intact  
4. Double-click **`Install to Quest.bat`**

That runs `tools/easy_install.ps1`: checks JDK + Node/metavr + headset, builds the debug APK if needed, installs with replace + permissions, and launches the app.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\easy_install.ps1
```

See [tools/README.md](tools/README.md).

## Docs

| Doc | What |
|-----|------|
| [docs/HELP.md](docs/HELP.md) | Same guide as the in-headset **?** Help (modes, pipelines, downloads, combos) |
| [docs/PRIVACY.md](docs/PRIVACY.md) | Privacy policy (public URL for Meta Store) |
| [docs/META_STORE.md](docs/META_STORE.md) | Horizon Store listing copy, flow, screenshot map |
| [docs/SCREENSHOTS.md](docs/SCREENSHOTS.md) | Screenshot shoot order |
| [docs/screenshots/](docs/screenshots/) | Store / GitHub images |

## What it does

- **Dock cast** — mirror an app window; leave the source app open  
- **3D** — stereo depth (glasses on / “3D” off)  
- **Reader pipeline** — Settings modes: **Read**, **Translate**, **Share**, **Listen** with **Active:** pipeline label  
- **Translate engine** — **OPUS** (machine translate) or **ML Kit OCR** (as-read, no OPUS)  
- **OCR zones + TTS** — read on-screen dialogue (continuous blue loop)  
- **Listen** — cast audio → SenseVoice → OPUS (optional) → Piper  
- **Browser** — Widevine WebView; on-device Translate or Google; Read  
- **My Books** — EPUB shelf; PC import over Wi‑Fi; tap book again to close  

### Reader pipelines (quick reference)

| Mode | Path |
|------|------|
| Read | OCR → Piper |
| Translate | OCR → OPUS → Piper |
| Share | OCR → OPUS → Piper + caption *(or OCR → Piper + caption)* |
| Listen | Audio → STT → OPUS → Piper *(or STT → Piper)* |

## Project layout

```
Install to Quest.bat          # one-click build + sideload
tools/
  easy_install.ps1            # JDK / metavr / Gradle / install / launch
  README.md
  export_launcher_icon.py
docs/
  HELP.md
  PRIVACY.md                  # store privacy policy text
  META_STORE.md               # Horizon listing copy + screenshot map
  SCREENSHOTS.md
  screenshots/                # store & GitHub images
app/
  build.gradle
  src/main/
    AndroidManifest.xml
    java/com/spatiallauncher/app/ui/   # panel UI, cast, TTS, Listen, browser, books
    res/                              # layouts, drawables, help strings
    cpp/                              # OpenXR NativeActivity / layers (legacy hooks)
    assets/                           # bundled models (large; some gitignored)
```

### Important app packages (Java UI)

| Area | Classes (under `app/.../ui/`) |
|------|-------------------------------|
| Main panel | `PanelMainActivity`, `UserSettingsStore`, `PanelAlerts`, `AssistMode` |
| 3D / cast | `GlesZMeshView`, `DepthEstimator`, mirror / MediaProjection path |
| OCR + TTS | `DialogueTextExtractor`, `ScreenDialogueReader`, `PiperTtsEngine` |
| Listen | `PlaybackListenEngine`, `ListenMtTranslator` |
| Page MT | `PageTranslator`, `OnDeviceTranslator`, `TranslateMtService`, `OfflineModelPack` |
| Books | `EpubLibraryStore`, `BookImportService`, `BookImportHttp` |
| Browser | `WidevineWebViewConfig`, `GoogleWebTranslate`, `QwenPageEngine` |

## Build requirements

- JDK 17+  
- Android SDK (see `app/build.gradle` / AGP)  
- Node 20+ (`npx metavr`)  
- Quest with Developer Mode  

Manual:

```powershell
.\gradlew.bat :app:assembleDebug
npx -y metavr app install .\app\build\outputs\apk\debug\app-debug.apk --replace --grant-permissions
npx -y metavr app launch com.spatiallauncher.app
```

## Notes

- Store / installer builds pack Piper, SenseVoice, OPUS JA/ZH/KO, Qwen, and ML Kit OCR AARs into the APK — no Play Store downloads.  
- Large model archives under `assets/models/` may be gitignored locally; `downloadOfflineModels` fetches them at build time.  
- Sandbox experiments stay under `com.spatiallauncher.app.sandbox`.

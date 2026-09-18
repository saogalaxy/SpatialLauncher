# Spatial Launcher

Public Meta Quest app: cast 2D apps into a spatial panel with optional **3D depth**, **OCR→TTS**, **Listen** (speech→translate→speak), **browser translate**, and **EPUB / My Books** (PC import from Novel Translator).

**Repo:** https://github.com/saogalaxy/SpatialLauncher

## Preview

**Demo video:** [YouTube](https://youtu.be/Dv5ACRTrMvQ?si=sxODKhJNHexj7dcK)

![Dock / home](docs/screenshots/01-dock-home.jpg)

![Cast + source open](docs/screenshots/02-cast-source-open.jpg)

![OCR zones](docs/screenshots/05-ocr-zones.jpg)

![Listen](docs/screenshots/07-listen.jpg)

![Browser](docs/screenshots/08-browser.jpg)

Full set: [docs/screenshots/](docs/screenshots/).

## Quick install (Quest)

1. Enable **Developer Mode** on the Quest  
2. Plug in USB (or Wi‑Fi ADB) and accept debugging  
3. **`git clone`** this repo (not only the ZIP) so Gradle wrapper + assets stay intact  
4. Double-click **`Install to Quest.bat`**

That runs `tools/easy_install.ps1`: checks JDK + Node/metavr + headset, builds the debug APK if needed, installs with replace + permissions, and launches the app.

**Storage:** the headset install is smaller than before (Piper + OPUS in the APK). **Qwen** (Page Translate) and **SenseVoice** (Listen) download on first use (~1 GB each) when Wi‑Fi is available. Build outputs stay in this repo folder on the PC.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\easy_install.ps1
```

See [tools/README.md](tools/README.md).

## Quick install (Windows Desktop)

1. Install [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0)  
2. Double-click **`Install Spatial Launcher Desktop.bat`**

Owl-style PC session (window/monitor → Live 3D SBS on PC) streams to Quest via **Desktop Link** (auto-find on LAN; Quest is a thin SBS viewer). Details: [desktop/README.md](desktop/README.md) · [docs/DESKTOP.md](docs/DESKTOP.md)

**Storage:** no drive picker — installs to `%LocalAppData%\SpatialLauncherDesktop\` on the **user-profile drive (usually C:)**. Expect ~**900 MB** (~450 MB app + ~470 MB depth models). Uninstall from **Settings → Apps**.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\desktop_easy_install.ps1
```

## Docs

| Doc | What |
|-----|------|
| [CHANGELOG.md](CHANGELOG.md) | What changed (keep updated with each notable fix/feature) |
| [docs/HELP.md](docs/HELP.md) | Same guide as the in-headset **?** Help (modes, pipelines, downloads, combos) |
| [docs/DESKTOP.md](docs/DESKTOP.md) | Spatial Launcher Desktop — PC session + Quest Link |
| [docs/TECH_STACK.md](docs/TECH_STACK.md) | Platforms, libraries, ports, models, audio/video path |
| [docs/PRIVACY.md](docs/PRIVACY.md) | Privacy policy (public URL for Meta Store) |
| [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) | Release keystore + `assembleRelease` for Meta Store |
| [docs/screenshots/](docs/screenshots/) | Store / GitHub images |
| [desktop/README.md](desktop/README.md) | Desktop app build / install |

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
  DESKTOP.md                  # PC session + Quest Link
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

- Store / installer builds pack Piper and OPUS JA/ZH/KO into the APK. Qwen + SenseVoice download on first use (optional `-PpackHeavyModels=true` for experiments; may fail packageDebug past ~2GB).  
- Large model archives under `assets/models/` may be gitignored locally; `downloadOfflineModels` fetches them at build time.  

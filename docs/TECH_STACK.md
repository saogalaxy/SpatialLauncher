# Spatial Launcher — Tech Stack

Detailed inventory of platforms, languages, libraries, protocols, models, and tooling.
Product version: **0.1.0**. Repo: https://github.com/saogalaxy/SpatialLauncher

---

## 1. Product shape

| Surface | Role |
|---------|------|
| **Quest app** (`com.spatiallauncher.app`) | Horizon OS **2D floating panel**: cast + 3D depth, OCR→TTS, Listen, browser, My Books, and **Desktop Link** (thin SBS viewer) |
| **Windows Desktop** | PC owns capture → depth → SBS → LAN **video** stream; optional reader/Listen on PC |
| **Install tooling** | `Install to Quest.bat`, `Install Spatial Launcher Desktop.bat`, scripts under `tools/` |

Spatial Launcher is **not** an immersive OpenXR title. Stereo on Quest uses Horizon OS surface APIs (reflection) and/or Desktop Link SBS.

---

## 2. Repository map

| Path | Purpose |
|------|---------|
| `app/` | Android / Quest Gradle module |
| `desktop/src/SpatialLauncher.Desktop/` | WPF shell (MainWindow, tray, SBS preview) |
| `desktop/src/SpatialLauncher.Desktop.Core/` | Capture, depth, stereo, Quest Link, discovery, audio, OCR/TTS/Listen, settings |
| `desktop/installer/` | Inno Setup script (optional) |
| `tools/` | Easy install / uninstall scripts |
| `docs/` | HELP, DESKTOP, PRIVACY, screenshots, this doc |
| `gradle/` | Gradle wrapper |
| `app/.../sandbox/` | Sandbox experiments (excluded from release APK) |

---

## 3. Quest / Android stack

### Platform

| Item | Value |
|------|--------|
| Language | **Java 17** |
| Package / applicationId | `com.spatiallauncher.app` |
| minSdk / targetSdk / compileSdk | **29** / **32** / **34** |
| ABI | `arm64-v8a` only |
| UI | AndroidX AppCompat + Material; Horizon **2D** category (`com.oculus.intent.category.2D`) |
| Form factor | Floating panel (`PanelMainActivity`), not OpenXR immersive |

### Build

| Item | Value |
|------|--------|
| Android Gradle Plugin | 8.1.4 |
| Gradle | 8.7 (wrapper) |
| Install path | `tools/easy_install.ps1` → JDK 17+, `npx metavr`, debug APK, install + launch |

### Libraries (`app/build.gradle`)

| Library | Version | Used for |
|---------|---------|----------|
| AndroidX Core / AppCompat / Annotation | 1.12 / 1.6.1 / 1.7.1 | UI foundation |
| Material | 1.11.0 | Components |
| TensorFlow Lite (+ support) | 2.14.0 / 0.4.4 | On-headset depth (MiDaS / DA-V2 TFLite when present) |
| ML Kit text-recognition (+ JA / ZH / KO) | 16.0.1 | OCR (no ML Kit Translate — avoids Play downloads) |
| sherpa-onnx (JitPack) | v1.13.5 | Piper TTS + SenseVoice ASR |
| onnxruntime-android | 1.17.3 | OPUS-MT in `:opusmt` process |
| llama-android-opencl | 5.1.0 | Qwen GGUF page translate (`:qwen` process) |
| commons-compress | 1.26.2 | Model / archive handling |
| slf4j-nop | 2.0.17 | Logging stub |

### Feature pipelines (Quest)

| Mode | Stack |
|------|--------|
| Cast + 3D | MediaProjection → GLES Z-mesh / depth |
| Read | ML Kit OCR → Piper |
| Translate | OCR → OPUS-MT (ORT) → Piper |
| Share | OCR → OPUS (optional) → Piper + caption |
| Listen | Cast audio → SenseVoice → OPUS (optional) → Piper |
| Browser | Widevine WebView; on-device Qwen or Google Translate |
| My Books | EPUB shelf; LAN import HTTP |
| Desktop Link | MediaCodec JPEG / H.264 / AV1 + Opus UDP `:8767` |

### Meta / Horizon

- Intent category **2D** panel app  
- Stereo helpers via reflection (`horizonos.view.SurfaceViewExt` / `SurfaceControlExt`)  
- MediaProjection for cast  
- **No** Meta OpenXR / Native SDK dependency in Gradle  

---

## 4. Windows Desktop stack

### Platform

| Item | Value |
|------|--------|
| Runtime | **.NET 8** (`net8.0-windows10.0.19041.0`) |
| UI | **WPF** (+ WinForms helpers for screens / tray) |
| Solution | `desktop/SpatialLauncher.Desktop.sln` |
| Publish | Self-contained **win-x64** → `%LocalAppData%\SpatialLauncherDesktop\app` |
| Settings | `%LocalAppData%\SpatialLauncherDesktop\user_settings.json` |

### NuGet (`SpatialLauncher.Desktop.Core`)

| Package | Version | Used for |
|---------|---------|----------|
| Microsoft.ML.OnnxRuntime.DirectML | 1.19.2 | Depth Anything ONNX (GPU via DirectML) |
| NAudio | 2.2.1 | Listen WASAPI + Desktop Link Opus mirror + Piper WAV |
| Concentus | 2.2.2 | Opus audio encode for Desktop Link `:8767` |
| Vortice.MediaFoundation | 3.6.2 | H.264 / AV1 encode (Media Foundation) |
| System.Drawing.Common | 8.0.8 | GDI capture / bitmaps |
| System.Speech | 8.0.0 | SAPI TTS fallback |

### Session pipeline

```
Window/monitor pick → GDI capture → Depth Anything ONNX (DirectML)
  → SBS stereo warp → Quest Link TCP :8765
                      → LAN discovery UDP :8766
```

| Concern | Implementation |
|---------|----------------|
| Depth Gaming | Depth Anything V2 ViT-S ONNX |
| Depth Movies | Depth Anything 3 Base → Small → DA-V2 Base fallback |
| Stream codecs | MJPEG (multipart), H.264 (Annex-B length-prefixed), AV1 (OBU length-prefixed) |
| Reader OCR | PaddleOCR CLI if present, else Windows OCR |
| Reader TTS | Piper preferred, SAPI fallback |
| Listen | WASAPI → SenseVoice (when models present) → OPUS-MT → Piper |
| Tray | Session continues while minimized |
| Desktop Link audio | WASAPI → Concentus Opus → UDP `:8767` → Quest jitter + AudioTrack |

---

## 5. Networking & media protocols

| Port | Transport | Role |
|------|-----------|------|
| **8765** | TCP (custom HTTP-ish) | Video `/sbs.mjpg`, `/sbs.h264`, `/sbs.av1`; `/status`, `/settings`; Book import (`/health`, `/import`) |
| **8766** | UDP | Desktop discovery beacon + `SLD?` ping; Book import discovery |
| **8767** | UDP | Desktop Link headset audio (Opus packets) |

### Video (PC → Quest)

- **Not** WebRTC / RTSP / MPEG-TS  
- Custom length-prefixed or MJPEG multipart over TCP  
- Quest decodes with **MediaCodec** (AV1 prefers low-latency QTI decoder when available)  
- Codec can hot-swap JPEG ↔ H.264 ↔ AV1 via settings / URL  
- Present / capture target **72 Hz**; H.264/AV1 MFTs signal the same frame rate  
- Quest never `lockCanvas` on a MediaCodec SurfaceView (JPEG canvas vs compressed are separate producers)  

### Audio (PC → Quest)

**Opus headset mirror** (Concentus on PC + Quest): WASAPI loopback of the Windows default render device → 20 ms Opus frames @ ~128 kbps → unicast UDP `:8767` to the connected viewer. Quest keeps a small jitter buffer (~20–80 ms) and uses Opus PLC on gaps. Video reconnect **flushes** the jitter queue without closing the socket. PC speakers remain audible by design.

> Product chip **OPUS** elsewhere means **OPUS-MT** (Marian translation ONNX), not this Opus audio codec.

---

## 6. Native / driver components

| Component | Stack |
|-----------|--------|
| Android NDK / C++ | None in tree; OpenCL declared for llama via native library uses |
| OpenXR / cpp legacy | Not present in current tree |
| Virtual audio driver | **Removed** — Headset uses WASAPI loopback + Opus instead |

---

## 7. Models & on-disk assets

### Quest (bundled / Gradle `downloadOfflineModels`; large binaries often gitignored)

| Model | Purpose |
|-------|---------|
| Piper en_US amy / ryan (sherpa-onnx) | TTS (APK) |
| SenseVoice multilingual (sherpa-onnx) | ASR Listen — **download on first use** (optional `-PpackHeavyModels`) |
| Xenova opus-mt ja/zh/ko → en (ORT) | Machine translate (APK) |
| Qwen2.5-1.5B Instruct Q4_K_M GGUF | Page translate — **download on first use** (optional `-PpackHeavyModels`) |
| MiDaS / DA-V2 TFLite (when present) | On-headset depth |

### Desktop (`%LocalAppData%\SpatialLauncherDesktop\models`)

| Model | Purpose |
|-------|---------|
| `depth_anything_v2_vits.onnx` | Gaming depth (installer fetch) |
| `da3_base.onnx` / `da3_small.onnx` | Movies depth |
| `depth_anything_v2_vitb.onnx` | Movies legacy fallback |
| `piper/` (+ optional `piper.exe`) | TTS |
| `translate/{jaen,zhen,koen}/` | OPUS-MT packs |
| `asr/` | SenseVoice (optional) |
| `paddleocr/` | PP-OCR CLI (optional) |

Approximate Desktop install size: **~900 MB** (app ~450 MB + depth models ~470 MB). Quest debug APK: Piper + OPUS (~hundreds of MB); Qwen/SenseVoice ~1 GB each on first use.

---

## 8. Tooling & install

| Script / tool | Role |
|---------------|------|
| `tools/easy_install.ps1` | Build + install Quest APK via metavr |
| `tools/desktop_easy_install.ps1` | `dotnet publish`, LocalAppData install, Start Menu, URL ACL, depth fetch |
| `tools/desktop_uninstall.ps1` | Remove Desktop app entry |
| `npx metavr` | Quest device install / launch |
| Inno Setup (`SpatialLauncherDesktop.iss`) | Optional classic installer after publish |

---

## 9. Privacy / third-party (summary)

- No ad or analytics SDKs (see `docs/PRIVACY.md`)  
- Frames/audio stay on PC + local Wi‑Fi for Desktop Link  
- Major upstreams: TensorFlow Lite, ML Kit, sherpa-onnx / k2-fsa, ONNX Runtime, llama.cpp (OpenCL), Hugging Face models, NAudio, Vortice  

---

## 10. One-page diagram

```text
┌──────────────────────────────── Quest (Java 17) ────────────────────────────────┐
│  Panel: Cast/3D · OCR/Piper · OPUS-MT · SenseVoice · Browser/Qwen · My Books    │
│  Desktop Link: MediaCodec (JPEG|H264|AV1) + Opus UDP :8767                       │
└────────────▲──────────────────────────────▲──────────────────────────▲───────────┘
             │ TCP :8765 video/settings     │ UDP :8766 discovery      │ UDP :8767 audio
┌────────────┴──────────────────────────────┴──────────────────────────┴───────────┐
│  Desktop (.NET 8 WPF)                                                            │
│  GDI capture → DirectML Depth Anything → SBS → MF encode / MJPEG                 │
│  WASAPI → Concentus Opus → :8767 · Optional: PaddleOCR · Piper · SenseVoice      │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 11. Related docs

| Doc | What |
|-----|------|
| [README.md](../README.md) | Product overview + quick install |
| [docs/DESKTOP.md](DESKTOP.md) | Desktop session / Quest Link usage |
| [docs/HELP.md](HELP.md) | In-headset help mirror |
| [docs/PRIVACY.md](PRIVACY.md) | Privacy policy |
| [desktop/README.md](../desktop/README.md) | Desktop build notes |
| [CHANGELOG.md](../CHANGELOG.md) | Release notes |

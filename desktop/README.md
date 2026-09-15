# Spatial Launcher Desktop

PC-first companion: **better depth + OCR/TTS/Listen on Windows**, SBS stream to Quest Desktop Link (thin viewer).

## Quick install

1. Install [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0)
2. Double-click **`Install Spatial Launcher Desktop.bat`** at the repo root

That runs [`tools/desktop_easy_install.ps1`](../tools/desktop_easy_install.ps1): publish win-x64 → `%LocalAppData%\SpatialLauncherDesktop\app` → Start Menu shortcut → Apps & Features uninstall entry → fetch DA-V2 + DA3 ONNX when possible → URL ACL for port **8765**.

### Storage (important)

- **No drive selection.** Install is always under the Windows **user profile** (almost always the **C:** drive):  
  `%LocalAppData%\SpatialLauncherDesktop\`
- **Typical size:** ~**450 MB** app + ~**470 MB** (Gaming) to ~**850 MB** (with DA3 Movies) depth models.
- Optional reader/TTS packs (Piper, OPUS, etc.) add more under `models\` if you add them later.
- Uninstall: **Settings → Apps → Spatial Launcher Desktop** (models are kept by default).

Or:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\desktop_easy_install.ps1
```

## Use with Quest

1. **PC:** Start Session (Stream to Quest + Advertise on LAN). Tray minimize OK.  
2. **Quest:** monitor toolbar → **Find PC** / auto-connect with **3D on**  
3. Same Wi‑Fi. Manual URL is fallback only.

## Depth

| Preset | Model | Stereo |
|--------|--------|--------|
| Gaming | `depth_anything_v2_vits.onnx` | Fast backward warp |
| Movies | `da3_base.onnx` (else `da3_small` / DA-V2 Base) | Forward-fill + hole inpaint |

## Reader

PaddleOCR (CLI under `models/paddleocr` when present) → OPUS-MT → Piper `en_US-lessac-high` (SAPI/Windows OCR fallbacks). Listen: WASAPI + SenseVoice pack under `models/asr`.

## Layout

```
desktop/
  src/SpatialLauncher.Desktop/       # WPF UI + tray
  src/SpatialLauncher.Desktop.Core/  # capture, depth, SBS, Quest Link, discovery, OCR, Listen
  installer/                         # publish output
```

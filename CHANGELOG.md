# Changelog

All notable changes to **Spatial Launcher** (Quest app + Windows Desktop) are recorded here.
Format: newest first. Dates are local (US).

## Unreleased

### Desktop Link / streaming
- **AV1 encode fix (PC):** hardware AV1 MFTs are async — unlock + event pump (`NeedInput`/`HaveOutput`) and honor `OutputStreamProvidesSamples`. Previously Ensure succeeded but Encode always returned empty.
- AV1 `av1C` exposed on `GET /status` for Quest MediaCodec `csd-0`.
- Codec hot-swap (JPEG ↔ MPEG H.264) without restarting the PC session or force-stopping the headset viewer.
- Quest waits for PC `/status` codec confirm before reconnecting; handles HTTP **409** codec-mismatch redirects and **503** busy.
- PC applies Quest `POST /settings` on the accept thread (no UI-dispatcher race).
- Stream viewer cap (max 2) + longer send timeout to stop zombie reconnect storms.
- Surface handoff when switching JPEG (`lockCanvas`) ↔ MPEG/AV1 (`MediaCodec`) to avoid black frames after codec change.
- Quest AV1 decode: prefer `c2.qti.av1.decoder.low_latency`, load `av1c` into `csd-0`.
- **AV1 `av1c` once-only on PC:** stop appending sequence-header OBUs every keyframe (was bloating `csd-0` to 15KB+).
- Quest rejects oversized `av1c` (over 2KB), skips cold-start surface recycle when already valid, waits for surface before claiming a viewer slot, and resumes stream on `surfaceCreated`/`onResume`.
- **Codec upswitch (JPEG→AV1/H.264):** stop resetting `surfaceProducer` on reconnect (that skipped the canvas→codec handoff). Replace the `SurfaceView` on producer changes so BLAST is clean — hide/show was dropping the surface on the way back up to AV1.
- **Edge smear clean** (PC + Quest Desktop Link): bias depth discontinuities toward background and shrink edge parallax to cut halos around people. Defaults lower **Motion ghosting** (was “Depth smooth”).
- **Spatial Launcher Audio driver:** ships signed Virtual Audio Driver package under `desktop/audio-driver/dist/x64`, installs via elevated `tools/install_spatial_audio_driver.ps1` (also from Desktop Sound UI), renames Windows Sound endpoint to **Spatial Launcher Audio**. Headset prefers that sink over Steam Streaming Speakers.
- Per-section **Save** on Stream / Sound / 3D look / Quest Link / Reader — persists to `%LocalAppData%\SpatialLauncherDesktop\user_settings.json`.
- Clearer 3D labels: **3D pop**, **Focus plane**, **Depth refresh**, **Motion ghosting**, **Edge smear clean**.

### Movies / depth
- Movies preset prefers Depth Anything 3 ONNX (`da3_base` → `da3_small` → DA-V2 Base fallback).
- Installer fetches DA3 models from Hugging Face when missing.

### Audio
- **Headset mirrors PC speakers:** WASAPI loopback of the Windows default render device → UDP PCM `:8767` (s16le stereo 48 kHz). PC speakers stay on; no virtual sink required for dual play.
- Unicast audio to the connected Quest IP (from video TCP); drop duplicate sequence numbers on Quest.
- Float loopback → explicit s16le conversion (fixes static from MediaFoundationResampler / dual unicast+broadcast).
- Optional **Spatial Launcher Audio** driver package + install scripts remain; Secure Boot PCs often hit **Code 52** (SignPath ≠ Microsoft attestation) — installer reports this clearly.
- Own virtual speaker path: install **Spatial Launcher Audio**, then Headset (when the driver loads).

### Docs
- Added [docs/TECH_STACK.md](docs/TECH_STACK.md) (platforms, libraries, ports, models, audio/video path).

### Defaults
- Stream width **1920**, JPEG quality **85**, sharpen **25**, **3D pop 21%**.

### Known issues (2026-09-15)
- If a PC has no AV1 hardware encoder, status shows encode error — use MPEG/JPEG.
- Desktop Link launched while the panel is stopped/not focused will wait for a surface; put on the headset and look at the panel if the stream does not start.
- Spatial Launcher Audio virtual driver may show Device Manager Code 52 under Secure Boot until an attestation-signed package ships.

## 2026-09-14 — Test notes (codec flow)

| Case | Result |
|------|--------|
| JPEG ↔ H.264 hot-swap | Pass — `/status` + stream path update; real payloads |
| Wrong URL after switch | Pass — PC `409`; Quest log `stream redirect HTTP 409 → …/sbs.mjpg` |
| Headset stays up across JPEG/MPEG | Pass |
| AV1 encode (async MFT + ProvidesSamples) | Pass — live payloads + stable `av1c` (~46 B) |
| AV1 on Quest headset | Pass — `c2.qti.av1.decoder.low_latency`, stable `viewers=1`, AU stream + picture |

## Earlier (Desktop Link thin client)

- Quest **Desktop Link** thin LAN viewer (`DesktopLinkActivity`) with discovery UDP, SBS stream, settings drawer (Live 3D, Full SBS, codec, audio, depth knobs).
- PC **Quest Link** TCP `:8765` (`/sbs.mjpg`, `/sbs.h264`, `/sbs.av1`), discovery `:8766`, audio `:8767`.
- Quick resume: keep HTTP listener up after Stop Session / Quest drop (`EnsureLinkListening`).
- Chrome fade after live; Min hides drawer; Exit finishes activity.

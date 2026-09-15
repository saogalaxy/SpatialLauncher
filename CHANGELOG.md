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

### Movies / depth
- Movies preset prefers Depth Anything 3 ONNX (`da3_base` → `da3_small` → DA-V2 Base fallback).
- Installer fetches DA3 models from Hugging Face when missing.

### Audio
- Separate LAN audio UDP path (PC speakers vs Headset mute+pipe).

### Defaults
- Stream width **1920**, JPEG quality **85**, sharpen **25**.

### Known issues (2026-09-14)
- If a PC has no AV1 hardware encoder, status shows encode error — use MPEG/JPEG.
- Desktop Link launched while the panel is stopped/not focused will wait for a surface; put on the headset and look at the panel if the stream does not start.

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

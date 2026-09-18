# Changelog

All notable changes to **Spatial Launcher** (Quest app + Windows Desktop) are recorded here.
Format: newest first. Dates are local (US).

## Unreleased

### Desktop Link — invariants (do not regress)

When changing Quest `DesktopLinkActivity` or PC `QuestLinkServer` / `MirrorSession`, keep these behaviors. Each was a real break (2026-09-15).

| Invariant | Why it broke before | Required behavior |
|-----------|---------------------|-------------------|
| **Codec / Gaming↔Movies hot-swap stays live** | Stall **watchdog** treated quiet gaps during `/settings` + surface handoff as a dead stream (`workerAlive=true` + `silentMs>10s` right when 15s grace ended) and called `forceReconnect`, stacking pumps | Watchdog reconnects **only if the pump thread is dead**. On codec/preset change: long **reconnect grace** (~45s). **Never** recreate SurfaceView when producer is already `MEDIA_CODEC`/`CANVAS` — wait or let the pump retry |
| **One pump generation at a time** | `forceReconnect` set `wantStream=true` *before* old workers finished → orphans kept looping while a new worker started (`AV1 surface not ready`, interrupted `av1c`) | Split **`streamDesired`** (user intent) from **`wantStream`** (pump gate). Bump **`streamGen`** on stop; pumps exit when `streamGen` mismatches. Only set `wantStream=true` inside `startStream` |
| **Stale LAN beacon must not undo codec** | After switching to AV1/H.264, `discoverPc()` still returned an old `…/sbs.mjpg` beacon and overwrote the URL | Only adopt a discovered URL if its path **matches `selectedCodec`** |
| **No stacked MEDIA_CODEC surface recreates** | Multiple pumps saw a briefly-invalid surface and all ran `MEDIA_CODEC→MEDIA_CODEC` recreate → black / incomplete surface | If producer is already the target: **wait up to 5s**, never tear down; only recreate when crossing JPEG (`lockCanvas`) ↔ MediaCodec |
| **PC frees viewer slots on drop** | Write loops caught `IOException` and continued → zombie viewers; headset got **503 busy** and could not reconnect | On stream write failure: **break** the loop so `NoteViewerLeft` runs. Send timeout must be Wi‑Fi-tolerant (~8s), not 500ms |
| **JPEG ↔ compressed surface producer** | Resetting `surfaceProducer` to `NONE` on reconnect skipped canvas→codec handoff; hide/show alone dropped BLAST | Keep producer across reconnect; **replace `SurfaceView`** only when crossing JPEG (`lockCanvas`) ↔ MediaCodec |
| **No lockCanvas on MediaCodec surfaces** | After JPEG, `surfaceCreated`/`surfaceChanged` redrawn the last bitmap onto a fresh MediaCodec surface → BLAST stayed CPU (`cur=2`) so configure failed `already connected (cur=2 req=3)` | Arm **`allowLockCanvas=false`** + clear `latestFrame` before MPEG/AV1; never `lockCanvas` while producer is `MEDIA_CODEC` |
| **Reuse decoder / detach BLAST** | Releasing + reconfiguring MediaCodec every HTTP reconnect left sticky BLAST consumers | Reuse live decoder across reconnects; `setOutputSurface(null)` before release; recover with SurfaceView replace only on configure failure |
| **Audio survives video reconnect** | Stopping Opus UDP on every codec/mode reconnect raced the surface and left audio behind after backlog | Keep receiver across video reconnect; **`flush()` jitter** on reconnect; full `stop()` only on Disconnect |
| **H.264 encode must not force every-AU sync** | `AllSamplesIndependent` + every-frame key hint produced ~300 B mushy AUs while AV1 stayed healthy | Prefer hardware H.264 MFT; periodic ~1 Hz keys; higher MPEG bitrate; signal encode FPS = present (**72**) |
| **PC codec epoch closes old path cleanly** | Clients hung on the wrong `/sbs.*` after a codec change | Bump codec epoch; return **409** with the correct `stream` path; Quest follows redirect / reconnects once |

**Quick regression check (headset + PC session running):** JPEG → MPEG → AV1 → JPEG; Gaming → Movies → Gaming; kill Wi‑Fi briefly and confirm auto-reconnect without tapping Connect. Confirm Headset audio stays in sync across codec switches.

### Desktop Link / streaming
- **Headset audio (Opus):** WASAPI loopback → Concentus Opus @ 48 kHz / 128 kbps → UDP `:8767` unicast to the connected Quest. Quest jitter buffer (~20–80 ms) + PLC. Default Sound mode is **Headset** (PC speakers still play). Not OPUS-MT translate. Video reconnect **flushes** jitter (does not tear down the socket).
- **Reconnect / codec surface:** grace + `streamGen` + codec-matched discovery; no lockCanvas on MediaCodec surfaces; reuse decoder with BLAST detach; no stacked MediaCodec recreates (see invariants above).
- **H.264 quality:** drop `AllSamplesIndependent`; prefer hardware MFT; ~1 Hz keyframes; bitrate curve ~4–18 Mbps (+ MPEG bump); encode signals **72 Hz** to match present.
- **Toolbar declutter:** TTS prev/play/pause/stop/next sit in a dock pill that **pops up on hover** over the speaker (manual TTS). Browser (globe) and My Books hide only while **Share** is on.
- **AV1 encode fix (PC):** hardware AV1 MFTs are async — unlock + event pump (`NeedInput`/`HaveOutput`) and honor `OutputStreamProvidesSamples`. Previously Ensure succeeded but Encode always returned empty.
- AV1 `av1C` exposed on `GET /status` for Quest MediaCodec `csd-0`.
- Codec hot-swap (JPEG ↔ MPEG ↔ AV1) without restarting the PC session.
- Quest waits for PC `/status` codec confirm before reconnecting; handles HTTP **409** codec-mismatch redirects and **503** busy.
- PC applies Quest `POST /settings` on the accept thread (no UI-dispatcher race).
- Surface handoff when switching JPEG (`lockCanvas`) ↔ MPEG/AV1 (`MediaCodec`) to avoid black frames after codec change.
- Quest AV1 decode: prefer `c2.qti.av1.decoder.low_latency`, load `av1c` into `csd-0`.
- **AV1 `av1c` once-only on PC:** stop appending sequence-header OBUs every keyframe (was bloating `csd-0` to 15KB+).
- Quest rejects oversized `av1c` (over 2KB), skips cold-start surface recycle when already valid, waits for surface before claiming a viewer slot, and resumes stream on `surfaceCreated`/`onResume`.
- **Edge smear clean** (PC + Quest Desktop Link): bias depth discontinuities toward background and shrink edge parallax to cut halos around people. Defaults lower **Motion ghosting** (was “Depth smooth”).
- Per-section **Save** on Stream / 3D look / Quest Link / Reader — persists to `%LocalAppData%\SpatialLauncherDesktop\user_settings.json`.
- Clearer 3D labels: **3D pop**, **Focus plane**, **Depth refresh**, **Motion ghosting**, **Edge smear clean**.

### Movies / depth
- **3D pop pixelation fix:** bilinear depth + color sampling in SBS warp (DA depth is ~518²; nearest lookups looked blocky on Movies). High-quality bilinear resize into the ONNX input and Half-SBS flat pack.
- Movies preset prefers Depth Anything 3 ONNX (`da3_base` → `da3_small` → DA-V2 Base fallback).
- Installer fetches DA3 models from Hugging Face when missing.

### Audio
- **Removed Desktop Link PC→Quest raw-PCM audio** (old Sound UI / UDP PCM). Replaced later by Opus Headset path (see Unreleased).
- PC **Listen** (WASAPI → SenseVoice on the desktop) is unchanged.

### Quest build
- **APK size fix:** stop packing Qwen (~986 MB) + SenseVoice (~1 GB) into the debug APK (AGP `packageDebug` **integer overflow** past ~2GB uncompressed). Piper + OPUS stay in the APK; Qwen/SenseVoice download on first headset use. Optional `-PpackHeavyModels=true`.
- **Release signing:** `tools/create_release_keystore.ps1` + `keystore.properties` (gitignored) wire `assembleRelease` for Meta Store uploads — see [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

### Docs
- Added [docs/TECH_STACK.md](docs/TECH_STACK.md) (platforms, libraries, ports, models, audio/video path).
- Desktop Link help split: Quest [HELP.md](docs/HELP.md) / in-app covers headset panel controls; [DESKTOP.md](docs/DESKTOP.md) covers PC Stream / Sound / 3D look / Quest Link settings.

### Defaults
- Stream width **1920**, JPEG quality **85**, sharpen **25**, **3D pop 21%**.

### Known issues (2026-09-16)
- If a PC has no AV1 hardware encoder, status shows encode error — use MPEG/JPEG.
- Desktop Link launched while the panel is stopped/not focused will wait for a surface; put on the headset and look at the panel if the stream does not start.
- JPEG often still looks sharper than MPEG at the same quality slider; prefer **AV1** on Quest 3 when Wi‑Fi allows.
- Sharpen applies to **JPEG only** (skipped for MPEG/AV1 to save CPU).
- Full SBS still subtracts 8 from the quality slider for all codecs (load relief).

## 2026-09-14 — Test notes (codec flow)

| Case | Result |
|------|--------|
| JPEG ↔ H.264 hot-swap | Pass — `/status` + stream path update; real payloads |
| Wrong URL after switch | Pass — PC `409`; Quest log `stream redirect HTTP 409 → …/sbs.mjpg` |
| Headset stays up across JPEG/MPEG | Pass |
| AV1 encode (async MFT + ProvidesSamples) | Pass — live payloads + stable `av1c` (~46 B) |
| AV1 on Quest headset | Pass — `c2.qti.av1.decoder.low_latency`, stable `viewers=1`, AU stream + picture |
| JPEG ↔ MPEG ↔ AV1 without disconnect (post–watchdog fix) | Required — grace + `streamGen`; must not stack pumps |
| Gaming ↔ Movies while streaming | Required — settings POST only; no watchdog reconnect mid-load |

## Earlier (Desktop Link thin client)

- Quest **Desktop Link** thin LAN viewer (`DesktopLinkActivity`) with discovery UDP, SBS stream, settings drawer (Live 3D, Full SBS, codec, audio, depth knobs).
- PC **Quest Link** TCP `:8765` (`/sbs.mjpg`, `/sbs.h264`, `/sbs.av1`), discovery `:8766`, audio `:8767`.
- Quick resume: keep HTTP listener up after Stop Session / Quest drop (`EnsureLinkListening`).
- Chrome fade after live; Min hides drawer; Exit finishes activity.

# Changelog

All notable changes to **Spatial Launcher** (Quest app + Windows Desktop) are recorded here.

- **USB PCâ†’Quest streaming removed.** It never worked end to end: over adb the
  forward, the listeners and the status path were all verified, but no video frame
  ever arrived on the headset. The NCM alternative was dropped too, because the USB
  network it needs is offered by Meta's PC software (Quest Link / MQDH) rather than
  by a third-party desktop app, so a plain PC and cable cannot provide it. Removed:
  the PC Enable USB link control, `UsbLinkManager`, the second listen port, the
  Quest USB button and its status strings, and the USB documentation. PC â†’ Quest
  video is Wi-Fi (LAN auto-detect) or Meta Quest Link casting.
- **A saved loopback URL can no longer strand LAN mode.** Desktop Link restored a
  saved `127.0.0.1` URL on every launch, so the headset dialled its own loopback,
  which can never reach a PC, and simply could not connect. A loopback URL is now
  dropped on launch and LAN discovery takes over.

Format: newest first. Dates are local (US).

## Unreleased â†’ 1.0.2 (versionCode 4, 2026-09-19)

Release 1.0.2 packages the Cast crash + Browser 3D Share fixes below (release-signed with `CN=Spatial Launcher`).

- **Lint cleanup + lock:** `lintDebug` is fully clean (was 28 errors: media3 opt-ins contained per-method, notification shade guarded + documented, control-layout orientations). Remaining 195 warnings locked in `app/lint-baseline.xml` â€” any new lint finding fails the build.
- **SenseVoice strictly on-demand:** no more download prompt or background fetch at startup â€” first tap on the Listen (ear) icon drives consent + Wiâ€‘Fi download via the existing Listen gate.
- **Browser desktop UA:** WebView now reports desktop Chrome (was mobile) so Netflix & co. serve the desktop page instead of pushing their native app.
- **Browser clear-data:** history dialog gains â€œClear cookies & site dataâ€ (cookies, DOM storage, caches, history + reload) for stale sessions.
- **Browser per-site clear:** â€œClear this siteâ€ forgets just the open host (visible cookies + origin storage) â€” other sites stay signed in. Best-effort: parent-domain cookies and IndexedDB may survive; the full clear covers those.
- **Beta lane:** `:app:assembleBeta` builds `com.spatiallauncher.app.beta` (`1.0.2-beta`), co-installs alongside debug/release with separate data. All experimental work ships here; never to Store channels.
- **Desktop settings profiles:** Session panel gains a Profiles selector â€” save the full snapshot under a name, pick one to apply live (mode, engines, UI follow; live file tracks it), delete when stale. Stored beside the live settings file.
- **Desktop stream cursor:** GDI blits never include the mouse pointer, so it is composited per frame (hotspot-correct, scaled to capture size, hidden cursors respected) across monitor, window, and PrintWindow fallback paths.
- **Compressed codecs always full SBS:** MPEG/AV1 render full-res eyes regardless of the Full SBS toggle (bandwidth allows it; the quality-slider âˆ’8 applies as on the toggle path). The toggle now covers JPEG only; the OCR zone preview mapping follows the effective layout.
- **AV1 key cadence (~1 Hz):** the server forced a keyframe on every AV1 AU, starving inter frames into mushy pixelation on detail-heavy content (Movies shows it first) â€” the same anti-pattern fixed for H.264. Keys now go out ~1 Hz like MPEG.
- **Movies depth no longer warps into a bad lenticular:** three compounding causes in the DA3 path. Depth was normalized by dividing by the raw max, so a letterboxed/vignetted frame collapsed most of its pixels onto one depth value and the plateau borders warped into hard seams â€” now a robust 2-98 percentile window. Near/far polarity was re-decided every frame with no margin, so cinematic frames with bars or vignettes could invert the whole depth map mid-stream and tear the picture â€” the decision is now sticky and only flips on decisive evidence. Movies preset also had the *lowest* smoothing of any preset (20%), which amplified both â€” now 55% with stronger edge clean.
- **Root cause â€” Movies never ran a depth model at all:** `da3_base.onnx` / `da3_small.onnx` declare their image input as NHWC **uint8** raw RGB, while `depth_anything_v2_*` declare NCHW normalized float. The estimator hard-coded the DA-V2 layout, so every Movies frame threw `InvalidArgument` and the existing catch silently substituted the luminance fallback â€” depth was literally pixel brightness, which is what produced the smeared "pixels running together" look. The estimator now reads each model's declared input contract and feeds NHWC uint8 or NCHW float accordingly; verified DA3 returns a real 518x518 depth map. The pipeline label also names the model in use and shouts `BROKEN - warping by brightness, not depth` if inference ever falls back again.
- **Desktop OCR zone selector:** the Reader section gains Edit/Clear zones over the stream preview (headset parity) â€” drag to add up to 6 capture-space zones (left eye while streaming), drag inside to move, corners to resize, right-click to delete, applied live and persisted. A Mono preview checkbox shows one eye full-width for easier editing (display only; stream layout untouched). Empty still means the default lower dialogue band, so text outside the old fixed third is no longer missed.
- **Desktop Link controller input (PC mode):** B toggles PC continuous TTS on/off (reads current state first so it never drifts; PC checkbox follows), A speaks one OCR pass of the current frame (manual one-shot through zones + translate, bypassing settle/dedupe). Gated on a live stream, repeats ignored. Continuous speech stays pipeline-driven.
- **Desktop Link reader buttons (PC mode):** controller key events never reach the activity (B arrives as system Back, A never arrives), so Speak screen + Continuous toggle live on-screen in the chrome drawer instead, calling the same PC endpoints with the same stream gating. Speak forces re-speech so repeat presses re-read identical text (the engine drops same-text repeats).
- **Toolbar auto-hide on playback:** the bottom toolbar fades out (120ms) while video plays, TTS speaks, or a share/cast session runs, and returns on hover, mirroring the Desktop Link chrome pattern on a faster 1200ms cycle. Hover listeners sit on the content, surfaces, and toolbar itself (non-consuming); pause/stop restores immediately.
- **VR theater scrapped:** the entire VR mode (theater room/screen, control dock, panel feeds, `:vr` module) is removed from the app after the split-surface direction proved all cost, no value. Preserved on the `backup-vr-split-scaffold` branch if ever revisited.
- **Browser chrome buttons:** Home (house), Stop square, and address-clear X join back/forward in the browser bar.
- **Browser protected-video fallback:** page video that refuses capture + black frames (secure-path signature) shows the live page flat with a banner instead of a black stereo box; exits automatically when watchable frames return.
- **3D+ head parallax retired:** the separate 3D+ chip is removed (feature inactive) â€” plain 3D is untouched. The flag is forced off with any persisted ON cleared, the rotation-vector listener never registers, and the sensor math stays guarded at zero so the draw path is unchanged.

### Desktop Link â€” invariants (do not regress)

When changing Quest `DesktopLinkActivity` or PC `QuestLinkServer` / `MirrorSession`, keep these behaviors. Each was a real break (2026-09-15).

| Invariant | Why it broke before | Required behavior |
|-----------|---------------------|-------------------|
| **Codec / Gamingâ†”Movies hot-swap stays live** | Stall **watchdog** treated quiet gaps during `/settings` + surface handoff as a dead stream (`workerAlive=true` + `silentMs>10s` right when 15s grace ended) and called `forceReconnect`, stacking pumps | Watchdog reconnects **only if the pump thread is dead**. On codec/preset change: long **reconnect grace** (~45s). **Never** recreate SurfaceView when producer is already `MEDIA_CODEC`/`CANVAS` â€” wait or let the pump retry |
| **One pump generation at a time** | `forceReconnect` set `wantStream=true` *before* old workers finished â†’ orphans kept looping while a new worker started (`AV1 surface not ready`, interrupted `av1c`) | Split **`streamDesired`** (user intent) from **`wantStream`** (pump gate). Bump **`streamGen`** on stop; pumps exit when `streamGen` mismatches. Only set `wantStream=true` inside `startStream` |
| **Stale LAN beacon must not undo codec** | After switching to AV1/H.264, `discoverPc()` still returned an old `â€¦/sbs.mjpg` beacon and overwrote the URL | Only adopt a discovered URL if its path **matches `selectedCodec`** |
| **No stacked MEDIA_CODEC surface recreates** | Multiple pumps saw a briefly-invalid surface and all ran `MEDIA_CODECâ†’MEDIA_CODEC` recreate â†’ black / incomplete surface | If producer is already the target: **wait up to 5s**, never tear down; only recreate when crossing JPEG (`lockCanvas`) â†” MediaCodec |
| **PC frees viewer slots on drop** | Write loops caught `IOException` and continued â†’ zombie viewers; headset got **503 busy** and could not reconnect | On stream write failure: **break** the loop so `NoteViewerLeft` runs. Send timeout must be Wiâ€‘Fi-tolerant (~8s), not 500ms |
| **JPEG â†” compressed surface producer** | Resetting `surfaceProducer` to `NONE` on reconnect skipped canvasâ†’codec handoff; hide/show alone dropped BLAST | Keep producer across reconnect; **replace `SurfaceView`** only when crossing JPEG (`lockCanvas`) â†” MediaCodec |
| **No lockCanvas on MediaCodec surfaces** | After JPEG, `surfaceCreated`/`surfaceChanged` redrawn the last bitmap onto a fresh MediaCodec surface â†’ BLAST stayed CPU (`cur=2`) so configure failed `already connected (cur=2 req=3)` | Arm **`allowLockCanvas=false`** + clear `latestFrame` before MPEG/AV1; never `lockCanvas` while producer is `MEDIA_CODEC` |
| **Reuse decoder / detach BLAST** | Releasing + reconfiguring MediaCodec every HTTP reconnect left sticky BLAST consumers | Reuse live decoder across reconnects; `setOutputSurface(null)` before release; recover with SurfaceView replace only on configure failure |
| **Audio survives video reconnect** | Stopping Opus UDP on every codec/mode reconnect raced the surface and left audio behind after backlog | Keep receiver across video reconnect; **`flush()` jitter** on reconnect; full `stop()` only on Disconnect |
| **H.264 encode must not force every-AU sync** | `AllSamplesIndependent` + every-frame key hint produced ~300 B mushy AUs while AV1 stayed healthy | Prefer hardware H.264 MFT; periodic ~1 Hz keys; higher MPEG bitrate; signal encode FPS = present (**72**) |
| **PC codec epoch closes old path cleanly** | Clients hung on the wrong `/sbs.*` after a codec change | Bump codec epoch; return **409** with the correct `stream` path; Quest follows redirect / reconnects once |

**Quick regression check (headset + PC session running):** JPEG â†’ MPEG â†’ AV1 â†’ JPEG; Gaming â†’ Movies â†’ Gaming; kill Wiâ€‘Fi briefly and confirm auto-reconnect without tapping Connect. Confirm Headset audio stays in sync across codec switches.

### Desktop Link / streaming
- **Headset audio (Opus):** WASAPI loopback â†’ Concentus Opus @ 48 kHz / 128 kbps â†’ UDP `:8767` unicast to the connected Quest. Quest jitter buffer (~20â€“80 ms) + PLC. Default Sound mode is **Headset** (PC speakers still play). Not OPUS-MT translate. Video reconnect **flushes** jitter (does not tear down the socket).
- **Reconnect / codec surface:** grace + `streamGen` + codec-matched discovery; no lockCanvas on MediaCodec surfaces; reuse decoder with BLAST detach; no stacked MediaCodec recreates (see invariants above).
- **H.264 quality:** drop `AllSamplesIndependent`; prefer hardware MFT; ~1 Hz keyframes; bitrate curve ~4â€“18 Mbps (+ MPEG bump); encode signals **72 Hz** to match present.
- **Toolbar declutter:** TTS prev/play/pause/stop/next sit in a dock pill that **pops up on hover** over the speaker (manual TTS). Browser (globe) and My Books hide only while **Share** is on.
- **AV1 encode fix (PC):** hardware AV1 MFTs are async â€” unlock + event pump (`NeedInput`/`HaveOutput`) and honor `OutputStreamProvidesSamples`. Previously Ensure succeeded but Encode always returned empty.
- AV1 `av1C` exposed on `GET /status` for Quest MediaCodec `csd-0`.
- Codec hot-swap (JPEG â†” MPEG â†” AV1) without restarting the PC session.
- Quest waits for PC `/status` codec confirm before reconnecting; handles HTTP **409** codec-mismatch redirects and **503** busy.
- PC applies Quest `POST /settings` on the accept thread (no UI-dispatcher race).
- Surface handoff when switching JPEG (`lockCanvas`) â†” MPEG/AV1 (`MediaCodec`) to avoid black frames after codec change.
- Quest AV1 decode: prefer `c2.qti.av1.decoder.low_latency`, load `av1c` into `csd-0`.
- **AV1 `av1c` once-only on PC:** stop appending sequence-header OBUs every keyframe (was bloating `csd-0` to 15KB+).
- Quest rejects oversized `av1c` (over 2KB), skips cold-start surface recycle when already valid, waits for surface before claiming a viewer slot, and resumes stream on `surfaceCreated`/`onResume`.
- **Edge smear clean** (PC + Quest Desktop Link): bias depth discontinuities toward background and shrink edge parallax to cut halos around people. Defaults lower **Motion ghosting** (was â€œDepth smoothâ€).
- **MPEG (H.264) lockup fix (PC):** hardware H.264 MFTs are async â€” `H264FrameEncoder` now pumps `NeedInput`/`HaveOutput` events like the AV1 encoder (previously one AU then silence, Quest timing out every ~15s). Encoder enum prefers sync MFTs first (same order as AV1). 15 straight null encodes now surface `Encode: MPEG (H.264) encoder produced no frames â€” try AV1 or JPEG` instead of a silent stall.
- Per-section **Save** on Stream / 3D look / Quest Link / Reader â€” persists to `%LocalAppData%\SpatialLauncherDesktop\user_settings.json`.
- Clearer 3D labels: **3D pop**, **Focus plane**, **Depth refresh**, **Motion ghosting**, **Edge smear clean**.

### Movies / depth
- **3D pop pixelation fix:** bilinear depth + color sampling in SBS warp (DA depth is ~518Â²; nearest lookups looked blocky on Movies). High-quality bilinear resize into the ONNX input and Half-SBS flat pack.
- Movies preset prefers Depth Anything 3 ONNX (`da3_base` â†’ `da3_small` â†’ DA-V2 Base fallback).
- Installer fetches DA3 models from Hugging Face when missing.

### Audio
- **Removed Desktop Link PCâ†’Quest raw-PCM audio** (old Sound UI / UDP PCM). Replaced later by Opus Headset path (see Unreleased).
- PC **Listen** (WASAPI â†’ SenseVoice on the desktop) is unchanged.

### Quest build
- **Cast crash after targetSdk 34 (bug, corrected):** first attempt promoted the FGS only *after* the share-sheet grant â€” but Horizon OS validates `getMediaProjection()` server-side against a live `mediaProjection` FGS, so every cast died with `SecurityException: Media projections require a foreground serviceâ€¦` + `Couldn't launch`. Fix: `launchGame()` promotes at tap time (foreground, bound service), kept across the grant, dropped on dismiss/stop. Bind early without FGS; `createVirtualDisplay()` after grant as before.
- **Browser 3D + HTML5 video (bug):** stereo letterboxing left the live WebView visible as a flat PiP while `WebView.draw()` / poster `<img>` capture froze the 3D frame (HW video overlays are invisible to canvas). Fix: hide the live WebView under stereo after the first good frame; prefer `<video>`â†’canvas JS capture, then WebView `SurfaceView` PixelCopy; never fall back to a static poster when a `<video>` is present. DRM/Widevine frames still cannot be bitmapped.
- **Video player (reel):** My Videos shelf (like My Books); ExoPlayer TextureView + mono-first play; frames feed OCR/TTS/Share/3D; TeeAudioProcessor PCM â†’ Listen on soundtrack; resume after background.
- **Meta Store Packaging.1:** `targetSdk 34`, `installLocation=auto`, `excludeFromRecents` on launch activity, `com.oculus.supportedDevices` for quest2/pro/3/3s, optional mic + headtracking `required=false`.
- **Security.2 trim:** dropped unused `POST_NOTIFICATIONS`, `WAKE_LOCK`, and `FOREGROUND_SERVICE_DATA_SYNC`; Listen deny shows an in-app banner and leaves other features working. Dashboard justifications in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).
- **APK size fix:** stop packing Qwen (~986 MB) + SenseVoice (~1 GB) into the debug APK (AGP `packageDebug` **integer overflow** past ~2GB uncompressed). Piper + OPUS stay in the APK; Qwen/SenseVoice download on first headset use. Optional `-PpackHeavyModels=true`.
- **Release signing:** `tools/create_release_keystore.ps1` + `keystore.properties` (gitignored) wire `assembleRelease` for Meta Store uploads â€” see [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).
- **Lint error fix (Book import):** `BookImportHttp` used `ByteArrayOutputStream.toString(Charset)` (API 33+) with `minSdk 29` â€” `NoSuchMethodError` on Quest (API 29â€“32) when reading import request headers; replaced with `new String(bytes, ISO_8859_1)`.
- **Lint error fix (Listen):** `PlaybackListenEngine.buildRecorder` now checks `RECORD_AUDIO` at the point of use before building the `AudioRecord` (permission already requested in `PanelMainActivity`; guard adds robustness and clears the `MissingPermission` lint error). `lintDebug` is now error-free.
- **Desktop Link status moved (Quest):** Live/mode status (`Live Â· MPEG`, `Reconnectingâ€¦`) moved from the bottom bar into the top of the settings drawer; bottom bar removed.
- **First-use model UX (Listen):** SenseVoice (~1 GB) now asks once before downloading (Download / Later), only fetches on unmetered Wiâ€‘Fi, shows banner progress (`â€¦ X MB`), and Listen reports actionable states (downloading / need Wiâ€‘Fi / needs consent) instead of `SenseVoice files missing from APK`. Help strings corrected (they still claimed no download).
- **Qwen strip (Quest):** removed the dead Novel Translator leftover â€” `QwenPageEngine` / `QwenPageService` (`:qwen`), llama-opencl dep + OpenCL manifest entries, Qwen Gradle/OfflineModelPack wiring. Page Translate is OPUS-only; docs + `page_translate_3d_off` string updated. (Qwen polish was skipped since 09-12: 1.5B echoed prompts.)
- **SenseVoice hardening (Quest):** upstream archive renamed `model.int8.onnx` â†’ `model.onnx` and ships an 894 MB fp32 next to the 239 MB int8 â€” a truncated extract passed existence checks and ORT aborted the process (SIGABRT). Now: selective extract (int8 + tokens only), per-entry byte-exact verification, partial cleanup on failure, int8 preferred with size floors. Verified live: int8 byte-exact, Listen transcribing.
- **Mic wording (Quest):** no microphone source exists in the codebase (cast-playback capture only); Listen prompt / Help / privacy now say Android labels the required audio-capture permission â€œMicrophoneâ€ but the mic is never recorded.
- **Quit button (Quest):** X at the end of the dock bar asks once, then kills the process outright (no cached linger). 1.0.1 / versionCode 3.
- **New app icon:** 3D-glasses character (transparent source) across Quest mipmaps + adaptive foreground, desktop ICO (transparent, denser fill for tray), 180px store tile. Export script also emits the ICO + store icon.
- **Store assets:** `docs/store-assets/` built from Meta's asset spec â€” icon 512, spatialized 180 bg+fg, hero/covers/mini, transparent logo lockup, 6Ã— 2560Ã—1440 screenshots (upscaled first drafts). Trailers: user-shot 30s + full promo cut to spec. Dashboard upload still manual.
- **Stretch/fit toggle (Quest):** cast scaling is now a setting (default fit). Canvas path letterboxes tall apps instead of stretching; surface sizing + touch mapping follow the mode. Fixes TTS/OCR boxes misaligning on stretched content.
- **My Books import row (Quest):** shelf has an explicit Import over Wiâ€‘Fi / Stop entry (empty shelf too) â€” import no longer hides behind long-press. Both apps' help now cover the stale-IP/refused fix (Find headset again).

### Docs
- Added [docs/TECH_STACK.md](docs/TECH_STACK.md) (platforms, libraries, ports, models, audio/video path).
- Desktop Link help split: Quest [HELP.md](docs/HELP.md) / in-app covers headset panel controls; [DESKTOP.md](docs/DESKTOP.md) covers PC Stream / Sound / 3D look / Quest Link settings.

### Defaults
- Stream width **1920**, JPEG quality **85**, sharpen **25**, **3D pop 21%**.

### Known issues (2026-09-16)
- If a PC has no AV1 hardware encoder, status shows encode error â€” use MPEG/JPEG.
- Desktop Link launched while the panel is stopped/not focused will wait for a surface; put on the headset and look at the panel if the stream does not start.
- JPEG often still looks sharper than MPEG at the same quality slider; prefer **AV1** on Quest 3 when Wiâ€‘Fi allows.
- Sharpen applies to **JPEG only** (skipped for MPEG/AV1 to save CPU).
- Full SBS still subtracts 8 from the quality slider for all codecs (load relief).

## 2026-09-14 â€” Test notes (codec flow)

| Case | Result |
|------|--------|
| JPEG â†” H.264 hot-swap | Pass â€” `/status` + stream path update; real payloads |
| Wrong URL after switch | Pass â€” PC `409`; Quest log `stream redirect HTTP 409 â†’ â€¦/sbs.mjpg` |
| Headset stays up across JPEG/MPEG | Pass |
| AV1 encode (async MFT + ProvidesSamples) | Pass â€” live payloads + stable `av1c` (~46 B) |
| AV1 on Quest headset | Pass â€” `c2.qti.av1.decoder.low_latency`, stable `viewers=1`, AU stream + picture |
| JPEG â†” MPEG â†” AV1 without disconnect (postâ€“watchdog fix) | Required â€” grace + `streamGen`; must not stack pumps |
| Gaming â†” Movies while streaming | Required â€” settings POST only; no watchdog reconnect mid-load |

## Earlier (Desktop Link thin client)

- Quest **Desktop Link** thin LAN viewer (`DesktopLinkActivity`) with discovery UDP, SBS stream, settings drawer (Live 3D, Full SBS, codec, audio, depth knobs).
- PC **Quest Link** TCP `:8765` (`/sbs.mjpg`, `/sbs.h264`, `/sbs.av1`), discovery `:8766`, audio `:8767`.
- Quick resume: keep HTTP listener up after Stop Session / Quest drop (`EnsureLinkListening`).
- Chrome fade after live; Min hides drawer; Exit finishes activity.
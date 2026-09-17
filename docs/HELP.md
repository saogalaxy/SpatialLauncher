# In-app Help (reference)

This mirrors the **?** Help drawer in Spatial Launcher. Prefer the headset UI when you’re wearing the Quest; use this page for GitHub / store copy.

## How to start

### 1. Cast an app in 3D
Tap a dock app → in the share sheet pick that app (**Just this window**). Leave **3D** on (glasses icon). Tap **power** to stop.  
**Important:** leave the casted app’s own window open so the stream stays live in Spatial Launcher.

### 2. Read on-screen subs / dialogue (English)
Cast the show → **OCR zones** (right sidebar) → box the subtitle band → Save → Done.  
Settings → **Reader pipeline → Read** → **Translate engine → ML Kit OCR** → **TTS on** → continuous (blue loop).  
Speech follows text in the boxes. With no custom boxes, only the lower cast dialogue band is read (not the whole app HUD). Numbers on boxes are **read order** — add top boxes first.

### 3. Listen when there are no subs
Cast the app → tap the **ear** (turns red). Allow mic if asked. Audio → SenseVoice → OPUS (if engine = OPUS) → Piper. Best with **pause breaks** between lines. Tap ear again to stop.

### 4. Translate on-screen foreign text
OCR zones on the text → Settings → **Reader pipeline → Translate** → **Translate engine → OPUS** → TTS on if you want it spoken.

### 5. Share overlay (caption + optional speak)
Settings → **Reader pipeline → Share** → **Translate engine → OPUS** (foreign) or **ML Kit OCR** (English as-read). Shows OCR text on the panel overlay; speaks if TTS continuous is on. OCR runs even when TTS is off (caption only).

### 6. Browser page translate / read
**Globe** → open a page → **Translate** (on-device Qwen) or **Google** (needs network). TTS on, then **Read**, to hear it. Tap **Translate** again to cancel a stuck job.

### 7. Open or import a book
Tap **book** = My Books (tap again or **Close** to dismiss). Tap a title to open. Tap **book** again while reading to close. Long-press book = PC EPUB import on/off (same Wi‑Fi as Novel Translator).

### 8. Desktop Link (PC → Quest 3D)
On Windows, install **Spatial Launcher Desktop**, Start Session (LAN advertise on). Sound defaults to **Headset** (PC speakers still play; mute them for Quest-only). On Quest tap the **monitor** toolbar button — it **auto-finds** the PC. PC owns depth + OCR/TTS/Listen; Quest is a thin SBS viewer with Horizon 3D.

**Stream format:** **JPEG** (sharpest), **AV1** (best compressed on Quest 3), **MPEG** (compatibility). Switch live from Quest or PC. **Gaming** / **Movies** change the depth model without disconnecting. Allow firewall TCP **8765**, UDP **8766** (find PC), UDP **8767** (audio). Details: [DESKTOP.md](DESKTOP.md).

## Reader pipeline (Settings)

Pick one mode. The **Active:** line under the buttons shows the exact path. Selected mode buttons are **green**.

| Mode | Pipeline | When to use |
|------|----------|-------------|
| **Read** | OCR → Piper | English / already-English on-screen text. OCR only when TTS continuous is on. |
| **Translate** | OCR → OPUS → Piper | Foreign on-screen subs/UI → English speech. |
| **Share** | OCR → OPUS → Piper + caption *(or OCR → Piper + caption if ML Kit OCR engine)* | Same as Translate/Read but always OCRs for the overlay; caption on panel. |
| **Listen** | Audio → STT → OPUS → Piper *(or STT → Piper if ML Kit OCR engine)* | No subs — hears cast audio. Turns 3D off. |

**Translate engine** (second row of buttons):

| Engine | Effect |
|--------|--------|
| **OPUS** | Machine-translate non-English before Piper (Translate / Share / Listen). |
| **ML Kit OCR** | Skip OPUS — recognize and speak **as-read** (best for English games). |

**Note:** “Share” here is an Assist mode (caption overlay), not the Meta **Share this window** cast sheet.

## Optional network

| Feature | What needs Wi‑Fi |
|--------|------------------|
| **Google Translate** | Network each time (browser button) |
| **PC EPUB import** | Same Wi‑Fi as Novel Translator on your PC |

**Already in the APK:** female + male TTS (Piper), OPUS JA/ZH/KO, 3D depth, ML Kit OCR AARs. **Qwen** (Page Translate) and **SenseVoice** (Listen) download on first use over Wi‑Fi (~1 GB each). No Play Store model downloads.

## Dual-mode icons

| Control | Modes |
|--------|--------|
| **3D** | Off = “3D” text · On = glasses |
| **Speaker** | Settings turns TTS on. Once (green) vs continuous (blue loop). Long-press can switch once/continuous when TTS is on. |
| **Listen** | Off · On (red). Turns TTS continuous + Assist Listen + 3D off while active. |
| **Books** | Tap = shelf · Long-press = PC import · Tap again closes shelf or open book |
| **Dock apps** | Tap = cast · Long-press = remove from dock |

## Settings sliders (reading)

| Slider | Effect |
|--------|--------|
| **OCR smoothness** | Wait for a stable on-screen caption before speaking (Read / Translate / Share) |
| **Listen smoothness** | Audio Listen phrase length (ear button) |
| **Read speed** | Piper speech pace |

## Useful combos

- **Cast + 3D** — spatial view of a mirrored app  
- **English game + Read + ML Kit OCR** — OCR → Piper, no OPUS  
- **Foreign subs + Translate + OPUS** — OCR → OPUS → Piper  
- **Share + OPUS** — OCR → OPUS → Piper + caption overlay  
- **Share + ML Kit OCR** — OCR → Piper + caption (English as-read)  
- **Listen + cast** — talk / no-subs audio path  
- **Browser + Translate / Google** — page MT + optional Read  
- **Books long-press + PC** — Novel Translator EPUB import  
- **Listen vs OCR** — Listen = audio; OCR+TTS = on-screen text. Don’t run both for the same job.

## Toolbar buttons (short)

| Icon | Action |
|------|--------|
| Power | Stop cast |
| 3D | Stereo depth toggle |
| Globe | Browser |
| Book | My Books / close reader |
| Speaker | TTS speak |
| Ear | Listen |
| Monitor | Desktop Link (PC SBS + Headset audio) |
| Boxes | OCR zones |
| ? | This help |
| Gear | Settings (depth, TTS, Reader pipeline, OCR / Listen smoothness) |

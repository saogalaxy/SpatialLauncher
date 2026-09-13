# In-app Help (reference)

This mirrors the **?** Help drawer in Spatial Launcher. Prefer the headset UI when you’re wearing the Quest; use this page for GitHub / store copy.

## How to start

### 1. Cast an app in 3D
Tap a dock app → in the share sheet pick that app (**Just this window**). Leave **3D** on (glasses icon). Tap **power** to stop.  
**Important:** leave the casted app’s own window open so the stream stays live in Spatial Launcher.

### 2. Read on-screen subs / dialogue
Cast the show → **OCR zones** (right sidebar) → box the subtitle band → Save → Done. Settings → **TTS on** → continuous (blue loop). Speech follows text in the boxes. With no custom boxes, only the lower cast dialogue band is read (not the whole app HUD). Numbers on boxes are **read order** — add top boxes first.

### 3. Listen when there are no subs
Cast the app → tap the **ear** (turns red). Allow mic if asked. Translate uses bundled OPUS (JA/ZH/KO in the APK). Best with **pause breaks** between lines. Tap ear again to stop.

### 4. Translate on-screen foreign text
OCR zones on the text → Settings → Assist → **Translate** → TTS on if you want it spoken.

### 5. Browser page translate / read
**Globe** → open a page → **Translate** (on-device) or **Google** (needs network). TTS on, then **Read**, to hear it. Tap **Translate** again to cancel a stuck job.

### 6. Open or import a book
Tap **book** = My Books (tap again or **Close** to dismiss). Tap a title to open. Tap **book** again while reading to close. Long-press book = PC EPUB import on/off (same Wi‑Fi as Novel Translator).

## Optional network

| Feature | What needs Wi‑Fi |
|--------|------------------|
| **Google Translate** | Network each time (browser button) |
| **PC EPUB import** | Same Wi‑Fi as Novel Translator on your PC |

**Already in the APK (store + desktop installer):** female + male TTS, SenseVoice, OPUS JA/ZH/KO, page Qwen, 3D depth, ML Kit OCR AARs.

## Dual-mode icons

| Control | Modes |
|--------|--------|
| **3D** | Off = “3D” text · On = glasses |
| **Speaker** | Settings turns TTS on. Once (green) vs continuous (blue loop). Long-press can switch once/continuous when TTS is on. |
| **Listen** | Off · On (red). Turns TTS continuous + 3D off while active. |
| **Books** | Tap = shelf · Long-press = PC import · Tap again closes shelf or open book |
| **Dock apps** | Tap = cast · Long-press = remove from dock |

## Settings sliders (reading)

| Slider | Effect |
|--------|--------|
| **OCR smoothness** | Wait for a stable on-screen caption before speaking (Read / Translate / Share Assist) |
| **Listen smoothness** | Audio Listen phrase length (ear button) |
| **Read speed** | Piper speech pace |

## Useful combos

- **Cast + 3D** — spatial view of a mirrored app  
- **OCR + Read** — OCR → Piper (English / as-read; no OPUS)  
- **OCR + Assist Translate** — OCR → OPUS → Piper  
- **OCR + Assist Share** — OCR → OPUS → Piper + caption overlay  
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
| Boxes | OCR zones |
| ? | This help |
| Gear | Settings (depth, TTS, Assist, OCR / Listen smoothness) |

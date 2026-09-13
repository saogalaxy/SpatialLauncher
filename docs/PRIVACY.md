# Privacy Policy — Spatial Launcher

**Effective date:** September 12, 2026  
**App:** Spatial Launcher (`com.spatiallauncher.app`) for Meta Quest / Horizon OS  
**Developer:** saogalaxy  

This policy describes how Spatial Launcher handles information. It replaces earlier drafts that claimed *no* network use; the current build is still **primarily on-device**, with a few **optional, user-triggered** downloads.

## 1. Core philosophy

Spatial Launcher is built so that **screen frames, speech audio, OCR text, translations, and e-book content are processed on your headset** for normal reading and casting. We do **not** run analytics, advertising, or account systems, and we do **not** upload your screen, microphone audio, or book text to our own servers (we do not operate a backend for this app).

## 2. Information we do not collect

- No accounts, email, or login  
- No analytics / crash telemetry SDKs from us  
- No advertising IDs or tracking pixels  
- No permanent archive of MediaProjection frames or Listen audio on our servers  

Screen capture buffers and ASR audio are used in memory for the active feature and are not uploaded by Spatial Launcher to a Spatial Launcher cloud service.

## 3. What stays on your device

| Feature | Local processing |
|--------|-------------------|
| App cast / 3D depth | MediaProjection frames → local stereo / depth |
| OCR zones | ML Kit (and related) OCR on-device |
| Piper TTS | On-device speech synthesis (female voice bundled) |
| Listen | Cast audio → SenseVoice ASR → local MT → Piper |
| Page Translate | On-device Qwen (bundled) when you use in-app Translate |
| My Books / EPUB | Files stored in app storage on the headset |

Settings and pinned dock apps are stored in **local app preferences** on the device only.

## 4. Optional network use (user-triggered)

The app declares `INTERNET` so these **optional** features can work. They run only when you use them:

| When | What leaves / downloads |
|------|-------------------------|
| First **Listen** (ear) | Google **ML Kit** language packs (JA/ZH/KO→EN) may download over Wi‑Fi |
| **Male** TTS voice | Optional Piper voice download (female is bundled) |
| First **Chinese / Korean** OCR caption packs | Optional OPUS model download (Japanese OCR path is bundled) |
| Browser **Google** translate button | Page text may be sent to **Google Translate** over the network for that request |
| **PC EPUB import** (long-press book) | Local **Wi‑Fi LAN** HTTP to your PC running Novel Translator — stays on your network; not a public cloud upload |
| Sideload / updates | Normal package install via your tooling; not part of runtime telemetry |

If you never turn on Listen, never pick Male TTS, never use Google Translate, and never start PC import, the app can run cast / OCR / female TTS / bundled translate without those downloads.

## 5. Permissions (local use)

| Permission | Why |
|------------|-----|
| **MediaProjection** (share sheet) | Capture the window you select for cast, OCR, and Listen audio |
| **RECORD_AUDIO** | Listen path (cast playback / mic as required by the OS) |
| **INTERNET / NETWORK_STATE / WIFI_STATE** | Optional model packs, Google Translate, LAN book import |
| **FOREGROUND_SERVICE** (+ media projection / data sync types) | Keep capture and import services alive while in use |
| **POST_NOTIFICATIONS** | Foreground service / status as required by Android |
| **Package visibility (queries)** | List installed apps for the dock “Add App” picker |

We do not use these permissions to build a profile or sell data.

## 6. Third parties

- **Google ML Kit** — used for OCR and Listen translation packs; downloads/models follow Google’s on-device translate components when you enable those features.  
- **Google Translate (optional button)** — only if you tap **Google** in the browser chrome.  
- **Meta / Horizon OS** — standard system share sheet and store distribution; governed by Meta’s policies.  
- **Piper / sherpa-onnx / SenseVoice / Qwen** — on-device model runtimes; optional male voice may download from a public model host when you choose Male.

We do not embed third-party ad or analytics SDKs.

## 7. Children’s privacy

Spatial Launcher is not directed at children under 13. We do not knowingly collect personal information from children.

## 8. Changes

We may update this policy when features change. The effective date at the top will change; the current version lives in this repository at `docs/PRIVACY.md`.

## 9. Contact

Questions about this policy: open an issue or discussion on the project repository  
https://github.com/saogalaxy/SpatialLauncher  

Or contact the developer through the Meta Horizon app listing when published.

---

**Store note:** Meta Horizon Store requires a **publicly reachable** privacy policy URL. This file in a **private** repo is not enough by itself — publish this same text (GitHub Pages, public gist, or a published Google Doc) and paste that URL into the Developer Dashboard.

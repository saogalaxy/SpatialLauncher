# Privacy Policy — Spatial Launcher

**Effective date:** September 13, 2026  
**App:** Spatial Launcher (`com.spatiallauncher.app`) for Meta Quest / Horizon OS, and **Spatial Launcher Desktop** (Windows companion)  
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
| Desktop Link | Optional LAN MJPEG from Spatial Launcher Desktop (same Wi‑Fi); frames stay on your network |
| OCR zones | ML Kit (and related) OCR on-device |
| Piper TTS | On-device speech synthesis (female voice bundled) |
| Listen | Cast audio → SenseVoice ASR → optional bundled OPUS → Piper |
| Reader pipeline | Local mode preference (Read / Translate / Share / Listen) and OPUS vs as-read engine |
| Page Translate | On-device OPUS-MT when you use in-app Translate |
| My Books / EPUB | Files stored in app storage on the headset |

Settings and pinned dock apps are stored in **local app preferences** on the device only.

## 4. Optional network use (user-triggered)

The app declares `INTERNET` so these **optional** features can work. They run only when you use them:

| When | What leaves / downloads |
|------|-------------------------|
| Browser **Google** translate button | Page text may be sent to **Google Translate** over the network for that request |
| **PC EPUB import** (long-press book) | Local **Wi‑Fi LAN** HTTP to your PC running Novel Translator — stays on your network; not a public cloud upload |
| **Desktop Link** (Quest ↔ Spatial Launcher Desktop) | SBS video frames on your **LAN only** (HTTP MJPEG to the headset). Not uploaded to our servers |
| Sideload / updates | Normal package install via your tooling; not part of runtime telemetry |

If you never use Google Translate and never start PC import, the app can run cast / OCR / Listen / TTS / on-device translate **fully offline** (no Play Store, no model downloads). Male TTS and ZH/KO OPUS ship in the same APK as the Meta Store and desktop installer builds.

Listen uses bundled OPUS (not Google ML Kit Translate).

## 5. Permissions (local use)

| Permission | Why |
|------------|-----|
| **MediaProjection** (share sheet) | Capture the window you select for cast, OCR, and Listen audio |
| **RECORD_AUDIO** | Listen path: captures the cast app’s playback audio only (MEDIA/GAME usages, own app excluded). Android requires this permission for any audio capture and labels it “Microphone” — the microphone itself is never opened or recorded |
| **INTERNET / NETWORK_STATE / WIFI_STATE** | Optional Google Translate button, LAN book import |
| **FOREGROUND_SERVICE** (+ media projection / data sync types) | Keep capture and import services alive while in use |
| **POST_NOTIFICATIONS** | Foreground service / status as required by Android |
| **Package visibility (queries)** | List installed apps for the dock “Add App” picker |

We do not use these permissions to build a profile or sell data.

## 6. Third parties

- **Google ML Kit** — OCR text recognition only (language AARs bundled in the APK; no Play Store model download). Listen/OCR **translate** uses bundled OPUS, not ML Kit Translate.  
- **Google Translate (optional button)** — only if you tap **Google** in the browser chrome.  
- **Meta / Horizon OS** — standard system share sheet and store distribution; governed by Meta’s policies.  
- **Piper / sherpa-onnx / SenseVoice / OPUS** — on-device model runtimes; female + male Piper and JA/ZH/KO OPUS are packed into the APK at build time. SenseVoice downloads once over Wi‑Fi with your consent.

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

**Store note:** Meta Horizon Store needs a **publicly reachable** privacy policy URL:

`https://github.com/saogalaxy/SpatialLauncher/blob/main/docs/PRIVACY.md`

# Screenshot guide (store + GitHub)

Capture in **VR mode** with Meta Cam / Quest Capture when possible so the panel sits in the environment.

## Prep
- Developer Mode Quest, Spatial Launcher installed  
- Clean cast target (game or browser with clear UI)  
- Wait ~2s after taps so toasts clear  
- Name files as below under `docs/screenshots/`

## Shoot order

| File | Shot | Setup |
|------|------|--------|
| `01-dock-home.jpg` | Dock / home | Idle, no cast, VR environment behind panel |
| `01b-share-dialog.jpg` | Share sheet | Dock app tapped; **app selected** (Just this window); don’t Share yet |
| `02-cast-source-open.jpg` | Cast + source open | Share done; **source app window still open** beside Spatial Launcher; 3D on (glasses) |
| `03-3d-off.jpg` | 3D off | Same scene; toolbar shows **“3D”** text |
| `04-settings-depth.jpg` | Settings | Gear open; Depth / Advanced 3D visible |
| `05-ocr-zones.jpg` | OCR editor | Boxes on dialogue; right sidebar Zones for: app; Add/Save/Done |
| `06-tts-continuous.jpg` | TTS continuous | Settings closed; blue loop / reading dialogue |
| `07-listen.jpg` | Listen | Ear **red**; cast with talky content |
| `08-browser.jpg` | Browser | Globe; clean page; Translate / Google / Read visible |
| `09-page-translate.jpg` | Page Translate | Progress (e.g. `12/38`) on foreign page |
| `10-my-books.jpg` | My Books shelf | Book dialog + titles |
| `10b-book-open.jpg` | Book open | Chapter text + Prev/Next |
| `11-help.jpg` | Help | **?** open; dark Help / How to start |
| `12-ocr-book-reading.jpg` | OCR + book | Book open; karaoke / highlight while TTS reads |

### Store minimum (5)
`01` → `02` → `05` → `07` → `08`

### Teaching shot callouts
- **01b / 02:** user must leave the casted app open  
- **07:** Listen is audio-only today (no transcript overlay)  
- **12:** best “product in use” reading shot  

## Exporting from a Cursor / metavr shoot
Meta Cam captures also land on the headset at `/sdcard/Oculus/Screenshots/` (e.g. `com.oculus.vrshell-YYYYMMDD-HHMMSS.jpg`). Pull those and rename to the list above, or capture with:

`npx metavr capture screenshot -o docs/screenshots/<name>.jpg`

## Contrast note
Help and Settings use an opaque dark drawer so text stays readable in the idle frosted UI.

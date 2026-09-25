# VR split scaffold — handoff

Branch: `vr-split-scaffold` (off `vr-theater`).
Goal: in VR theater mode, split the panel into two native surfaces —
the cast content stays on the big theater screen, and the **controls** move to
a small dock quad parked below the sightline. While the theater session is
live, the 2D panel yields (no local draw; feeds keep flowing).

All scaffold code is marked `SCAFFOLD (vr-split)` / `TODO(vr-split)`.
Nothing here ships to the Store: beta lane only, per `AGENTS.md`.

## What is already wired

| Piece | Where | State |
|---|---|---|
| Dock bridge API (`setDockActive`, `isDockActive`, `pushControlFrame`, `dockFrameW/H`) | `vr/.../vr/VrBridge.java` | Done |
| Dock staging buffer (own lock, 1024px cap) + `pushControlFrame` JNI | `vr/src/main/cpp/vr_room.c` | Done |
| Dock GL texture (created in `theaterGlInit`, deleted in `theaterShutdown`) | `vr_room.c` | Done |
| `dockPushTexture()` upload | `vr_room.c` | Done |
| `dockDrawQuad()` — reuses theater program + curved mesh, own model matrix (under screen, `DOCK_X/Y/SCALE/Z_PULL`) | `vr_room.c` | Done, pose is first-guess |
| Dock draw call inside the per-eye loop | `theaterRenderViews`, `vr_room.c` | Done |
| Dock reflection plumbing in the panel (`feedDockBridge`, 150ms throttle) | `PanelMainActivity.java` | Done |
| `VrActivity` toggles `setDockActive` alongside `setTheaterActive` | `VrActivity.java` | Done |
| Theater-active tracking (`lastTheaterActive`) + skip local panel draw while live | `PanelMainActivity.drawStereoMirrorFrame` | Done |

## What Glimmer must implement (in order)

1. **`renderControlsBitmap()`** (`PanelMainActivity.java`) - DONE: detached strip with transport play glyph + seek, 3D state, mode readout (1024x256, UI-thread render, bridge-probe gated).
   Build a detached view hierarchy with ONLY the controls (transport row, 3D
   toggle, mode buttons — never the cast surface). `measure()` + `layout()` it
   at a fixed size (suggest 1024x512), draw to a `Bitmap`-backed `Canvas` on
   the UI thread, cache and return the bitmap. Must not touch the activity
   window: the panel is paused while the theater owns the display.
2. **On-device pose tuning.** `DOCK_X`, `DOCK_Y`, `DOCK_SCALE`, `DOCK_Z_PULL`
   in `vr_room.c` are first guesses. Put the headset on, check: dock below the
   sightline to the screen, readable at ~arm's length, never overlapping the
   screen from the seated eye position.
3. **Tilt.** `DOCK_TILT_DEG` is 0 and unused. Add an X-rotation into the dock
   model matrix in `dockDrawQuad()` so the console tips up toward the user.
4. **Flat quad mesh.** The dock currently reuses the curved theater mesh. Give
   it a small flat quad (own VBO) — cheaper and crisper for a control console.
5. **Input (the real milestone).** The dock is view-only. Add controller-ray
   action polling in `vr_room.c`, hit-test against the dock quad, map the hit
   to 2D coordinates on the controls bitmap, and inject as touch into the
   panel's control handlers. Do not start this until 1–4 are verified
   on-headset; a dead dock is worse than no dock.
6. **Decide the overlay's fate.** `VrActivity` still re-fronts the 2D panel as
   an overlay 8s after session start. Once the dock carries the controls,
   that overlay likely goes away (or becomes content-only). Keep behavior
   deliberate — don't leave both.

## Verification (mandatory, per AGENTS.md)

- `.\gradlew.bat :app:assembleBeta :app:lintDebug --no-daemon` — both green.
- Overlap check: `git diff <base> -U0 -- app/src/main` vs
  `lint-results-debug.txt` — zero findings in changed hunks.
- On-device: `:app:assembleBeta` → install same-signature `--replace` →
  enter/exit theater, confirm dock appears, screen unaffected, logcat on the
  feature TAG. Never declare it working without log or eyes-on evidence.
- `CHANGELOG.md` entry already added; extend it if scope grows.
- This scaffold was authored off-device; the first local builds
  (`assembleRelease` + `assembleBeta` + `lintDebug`) are green with zero new
  lint findings. On-device pose tuning (item 2) still needs eyes-on confirmation.

## Session log (2026-09-25, vr-split-scaffold)

- Overlay re-front is 8s, not 1.5s (matches `VrActivity` 8000ms delay).
- Deleted dead `theaterBarPush`/`pushBarStrip` probe in `PanelMainActivity`
  (method never existed on `VrBridge`; dock probe `pushControlFrame` kept).
- `VrActivity` panel auto-launch `catch` now `Log.w`s instead of swallowing.
- Fallback gate kept: `testVbo` draws only when `roomPropCount == 0`.
- Loader uses GLB index buffers (`DrawElements`, 16/32-bit) with `idx=` skip
  counter; non-indexed prims still `DrawArrays`. Bad indices fall back loudly.
- Loader recurses child nodes with accumulated parent transforms (plus the
  Y-180 facing spin); Blender hierarchy no longer matters.

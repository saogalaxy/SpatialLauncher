# Spatial Launcher

OpenXR native (C++/NDK) Android project for a Quest spatial launcher: an immersive
`NativeActivity` app that wraps 2D Android UI panels (`android.view.Surface`) as OpenXR
quad composition layers placed in the user's space.

## Project layout

```
app/
  build.gradle                     # module build config, NDK/CMake, OpenXR loader dep
  src/main/
    AndroidManifest.xml             # OpenXR IMMERSIVE_HMD intent filter + spatial permissions
    cpp/
      CMakeLists.txt
      Main.cpp                      # android_main() entry point / event + render loop
      OpenXRContext.h / .cpp         # XrInstance/XrSession lifecycle, extension negotiation
      SurfaceLayer.h / .cpp          # main C++ surface-wrapping hooks (see below)
      HandInput.h / .cpp             # XR_EXT_hand_tracking wrapper: index fingertip poses
      PanelInteractor.h / .cpp       # hit-tests fingertips against panel quads, dispatches
                                      # poke events into Java via PanelBridge
    java/com/spatiallauncher/app/panel/
      PanelElement.java              # one interactive hit-region on a panel
      PanelSurfaceView.java          # generic Surface renderer + poke routing
      PanelBridge.java               # JNI landing point for PanelInteractor
      DockPanel.java                 # dock content: icons, layout, per-icon PanelElements
    res/drawable/ic_dock_*.xml       # clean vector icon set for the dock
```

## Surface-wrapping hooks

`SurfaceLayer` (`app/src/main/cpp/SurfaceLayer.{h,cpp}`) is the core native hook that
turns an Android `Surface` into a composited OpenXR quad layer:

- `SurfaceLayer::LoadExtensionFunctions()` resolves `xrCreateSwapchainAndroidSurfaceKHR`
  from `XR_KHR_android_surface_swapchain` once, at session start.
- `SurfaceLayer::CreateSurfaceBackedSwapchain()` creates the swapchain and returns the
  backing `jobject` Surface, falling back to `XR_FB_android_surface_swapchain_create`
  when the KHR extension isn't available.
- `SurfaceLayer::BuildQuadLayer()` produces the `XrCompositionLayerQuad` for that panel,
  using `SetPlacement()` (pose + size in meters) and `SetSpace()` (reference space).

`OpenXRContext` (`app/src/main/cpp/OpenXRContext.{h,cpp}`) owns instance/session
creation, extension negotiation (querying both surface-swapchain extensions before
requesting them), the session-state machine, and the per-frame `xrWaitFrame` /
`xrBeginFrame` / `xrEndFrame` loop that will submit each panel's quad layer.

## Direct hand-tracking / touch interaction

Every panel supports direct poke interaction (no controller ray required), driven by:

- `HandInput` — wraps `XR_EXT_hand_tracking`, exposing each hand's index fingertip pose.
  Falls back to `IsAvailable() == false` cleanly if the runtime doesn't support it.
- `PanelInteractor` — per frame, hit-tests both fingertips against every registered
  panel's world-space quad (`SurfaceLayer::GetPlacement()`), and calls
  `PanelBridge.onPoke(panelId, u, v, action)` in Java when a fingertip is within
  ~2cm of a panel's front face (release at ~3.5cm, to avoid jitter at the threshold).
- `PanelBridge` / `PanelSurfaceView` / `PanelElement` (Java) — route that normalized hit
  point to whichever registered element's bounds contain it, and paint a hover/press
  highlight. Because hit-testing walks a generic element list, **every** control drawn
  on a panel (dock icons, settings toggles, sliders, buttons) gets direct-touch support
  automatically just by registering a `PanelElement` — no native code changes needed
  per widget.
- `DockPanel` (Java) draws the dock's frosted pill background and one vector icon per
  slot (`res/drawable/ic_dock_*.xml`: My Games, Project Aethel, Spatial_Sim, MetaVR
  Dashboard, Add New Game), registering each icon as a `PanelElement`.

## Build requirements

- JDK 17
- Android SDK: platforms 26/32/34, build-tools 34.0.0, NDK `27.0.12077973`, CMake `3.22.1`
- A Quest headset with Developer Mode enabled, connected via USB or `metavr device connect <ip>`

## Build & deploy

```powershell
./gradlew assembleDebug
metavr app install app/build/outputs/apk/debug/app-debug.apk
metavr app launch com.spatiallauncher.app
```

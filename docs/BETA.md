# Beta lane

The beta lane is a separate build type that co-installs with the store app:
package `com.spatiallauncher.app.beta`, version name `1.0.2-beta`, debug-signed,
own data directory. **Never upload a beta APK to a Meta Store channel** — it is
debug-signed and the Store will reject it.

## Publish a beta

```powershell
.\gradlew.bat :app:assembleBeta "-PbetaBuild=7"
Copy-Item app\build\outputs\apk\beta\app-beta.apk app\build\outputs\apk\beta\SpatialLauncher-beta-7.apk
gh release upload beta "app\build\outputs\apk\beta\SpatialLauncher-beta-7.apk#SpatialLauncher-beta-7.apk" --clobber
```

`-PbetaBuild` is a plain increasing integer. It is embedded in the APK as
`BuildConfig.BETA_BUILD` and **must** match the number in the asset filename —
that is how the in-app check compares builds. Quote the property in PowerShell.

The `beta` release on GitHub always carries the newest beta APK, and `--clobber`
replaces the asset. Old assets are worth deleting so the download page shows one
file.

> PowerShell splits an unquoted `-PbetaBuild=2026.09.1` at the dot and Gradle
> fails with `Task '.1' not found`. Keep the marker a plain integer and quote it.

## In-app update notice

Beta builds check the rolling release once every 6 hours on panel start and show
an in-app banner when the published marker is newer than the running one. It is
gated on the `.beta` package name, so the store build never runs it.

The banner offers **Install** / **Later**. **Install** downloads the published
asset and hands it to the Android package installer, falling back to the
unknown-sources settings screen if that handoff is refused. This is why the beta
lane declares `REQUEST_INSTALL_PACKAGES` and a `FileProvider` in `src/beta` -
both are absent from the release APK, which has no in-app installer.

`BETA_BUILD` is defined for every build type (defaulting to `0`) so the release
lane compiles; only the beta lane overrides it.

## What the installers build

| Installer | Builds | Installs to |
| --- | --- | --- |
| `tools/easy_install.ps1` | `:app:assembleBeta` | Quest, `com.sptiallauncher.app.beta` |
| `tools/desktop_easy_install.ps1` | `dotnet publish -c Release -r win-x64 --self-contained` | PC, `%LOCALAPPDATA%\SpatialLauncherDesktop\app` |
| Meta Store (manual) | `:app:assembleRelease` | Quest, `com.sptiallauncher.app` |

The installers target separate packages/locations on purpose, so they co-install
and never overwrite a Store build. Pass `-BetaBuild <n>` to
`tools/easy_install.ps1` to build with the same marker as a published asset.

## Publishing notes

The `beta` **tag** is a snapshot, not a moving pointer: it is not re-pointed at
`main` on each publish, so it stays at the commit it was created at while `main`
advances. That only affects the source reference shown on the release page. The
published **asset** is what the headset downloads and is always current. Moving
the tag is a deliberate force-push, not part of the normal publish step:

```powershell
git tag -f beta origin/main
git push --force origin beta
```

## Verify

```powershell
adb logcat -s BetaUpdate
```

- `beta is current (n)` — published marker matches the running build.
- `new beta available: a -> b` — banner shown.

To test the notify path, upload a higher-numbered asset, clear the rate limit
with `adb shell run-as com.spatiallauncher.app.beta rm -f shared_prefs/beta_update.xml`,
and relaunch the panel.

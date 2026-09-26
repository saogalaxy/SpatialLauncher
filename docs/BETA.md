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

The `beta` tag on GitHub is a rolling release: it always carries the newest beta
APK, and `--clobber` replaces the asset. Old assets are worth deleting so the
download page shows one file.

> PowerShell splits an unquoted `-PbetaBuild=2026.09.1` at the dot and Gradle
> fails with `Task '.1' not found`. Keep the marker a plain integer and quote it.

## In-app update notice

Beta builds check the rolling release once every 6 hours on panel start and show
an in-app banner when the published marker is newer than the running one. It is
gated on the `.beta` package name, so the store build never runs it.

There is **no in-app installer**: the banner links to the release page and the
user downloads and sideloads. Installing in place would need
`REQUEST_INSTALL_PACKAGES`, which the app deliberately does not declare.

`BETA_BUILD` is defined for every build type (defaulting to `0`) so the release
lane compiles; only the beta lane overrides it.

## Verify

```powershell
adb logcat -s BetaUpdate
```

- `beta is current (n)` — published marker matches the running build.
- `new beta available: a -> b` — banner shown.

To test the notify path, upload a higher-numbered asset, clear the rate limit
with `adb shell run-as com.spatiallauncher.app.beta rm -f shared_prefs/beta_update.xml`,
and relaunch the panel.

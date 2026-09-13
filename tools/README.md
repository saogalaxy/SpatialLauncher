# Spatial Launcher — Easy Install (Quest)

Looks for what's missing, builds the debug APK if needed, then installs it on a connected Meta Quest with metavr.

## What it checks
1. JDK (JAVA_HOME or java on PATH)
2. Node.js / npx (for metavr)
3. Connected Quest (`metavr device list`)
4. Debug APK (builds with Gradle if missing)

## Run
Double-click **Install to Quest.bat**  
or:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\easy_install.ps1
```

Optional flags:

```powershell
.\tools\easy_install.ps1 -SkipBuild      # only install an existing APK
.\tools\easy_install.ps1 -NoLaunch       # install but don't open the app
.\tools\easy_install.ps1 -Device SERIAL  # pick a specific headset
```

## Requirements
- Windows PC
- Quest with **Developer Mode** on
- USB (data cable) or `metavr device connect <ip>`
- Accept the USB debugging prompt on the headset when asked

First build can take several minutes (large models). Later installs are faster.

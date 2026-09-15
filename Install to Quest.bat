@echo off
setlocal
cd /d "%~dp0"
title Spatial Launcher — Install to Quest
echo.
echo Spatial Launcher Easy Installer
echo Checks JDK, Node/metavr, Quest, then builds and installs.
echo.
echo STORAGE (read this):
echo   The APK is built on this PC under the repo, then sideloaded to the headset.
echo   Quest storage: debug APK with offline models is typically ~2.5+ GB on the headset.
echo   PC only keeps build outputs in this folder (not a separate C: app install for Quest).
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\easy_install.ps1"
if errorlevel 1 (
    echo.
    echo Setup did not finish. Fix the message above, then double-click this file again.
    pause
    exit /b 1
)

echo.
echo READY TO GO: YES
pause
endlocal
exit /b 0

@echo off
setlocal
cd /d "%~dp0"
title Spatial Launcher Desktop — Easy Install
echo.
echo Spatial Launcher Desktop Easy Installer
echo Checks .NET 8 SDK, builds, publishes, installs, then auto-launches.
echo.
echo STORAGE (read this):
echo   Installs to your Windows user profile drive (usually C:), not a drive picker.
echo   Location: %%LocalAppData%%\SpatialLauncherDesktop\
echo   Typical size: ~450 MB app + ~470 MB depth models  (~900 MB total on PC).
echo   Uninstall: Settings - Apps - Spatial Launcher Desktop
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\desktop_easy_install.ps1"
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

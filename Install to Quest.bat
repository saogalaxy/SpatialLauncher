@echo off
setlocal
cd /d "%~dp0"
title Spatial Launcher — Install to Quest
echo.
echo Spatial Launcher Easy Installer
echo Checks JDK, Node/metavr, Quest, then builds and installs.
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

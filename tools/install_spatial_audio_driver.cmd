@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install_spatial_audio_driver.ps1"
echo.
echo Exit %ERRORLEVEL% ? log: %LOCALAPPDATA%\SpatialLauncherDesktop\logs\audio_driver_install.log
pause

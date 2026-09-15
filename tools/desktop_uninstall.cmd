@echo off
setlocal
title Uninstall Spatial Launcher Desktop
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1"
endlocal
exit /b %ERRORLEVEL%

@echo off
setlocal
title TabletDisplay Android Release
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\package-release.ps1"
exit /b %errorlevel%

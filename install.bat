@echo off
REM Double-click or run from cmd: builds TubeOffline and installs it via adb.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*

@echo off
rem Double-click: installs/updates AutoTube on the USB-connected phone so Android Auto lists it.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-AutoTube.ps1" %*
pause

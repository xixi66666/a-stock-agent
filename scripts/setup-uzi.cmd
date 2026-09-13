@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-uzi.ps1" %*
exit /b %errorlevel%

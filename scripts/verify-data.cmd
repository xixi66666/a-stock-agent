@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify-data.ps1"
exit /b %ERRORLEVEL%

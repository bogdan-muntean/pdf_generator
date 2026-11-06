@echo off
rem Double-click to install PDF Generator: builds it and creates the "PDF Generator" shortcut on the
rem Desktop and in this folder. Run it again after updating the code to install the new version.
rem Needs Java 21 and Maven (see README). Details: create-shortcut.ps1
title Install PDF Generator
cd /d "%~dp0"
echo Installing PDF Generator (the first build takes a minute)...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0create-shortcut.ps1" %*
if errorlevel 1 (
    echo.
    echo Installation did not finish: see the message above.
    pause
    exit /b 1
)
echo.
echo Done. Start the app from the "PDF Generator" shortcut on the Desktop or in this folder.
timeout /t 8

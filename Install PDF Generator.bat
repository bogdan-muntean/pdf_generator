@echo off
rem Double-click to install PDF Generator: finds Java 21 (or offers to install it), builds the app and creates
rem the "PDF Generator" shortcut on the Desktop and in this folder. Run it again after updating the code.
rem Nothing else is needed: Maven and Node are downloaded by the build (ADR-0030). Details: create-shortcut.ps1
title Install PDF Generator
cd /d "%~dp0"
echo Installing PDF Generator...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0create-shortcut.ps1" %*
if errorlevel 1 (
    echo.
    echo Installation did not finish: see the message above.
) else (
    echo.
    echo Done. Start the app from the "PDF Generator" shortcut on the Desktop or in this folder.
)
echo.
pause

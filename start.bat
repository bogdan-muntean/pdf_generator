@echo off
rem Starts the viewer WITH a console window (useful to see logs). For everyday use, run
rem "Install PDF Generator.bat" once and use the "PDF Generator" desktop shortcut instead.
rem The app picks a free port and opens the browser itself. Ctrl+C or Quit stops it cleanly.
cd /d "%~dp0"
if not exist target\pdf-generator.jar (
    echo Building pdf-generator...
    call "%~dp0mvnw.cmd" -q package -DskipTests || exit /b 1
)
java -jar target\pdf-generator.jar serve %*

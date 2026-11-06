@echo off
rem Command line: render.bat list ^| render.bat render cv-en ^| render.bat render --all ^| render.bat --help
cd /d "%~dp0"
if not exist target\pdf-generator.jar (
    echo Building pdf-generator...
    call mvn -q package -DskipTests || exit /b 1
)
java -jar target\pdf-generator.jar %*

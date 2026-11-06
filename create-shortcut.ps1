<#
.SYNOPSIS
  Builds pdf-generator, installs it into app\ and creates a "PDF Generator" shortcut on the Desktop and in
  this project folder (and optionally in the Start Menu).

.DESCRIPTION
  The shortcut starts the app without a console window (javaw). The app picks a free port, opens the
  browser and shows a tray icon (Open viewer / Quit). Safe to run again: run it after changing the code
  to install the new build.

  The shortcut runs app\pdf-generator.jar, a COPY of the build. Rebuilding (mvn package) therefore never
  touches the jar of a running app. Replacing a jar under a running JVM breaks it (ADR-0021).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File create-shortcut.ps1
  powershell -ExecutionPolicy Bypass -File create-shortcut.ps1 -StartMenu
  powershell -ExecutionPolicy Bypass -File create-shortcut.ps1 -NoFolderShortcut   # Desktop only
#>
param(
    [switch] $StartMenu,
    [switch] $SkipBuild,
    [switch] $NoFolderShortcut
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$built = Join-Path $root 'target\pdf-generator.jar'
$appDir = Join-Path $root 'app'
$jar = Join-Path $appDir 'pdf-generator.jar'
$icon = Join-Path $appDir 'app.ico'
$instanceFile = Join-Path $root 'run\instance.properties'

function Get-RunningPort {
    if (-not (Test-Path $instanceFile)) { return $null }
    $line = Get-Content $instanceFile | Where-Object { $_ -like 'port=*' } | Select-Object -First 1
    if (-not $line) { return $null }
    $port = ($line -split '=')[1]
    try {
        Invoke-RestMethod "http://127.0.0.1:$port/api/ping" -TimeoutSec 2 | Out-Null
        return $port
    } catch {
        return $null
    }
}

if (-not $SkipBuild) {
    Write-Host 'Building pdf-generator (mvn package)...'
    Push-Location $root
    try {
        & mvn -q -B package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit code $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path $built)) { throw "Jar not found: $built" }

# A broken jar fails inside javaw before the app can show any message: check it really starts.
$java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$versionOutput = & $java -jar $built --version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch 'pdf-generator') {
    throw "The built jar does not start (it may be incomplete). Run 'mvn package' and check its output.`n$versionOutput"
}

# The installed copy cannot be replaced while the app runs from it: ask the app to quit cleanly first.
$port = Get-RunningPort
if ($port) {
    $answer = Read-Host "PDF Generator is running (port $port). Close it now to install the new version? Unsaved edits in the editor are lost. [y/N]"
    if ($answer -notmatch '^[yY]') { Write-Host 'Nothing installed.'; exit 1 }
    Invoke-RestMethod -Method Post "http://127.0.0.1:$port/api/shutdown" -Headers @{ 'X-Pdfgen-Action' = 'shutdown' } | Out-Null
    $deadline = (Get-Date).AddSeconds(30)
    while ((Test-Path $instanceFile) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 300 }
    Start-Sleep -Milliseconds 500
}

New-Item -ItemType Directory -Force $appDir | Out-Null
try {
    Copy-Item $built $jar -Force
} catch {
    throw "Cannot replace $jar. Is PDF Generator still running? Quit it from the tray icon and try again."
}
Copy-Item (Join-Path $root 'src\main\resources\launcher\app.ico') $icon -Force

# javaw = Java without a console window. Prefer JAVA_HOME, else whatever is on PATH.
$javaw = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javaw.exe'))) {
    $javaw = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
} else {
    $command = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($command) { $javaw = $command.Source }
}
if (-not $javaw) { throw 'javaw.exe not found: install Java 21 or set JAVA_HOME' }

$targets = @([Environment]::GetFolderPath('Desktop'))
if ($StartMenu) { $targets += [Environment]::GetFolderPath('Programs') }
# Also next to the project files, so the app can be started from Explorer here (git-ignored, ADR-0028).
if (-not $NoFolderShortcut) { $targets += $root }

$shell = New-Object -ComObject WScript.Shell
foreach ($folder in $targets) {
    $path = Join-Path $folder 'PDF Generator.lnk'
    $shortcut = $shell.CreateShortcut($path)
    $shortcut.TargetPath = $javaw
    $shortcut.Arguments = "-jar `"$jar`" serve"
    $shortcut.WorkingDirectory = $root          # so data\, output\, logs\ and run\ resolve to this folder
    $shortcut.IconLocation = "$icon,0"
    $shortcut.Description = 'PDF Generator - CVs and cover letters (local)'
    $shortcut.WindowStyle = 7
    $shortcut.Save()
    Write-Host "Shortcut created: $path"
}
Write-Host "Installed: $jar"
Write-Host "Java: $javaw"

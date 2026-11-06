<#
.SYNOPSIS
  Builds pdf-generator, installs it into app\ and creates a "PDF Generator" shortcut on the Desktop and in
  this project folder (and optionally in the Start Menu).

.DESCRIPTION
  The shortcut starts the app without a console window (javaw). The app picks a free port, opens the
  browser and shows a tray icon (Open viewer / Quit). Safe to run again: run it after changing the code
  to install the new build.

  On a new computer it needs nothing but Windows and internet: it finds Java 21 (or offers to install it with
  winget) and builds with the Maven Wrapper (mvnw.cmd), which downloads Maven and Node itself (ADR-0030).
  The full build output goes to logs\install-<time>.log.

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
    [switch] $NoFolderShortcut,
    [switch] $BuildOnly          # build and check the jar, install nothing (for testing the setup)
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$built = Join-Path $root 'target\pdf-generator.jar'
$appDir = Join-Path $root 'app'
$jar = Join-Path $appDir 'pdf-generator.jar'
$icon = Join-Path $appDir 'app.ico'
$instanceFile = Join-Path $root 'run\instance.properties'

. (Join-Path $root 'installer\JavaSetup.ps1')
$jdk = Get-OrInstallJdk
$env:JAVA_HOME = $jdk                        # this process only: mvnw.cmd and the checks below use it
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
Write-Host ""

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
    $logDir = Join-Path $root 'logs'
    New-Item -ItemType Directory -Force $logDir | Out-Null
    $log = Join-Path $logDir ("install-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
    $maven = Join-Path $root 'mvnw.cmd'
    if (-not (Test-Path $maven)) { $maven = 'mvn' }   # fallback for an old checkout without the wrapper
    Write-Host ''
    Write-Host 'Step 2 of 3: building PDF Generator.'
    Write-Host '  The first time this downloads Maven, Node and the libraries (internet needed, a few minutes).'
    Write-Host "  Progress is written to $log"
    Push-Location $root
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'      # Maven writes warnings to stderr; the exit code decides
        & cmd /c "`"$maven`" -B package -DskipTests > `"$log`" 2>&1"
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $previous
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) {
        $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
        if ($text -match 'UnknownHost|Could not transfer|Could not resolve|Connection (refused|reset|timed out)|Unable to tunnel|No such host|Failed to download|Cannot download') {
            throw "The build could not download what it needs. Check the internet connection (and any proxy or antivirus), then try again.`nDetails: $log"
        }
        throw "The build failed (exit code $exitCode). Details: $log"
    }
    Write-Host "  Build finished."
}
if (-not (Test-Path $built)) { throw "Jar not found: $built" }

# A broken jar fails inside javaw before the app can show any message: check it really starts.
$java = Join-Path $jdk 'bin\java.exe'
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try { $versionOutput = & $java -jar $built --version 2>&1 | Out-String } finally { $ErrorActionPreference = $previous }
if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch 'pdf-generator') {
    throw "The built jar does not start (it may be incomplete). Run 'mvn package' and check its output.`n$versionOutput"
}

if ($BuildOnly) { Write-Host "Build checked: $built (nothing installed)"; exit 0 }

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

Write-Host ''
Write-Host 'Step 3 of 3: installing and creating the shortcuts...'
New-Item -ItemType Directory -Force $appDir | Out-Null
try {
    Copy-Item $built $jar -Force
} catch {
    throw "Cannot replace $jar. Is PDF Generator still running? Quit it from the tray icon and try again."
}
Copy-Item (Join-Path $root 'src\main\resources\launcher\app.ico') $icon -Force

# javaw = Java without a console window, from the JDK found above (full path: works even if Java is not on PATH).
$javaw = Join-Path $jdk 'bin\javaw.exe'

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

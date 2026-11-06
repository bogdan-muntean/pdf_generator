# Finding (and if needed installing) a JDK 21+ for the installer (ADR-0030). Dot-sourced by create-shortcut.ps1;
# installer\JavaSetup.Tests.ps1 checks the version parsing.

$MinimumJavaVersion = 21

<#
  Major version from the output of "java -version": 'openjdk version "21.0.2"' -> 21, '"1.8.0_391"' -> 8.
  Returns 0 when the text has no version.
#>
function Get-JavaMajorVersion([string] $versionText) {
    if ($versionText -match 'version "(\d+)(?:\.(\d+))?[^"]*"') {
        $first = [int] $Matches[1]
        if ($first -eq 1 -and $Matches[2]) { return [int] $Matches[2] }   # old scheme: 1.8 = Java 8
        return $first
    }
    return 0
}

# Folders where JDK installers put Java (winget Microsoft.OpenJDK, Adoptium Temurin, Oracle, Zulu, Amazon...).
function Get-StandardJdkFolders {
    $bases = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, (Join-Path $env:LOCALAPPDATA 'Programs')) |
        Where-Object { $_ -and (Test-Path $_) }
    $vendors = 'Microsoft', 'Eclipse Adoptium', 'Java', 'Zulu', 'Amazon Corretto', 'BellSoft', 'OpenJDK', 'Eclipse Foundation'
    foreach ($base in $bases) {
        foreach ($vendor in $vendors) {
            $dir = Join-Path $base $vendor
            if (Test-Path $dir) { Get-ChildItem $dir -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName } }
        }
    }
}

<#
  A JDK home (has bin\java.exe, bin\javaw.exe and bin\javac.exe) of version 21+, or $null.
  Order: JAVA_HOME, java on PATH, standard install folders (newest version first).
#>
function Find-Jdk {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates.Add((Split-Path (Split-Path $onPath.Source))) }
    Get-StandardJdkFolders | ForEach-Object { $candidates.Add($_) }

    $found = foreach ($jdkHome in ($candidates | Select-Object -Unique)) {
        $java = Join-Path $jdkHome 'bin\java.exe'
        if (-not ((Test-Path $java) -and (Test-Path (Join-Path $jdkHome 'bin\javac.exe')) -and
                (Test-Path (Join-Path $jdkHome 'bin\javaw.exe')))) { continue }
        # "java -version" writes to stderr, which Windows PowerShell turns into errors under 'Stop'
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $text = (& $java -version 2>&1) | Out-String } finally { $ErrorActionPreference = $previous }
        $version = Get-JavaMajorVersion $text
        if ($version -ge $MinimumJavaVersion) { [pscustomobject] @{ Home = $jdkHome; Version = $version } }
    }
    # newest version; among equal versions the first candidate (JAVA_HOME, then PATH) wins
    $newest = ($found | Measure-Object -Property Version -Maximum).Maximum
    $best = $found | Where-Object { $_.Version -eq $newest } | Select-Object -First 1
    if ($best) { return $best.Home }
    return $null
}

<# Finds a JDK; if there is none, offers to install one with winget. Returns the JDK home or throws. #>
function Get-OrInstallJdk {
    Write-Host "Step 1 of 3: checking for Java $MinimumJavaVersion (JDK)..."
    $jdk = Find-Jdk
    if ($jdk) {
        Write-Host "  Java $MinimumJavaVersion or newer is already installed: $jdk"
        return $jdk
    }

    $download = 'https://adoptium.net/temurin/releases/?version=21&os=windows&package=jdk'
    Write-Host ''
    Write-Host "  Java $MinimumJavaVersion (JDK) is needed to build and run PDF Generator, and was not found."
    if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
        throw "Install Java 21 (JDK, .msi) from $download , then double-click the installer again."
    }
    Write-Host '  It can be installed now with Windows Package Manager (winget):'
    Write-Host '    - what: Microsoft Build of OpenJDK 21, a free Java from Microsoft (about 300 MB installed)'
    Write-Host '    - needs: internet; Windows may ask "Do you want to allow this app to make changes?": choose Yes'
    Write-Host '    - takes: usually 1-3 minutes; it stays on this computer and other programs can use it'
    Write-Host '    - remove later: Settings > Apps > "Microsoft Build of OpenJDK"'
    $answer = Read-Host '  Install Java 21 now? [y/N]'
    if ($answer -notmatch '^[yY]') {
        throw "Java is required. Install Java 21 (JDK) from $download , then run the installer again."
    }

    Write-Host ''
    Write-Host '  Installing Java 21... (downloading and installing, please wait; do not close this window)'
    & winget install --id Microsoft.OpenJDK.21 -e --accept-package-agreements --accept-source-agreements
    $wingetExit = $LASTEXITCODE
    Write-Host ''
    $jdk = Find-Jdk   # the new JDK is not on this window's PATH yet: found in the standard folders
    if (-not $jdk) {
        $reason = if ($wingetExit -ne 0) { " (winget exit code $wingetExit; a declined permission prompt or no internet are the usual causes)" } else { '' }
        throw "Java could not be installed automatically$reason. Install Java 21 (JDK) from $download , then run the installer again."
    }
    Write-Host "  Java 21 installed: $jdk"
    Write-Host '  Other open windows will not see it until they are reopened; this installer and the PDF Generator shortcut do not need that.'
    return $jdk
}

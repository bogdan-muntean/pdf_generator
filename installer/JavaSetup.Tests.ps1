# Checks the Java version parsing of JavaSetup.ps1 (ADR-0030). Run:
#   powershell -NoProfile -ExecutionPolicy Bypass -File installer\JavaSetup.Tests.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'JavaSetup.ps1')

$cases = @(
    @('openjdk version "21.0.2" 2024-01-16 LTS', 21),
    @('java version "17.0.9" 2023-10-17 LTS', 17),
    @('java version "1.8.0_391"', 8),
    @('openjdk version "25" 2025-09-16', 25),
    @('openjdk version "22-ea" 2024-03-19', 22),
    @('not java at all', 0)
)
$failed = 0
foreach ($case in $cases) {
    $actual = Get-JavaMajorVersion $case[0]
    if ($actual -ne $case[1]) {
        Write-Host "FAIL: '$($case[0])' -> $actual, expected $($case[1])"
        $failed++
    }
}
$jdk = Find-Jdk
Write-Host "JDK found on this computer: $(if ($jdk) { $jdk } else { 'none' })"
if ($failed) { Write-Host "$failed case(s) failed"; exit 1 }
Write-Host "All $($cases.Count) version cases passed"

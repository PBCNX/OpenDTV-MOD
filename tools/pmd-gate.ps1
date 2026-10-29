# PMD gate — OpenDTV
#
# The Java half of the quality gate. tools/quality.ps1 asks desloppify, and
# desloppify's Java plugin calls `pmd check`, which is PMD 7 syntax; against the
# PMD 6.55 in tools/pmd6 it fails to parse and the pmd_violation detector scores
# zero without saying so. This script runs PMD 6.55 directly, with the rules in
# tools/pmd-ruleset.xml, so the Java findings are visible either way.
#
# Usage:
#   pwsh tools/pmd-gate.ps1              run and compare with the recorded count
#   pwsh tools/pmd-gate.ps1 -List        also print every violation
#   pwsh tools/pmd-gate.ps1 -Baseline    write the count into docs/QUALITY.md
#
# Exit codes: 0 at or below the recorded count, 1 worse, 2 tooling missing.

param(
    [switch]$List,
    [switch]$Baseline
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$src = Join-Path $repo 'opendtv\app\src\main\java'
$ruleset = Join-Path $PSScriptRoot 'pmd-ruleset.xml'
$quality = Join-Path $repo 'docs\QUALITY.md'
$logs = Join-Path $repo 'docs\build-logs'
$toolsRoot = Join-Path (Split-Path -Parent $repo) 'tools'

$pmdBin = if ($env:PMD_BIN) { $env:PMD_BIN } else { Join-Path $toolsRoot 'pmd6\pmd-bin-6.55.0\bin' }
$pmd = Join-Path $pmdBin 'pmd.bat'
if (-not (Test-Path $pmd)) {
    Write-Host "pmd 6.55 not found - set PMD_BIN to its bin directory" -ForegroundColor Yellow
    exit 2
}

# The JDK in tools, and a clean CLASSPATH: pmd.bat builds its classpath from it,
# and a stray entry with a space in the path ("Program Files\TSDuck") breaks it.
$env:JAVA_HOME = Join-Path $toolsRoot 'jdk17'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $pmdBin + ';' + $env:PATH
$env:CLASSPATH = ''

Write-Host "pmd gate" -ForegroundColor Cyan
Write-Host "  repo    : $repo"
Write-Host "  ruleset : tools\pmd-ruleset.xml"

New-Item -ItemType Directory -Force -Path $logs | Out-Null
$stamp = Get-Date -Format 'yyyy-MM-dd-HHmm'
$log = Join-Path $logs "pmd-$stamp.txt"

# PMD writes its banner to stderr; with ErrorActionPreference=Stop that would
# abort the script, so tolerate native stderr for the duration of the call.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
Push-Location $src
try {
    $raw = & $pmd -d . -R $ruleset -f text 2>&1
} finally {
    Pop-Location
    $ErrorActionPreference = $prevEap
}
$raw | Set-Content $log -Encoding utf8

# Only the violation lines: file:line:<tab>Rule:<tab>message. PMD's banner and
# the incremental-analysis warning land on stderr and are not findings.
$hits = @($raw | Where-Object { $_ -match '\.java:\d+:' })
$count = $hits.Count

Write-Host ("  violations : {0}" -f $count)
Write-Host ("  log        : {0}" -f $log.Replace($repo + '\', ''))

$byRule = $hits | ForEach-Object { (($_ -split "`t")[1]) -replace ':.*', '' } |
    Group-Object | Sort-Object Count -Descending
$byRule | Select-Object -First 8 | ForEach-Object { Write-Host ("     {0,3}  {1}" -f $_.Count, $_.Name) }

if ($List) { $hits | ForEach-Object { Write-Host "     $_" } }

$recorded = $null
if (Test-Path $quality) {
    $line = Select-String -Path $quality -Pattern 'pmd=([\d]+)' | Select-Object -First 1
    if ($line) { $recorded = [int]$line.Matches[0].Groups[1].Value }
}

Write-Host ""
if ($Baseline) {
    Write-Host "recorded count: $count - update docs/QUALITY.md by hand" -ForegroundColor Yellow
    exit 0
}

if ($null -eq $recorded) {
    Write-Host "no pmd count recorded yet - add pmd=$count to docs/QUALITY.md" -ForegroundColor Yellow
    exit 0
}

Write-Host ("baseline  : pmd {0}" -f $recorded)
if ($count -gt $recorded) {
    Write-Host "WORSE than baseline" -ForegroundColor Red
    exit 1
}
Write-Host "at or better than baseline" -ForegroundColor Green
exit 0

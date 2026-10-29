# Quality gate — OpenDTV-MOD
#
# Three stages, cheapest first. Advisory for private builds, blocking for a public
# release (the caller decides which; this script only reports).
#
#   1  desloppify (PMD 6.55)   mechanical: dead code, empty catches, complexity, tests
#   2  android lint            unused resources / ids / layouts
#   3  public checks           no vendor references, no vendor binaries in the tree
#
# Usage:
#   pwsh tools/quality.ps1                 run stage 1 and 3
#   pwsh tools/quality.ps1 -Lint           add stage 2 (slow, needs gradle)
#   pwsh tools/quality.ps1 -Baseline       write docs/QUALITY.md from this run
#
# Tooling is NOT in the repo (vendor-free rule): a venv with desloppify + PMD 6.55 sit
# beside the checkout. Override with DESLOPPIFY_EXE and PMD_BIN if they live elsewhere.
#
# Exit codes: 0 ok, 1 worse than the recorded baseline, 2 tooling missing.

param(
    [switch]$Lint,
    [switch]$Baseline
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$src = Join-Path $repo 'opendtv\app\src\main\java'
$quality = Join-Path $repo 'docs\QUALITY.md'
$logs = Join-Path $repo 'docs\build-logs'
$toolsRoot = Join-Path (Split-Path -Parent $repo) 'tools'

$desloppify = if ($env:DESLOPPIFY_EXE) { $env:DESLOPPIFY_EXE }
              else { Join-Path $toolsRoot 'desloppify\venv\Scripts\desloppify.exe' }
if (-not (Test-Path $desloppify)) {
    $parent = Split-Path -Parent $repo
    foreach ($candidate in @(
            (Join-Path $parent 'releases\tools\desloppify-venv\Scripts\desloppify.exe'),
            (Join-Path $parent 'tools\desloppify\venv\Scripts\desloppify.exe'),
            (Join-Path $parent 'outgoing\tools\desloppify-venv\Scripts\desloppify.exe'),
            (Join-Path (Split-Path -Parent $parent) 'outgoing\tools\desloppify-venv\Scripts\desloppify.exe'))) {
        if (Test-Path $candidate) { $desloppify = $candidate; break }
    }
}
$pmdBin = if ($env:PMD_BIN) { $env:PMD_BIN }
          else { Join-Path $toolsRoot 'pmd6\pmd-bin-6.55.0\bin' }

Write-Host "quality gate" -ForegroundColor Cyan
Write-Host "  repo : $repo"
Write-Host "  src  : $src"

# ---------------------------------------------------------------- stage 3 first (cheap)
$vendorHits = @()
$vendorPatterns = 'SmsRio|smsrio|Sony9308|endeavour|sonytuner'
Get-ChildItem $src -Recurse -Filter *.java |
    Select-String -Pattern $vendorPatterns -List |
    ForEach-Object { $vendorHits += $_.Path.Replace($repo + '\', '') }

$binaryHits = Get-ChildItem (Join-Path $repo 'opendtv\app\src\main\jniLibs') -Recurse -File -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName.Replace($repo + '\', '') }

Write-Host ""
Write-Host "3 public checks" -ForegroundColor Cyan
Write-Host ("  vendor references : {0} file(s)" -f $vendorHits.Count)
$vendorHits | Select-Object -First 6 | ForEach-Object { Write-Host "     $_" }
Write-Host ("  vendor binaries   : {0} file(s)" -f $binaryHits.Count)

# ---------------------------------------------------------------- stage 1 desloppify
if (-not (Test-Path $desloppify)) {
    Write-Host ""
    Write-Host "desloppify not found - set DESLOPPIFY_EXE or install the venv" -ForegroundColor Yellow
    exit 2
}
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$stamp = Get-Date -Format 'yyyy-MM-dd-HHmm'
$log = Join-Path $logs "desloppify-$stamp.txt"

$env:PATH = "$pmdBin;$env:PATH"
Push-Location (Split-Path -Parent $repo)
# desloppify writes its progress to stderr; with ErrorActionPreference=Stop that would
# abort the script, so tolerate native stderr for the duration of the call.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $out = & $desloppify --lang java scan --path $src --profile ci --no-badge 2>&1
} finally {
    $ErrorActionPreference = $prevEap
    Pop-Location
}
$out | Set-Content $log -Encoding utf8

$objective = ($out | Select-String -Pattern 'objective\s+([\d.]+)/100' | Select-Object -First 1)
$issues = ($out | Select-String -Pattern 'Total:\s+(\d+)\s+issues' | Select-Object -First 1)
$objectiveScore = if ($objective) { [double]$objective.Matches[0].Groups[1].Value } else { -1 }
$issueCount = if ($issues) { [int]$issues.Matches[0].Groups[1].Value } else { -1 }

Write-Host ""
Write-Host "1 desloppify (PMD 6.55)" -ForegroundColor Cyan
Write-Host ("  objective : {0}" -f $(if ($objectiveScore -ge 0) { "$objectiveScore/100" } else { 'not reported' }))
Write-Host ("  issues    : {0}" -f $(if ($issueCount -ge 0) { $issueCount } else { 'not reported' }))
Write-Host ("  log       : {0}" -f $log.Replace($repo + '\', ''))

# ---------------------------------------------------------------- stage 2 lint (optional)
if ($Lint) {
    $gradle = Join-Path $toolsRoot 'gradle-8.11.1\bin\gradle.bat'
    if (Test-Path $gradle) {
        $env:JAVA_HOME = Join-Path $toolsRoot 'jdk17'
        Write-Host ""
        Write-Host "2 android lint" -ForegroundColor Cyan
        Push-Location (Join-Path $repo 'opendtv')
        try { & $gradle :app:lintDebug --console=plain 2>&1 | Select-Object -Last 6 } finally { Pop-Location }
        Write-Host "  report: opendtv\app\build\reports\lint-results-debug.html"
    } else {
        Write-Host "2 android lint: gradle not found, skipped" -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------- baseline / compare
$recorded = $null
if (Test-Path $quality) {
    $line = Select-String -Path $quality -Pattern 'objective=([\d.]+)\s+issues=(\d+)' | Select-Object -First 1
    if ($line) { $recorded = @([double]$line.Matches[0].Groups[1].Value, [int]$line.Matches[0].Groups[2].Value) }
}

Write-Host ""
if ($Baseline) {
    $text = @"
# Quality baseline

Recorded $(Get-Date -Format 'yyyy-MM-dd HH:mm') on ``$((git -C $repo rev-parse --short HEAD))``.

``````text
objective=$objectiveScore issues=$issueCount
``````

Rules:

- The two numbers may only improve. A public release blocks on a worse score or a higher
  count; a private build only warns.
- Stage 1 is desloppify with PMD 6.55. For Java, `orphaned` and `single-use` are suppressed:
  they report our own classes as unimported, which is false.
- Stage 2 is Android lint (``-Lint``). Stage 3 is the vendor checks above; a public tree
  needs **zero** vendor references and **zero** files under ``jniLibs``.
- Tooling lives outside the repo (``../tools/desloppify``, ``../tools/pmd6``) because the
  vendor-free rule forbids shipping it.
"@
    $text | Set-Content $quality -Encoding utf8
    Write-Host "baseline written to docs/QUALITY.md" -ForegroundColor Green
    exit 0
}

if (-not $recorded) {
    Write-Host "no baseline recorded yet - run with -Baseline on a still trunk" -ForegroundColor Yellow
    exit 0
}

$worse = ($objectiveScore -lt $recorded[0]) -or ($issueCount -gt $recorded[1])
Write-Host ("baseline  : objective {0} / {1} issues" -f $recorded[0], $recorded[1])
if ($worse) {
    Write-Host "WORSE than baseline" -ForegroundColor Red
    exit 1
}
Write-Host "at or better than baseline" -ForegroundColor Green
exit 0

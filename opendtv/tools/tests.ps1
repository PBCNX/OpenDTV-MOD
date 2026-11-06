# Runs the app's JVM unit suite: no device, no emulator, nothing beyond the workspace toolchain.
# A release block must quote this line's output, so a green block says the suite ran. The workspace
# gate (tools/quality.ps1) does not call it yet; see docs/QUALITY.md.
$ErrorActionPreference = 'Stop'

$gradleRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)   # tools -> opendtv
$repo = Split-Path -Parent $gradleRoot                                                # -> the worktree root
$workspace = Split-Path -Parent $repo                                                 # -> OpenDTV
$jdk = Join-Path $workspace 'tools\jdk17'
if ((Test-Path $jdk) -and -not $env:JAVA_HOME) { $env:JAVA_HOME = $jdk }
$gradle = Join-Path $workspace 'tools\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path $gradle)) { $gradle = 'gradle' }

Push-Location $gradleRoot
try {
    & $gradle :app:testDebugUnitTest --console=plain
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
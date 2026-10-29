# make-public-tree.ps1 - build the public source tree from a tagged commit.
#
# The public repository carries source only: no APK, no vendor libraries, and - by the owner's
# decision - no tuner driver at all. The seam stays; the drivers do not.
#
# This is a script rather than a hand edit for one reason: the strip is not pure deletion. Three
# files reference a driver class outside the driver itself, so every generation would otherwise
# re-discover the same three edits. Those edits are listed and asserted below.
#
# Usage:
#   pwsh -File tools/make-public-tree.ps1                          # from v0.1.0-beta into releases\
#   pwsh -File tools/make-public-tree.ps1 -Ref b99202c -OutDir D:\tmp\pub -Build
#
# The output directory is NOT a git repository. Turning it into a branch is a separate, deliberate
# step (see docs/PUBLIC_TREE.md): it must be an orphan commit, because the private history contains
# the vendor files this script exists to remove.

param(
    [string]$Ref = 'v0.1.0-beta',
    [string]$OutDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'releases\public-0.1.0-beta'),
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

Write-Host "public tree from $Ref" -ForegroundColor Cyan
Write-Host "  repo : $repo"
Write-Host "  out  : $OutDir"

# ------------------------------------------------------------------ 1. export the tagged tree
if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$zip = Join-Path $env:TEMP "public-$([guid]::NewGuid().ToString('N')).zip"
& git -C $repo archive --format=zip --output=$zip $Ref
if ($LASTEXITCODE -ne 0) { throw "git archive failed for $Ref" }
Expand-Archive -Path $zip -DestinationPath $OutDir -Force
Remove-Item $zip -Force

# ------------------------------------------------------------------ 2. drop the drivers
$drop = @(
    'opendtv/app/src/main/java/com/adth',
    'opendtv/app/src/main/java/ph/pbcnx/smsrio',
    'opendtv/app/src/main/java/ph/opendtv/source/provider',
    'opendtv/app/src/main/java/ph/opendtv/source/UsbTunerSource.java',
    'opendtv/app/src/main/java/ph/opendtv/source/SmsRioTunerSource.java',
    'opendtv/app/src/main/java/ph/opendtv/source/Sony9308TunerSource.java',
    'opendtv/app/src/main/jniLibs',
    # the SmsRio driver's own licence + readme: it credits a driver this tree does not carry, and the
    # in-app licence screen already credits the project
    'third_party/smsrio',
    # internal documentation, and this is not tidiness: docs/ holds 30 session files that name the
    # private repositories, the bench phone's LAN address and adb ports, the TV's adb id and the
    # author's Windows paths. WORKFLOW.md names the private repositories too. None of it belongs in
    # a public repository, and it was published once by mistake before this line existed.
    'docs',
    'WORKFLOW.md'
)
foreach ($p in $drop) {
    $full = Join-Path $OutDir $p
    if (Test-Path $full) {
        Remove-Item -Recurse -Force $full
        Write-Host "  dropped  $p"
    } else {
        Write-Warning "  absent   $p (the strip list and the tag disagree - check before publishing)"
    }
}

# ------------------------------------------------------------------ 3. the edits the strip needs
function Edit-File([string]$rel, [scriptblock]$change) {
    $full = Join-Path $OutDir $rel
    if (-not (Test-Path $full)) { throw "missing $rel" }
    $text = [System.IO.File]::ReadAllText($full)
    $new = & $change $text
    if ($new -eq $text) { Write-Warning "  no change in $rel - the code moved; update the generator" }
    [System.IO.File]::WriteAllText($full, $new)
    Write-Host "  edited   $rel"
}

# 3a. TunerRegistry constructs It9175Provider directly, so the class must exist. Make every driver
#     reflective - that is the whole point of the public tree having none.
Edit-File 'opendtv/app/src/main/java/ph/opendtv/source/TunerRegistry.java' {
    param($t)
    $t -replace '(?m)^\s*PROVIDERS\.add\(new ph\.opendtv\.source\.provider\.It9175Provider\(\)\);\r?\n', ''
}

# 3b. AnalyzerActivity asks UsbTunerSource for two extras through instanceof: the last tuned
#     frequency, and a "USB drop" suffix. Neither exists here, so the suffix becomes empty (the
#     variable is used in the detail line, so it has to stay), and the frequency hint goes.
Edit-File 'opendtv/app/src/main/java/ph/opendtv/AnalyzerActivity.java' {
    param($t)
    $t = $t -replace '(?m)^import ph\.opendtv\.source\.UsbTunerSource;\r?\n', ''
    $t = $t -replace '(?m)^\s*if \(s instanceof UsbTunerSource\) currentTunedKhz = \(\(UsbTunerSource\) s\)\.initialFrequencyKhz;\r?\n', ''
    $t = $t -replace '(?ms)^\s*String dropped = source instanceof UsbTunerSource.*?: "";\r?\n', "            String dropped = `"`";`r`n"
    $t
}

# 3c. TunerHub only imports UsbTunerSource for a javadoc link - no construction - so the import goes
#     and the sentence stays readable.
Edit-File 'opendtv/app/src/main/java/ph/opendtv/tv/TunerHub.java' {
    param($t)
    $t = $t -replace '(?m)^import ph\.opendtv\.source\.UsbTunerSource;\r?\n', ''
    $t -replace '\{@link UsbTunerSource\}', 'tuner source'
}

# 3d. UsbProbeActivity is the IT9175 probe. It is reachable from the Developers screen, so rather
#     than unpick that door, replace the body with one that says what is true.
$probe = @'
package ph.opendtv;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** The USB probe, in a tree that carries no tuner driver: it can only report that. */
public class UsbProbeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setPadding(48, 96, 48, 48);
        t.setText("No tuner driver is built into this tree.\n\n"
                + "The seam is here (TunerSource, TunerProvider, TunerRegistry); the drivers are in "
                + "their own repositories. See DRIVERS.md.");
        setContentView(t);
    }
}
'@
[System.IO.File]::WriteAllText((Join-Path $OutDir 'opendtv/app/src/main/java/ph/opendtv/UsbProbeActivity.java'), $probe)
Write-Host '  replaced opendtv/app/src/main/java/ph/opendtv/UsbProbeActivity.java'

# ------------------------------------------------------------------ 4. the public README and DRIVERS
# These are generator INPUT, versioned in the private tree, not part of the tagged export: the tag
# points at the commit that was proved, and the docs are applied on top of it.
Copy-Item (Join-Path $repo 'docs\PUBLIC_README.md') (Join-Path $OutDir 'README.md') -Force
Copy-Item (Join-Path $repo 'docs\PUBLIC_DRIVERS.md') (Join-Path $OutDir 'DRIVERS.md') -Force
foreach ($leftover in @('docs\PUBLIC_README.md', 'docs\PUBLIC_DRIVERS.md')) {
    $full = Join-Path $OutDir $leftover
    if (Test-Path $full) { Remove-Item $full -Force }
}
Write-Host '  wrote    README.md, DRIVERS.md'

# ------------------------------------------------------------------ 5. leftovers that must not ship
foreach ($junk in @('opendtv/local.properties', 'local.properties', 'keys', '.desloppify')) {
    $full = Join-Path $OutDir $junk
    if (Test-Path $full) { Remove-Item -Recurse -Force $full; Write-Host "  removed  $junk" }
}

# ------------------------------------------------------------------ 6. optional build proof
if ($Build) {
    # The SDK path is machine-local and gitignored, so the proof needs it injected.
    $localProps = Join-Path $repo 'opendtv\local.properties'
    if (-not (Test-Path $localProps)) { throw "no $localProps to copy for the build proof" }
    Copy-Item $localProps (Join-Path $OutDir 'opendtv\local.properties')
    $env:JAVA_HOME = Join-Path (Split-Path -Parent $repo) 'tools\jdk17'
    $gradle = Join-Path (Split-Path -Parent $repo) 'tools\gradle-8.11.1\bin\gradle.bat'
    Push-Location (Join-Path $OutDir 'opendtv')
    # Gradle writes notes to stderr ("deprecated API", the AGP warning); with
    # ErrorActionPreference=Stop a native command's stderr aborts the script even when the build
    # passed. Tolerate stderr for the call and judge by the exit code, as tools/quality.ps1 does.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $log = & $gradle :app:assembleDebug --console=plain 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
        Pop-Location
    }
    $log | Select-String 'BUILD|error:|FAILED' | ForEach-Object { "  {0}" -f $_.Line }
    if ($code -ne 0) { throw 'the public tree does not build - do not publish it' }
    $apk = Join-Path $OutDir 'opendtv\app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { throw 'build reported success but produced no APK' }
    Write-Host ("  built    {0} ({1:N0} b) - vendor-free tree compiles" -f (Split-Path $apk -Leaf), (Get-Item $apk).Length)
    Remove-Item (Join-Path $OutDir 'opendtv\local.properties') -Force
    # the build output must not travel with the source
    Remove-Item (Join-Path $OutDir 'opendtv\app\build') -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $OutDir 'opendtv\build') -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $OutDir 'opendtv\.gradle') -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "public tree ready in $OutDir" -ForegroundColor Green

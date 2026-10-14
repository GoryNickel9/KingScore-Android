#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Serial='emulator-5576',
    [string]$Apk=(Join-Path $PSScriptRoot '../.work/releases/KingScore-0.5.0-rc3-11.apk'),
    [ValidateRange(3,15)][int]$Samples=7,
    [string]$Adb=(Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-[0-9]+$'){throw 'PC emulator required'}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$run=Join-Path $repo ('.work/startup-measurements/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force|Out-Null
$report=[ordered]@{status='FAIL';serial=$Serial;api=$null;releaseBuild=$true;remoteDatabaseContacted=$false;authenticated=$false;warmups=1;samples=@();failure=$null}
function Invoke-Adb([string]$Name,[string[]]$Arguments,[switch]$AllowFailure) {
    $text=(& $Adb -s $Serial @Arguments 2>&1|Out-String);$code=$LASTEXITCODE
    [IO.File]::WriteAllText((Join-Path $run "$Name.log"),$text)
    if($code -ne 0 -and -not $AllowFailure){throw "ADB step failed: $Name"};return $text
}
try {
    if((Invoke-Adb 'boot' @('shell','getprop','sys.boot_completed')).Trim() -ne '1'){throw 'Emulator not ready'}
    $report.api=[int](Invoke-Adb 'api' @('shell','getprop','ro.build.version.sdk')).Trim()
    if((Invoke-Adb 'existing' @('shell','pm','list','packages','app.kingscore.android')) -match 'package:app.kingscore.android'){throw 'Fresh emulator required; no existing app data is removed'}
    [void](Invoke-Adb 'install' @('install',$Apk))
    $package=Invoke-Adb 'package' @('shell','dumpsys','package','app.kingscore.android')
    if($package -match 'pkgFlags=\[[^\]]*DEBUGGABLE'){throw 'Startup measurements require a non-debuggable release'}
    if($package -notmatch 'versionCode=([0-9]+)'){throw 'Installed release version unavailable'}
    $report.versionCode=[int]$Matches[1]
    for($sample=0;$sample -le $Samples;$sample++) {
        [void](Invoke-Adb "stop-$sample" @('shell','am','force-stop','app.kingscore.android'))
        $launch=Invoke-Adb "launch-$sample" @('shell','am','start','-W','-n','app.kingscore.android/.MainActivity')
        if($launch -notmatch 'TotalTime: ([0-9]+)'){throw 'Cold-start timing not available'}
        $elapsed=[int]$Matches[1]
        $loginVisible=$false
        for($attempt=0;$attempt -lt 4;$attempt++){
            $deviceFile="/data/local/tmp/kingscore-startup-$sample-$attempt.xml"
            [void](Invoke-Adb "ui-dump-$sample-$attempt" @('shell','uiautomator','dump',$deviceFile) -AllowFailure)
            $ui=Invoke-Adb "ui-$sample-$attempt" @('shell','cat',$deviceFile) -AllowFailure
            if($ui -match 'text="Ultime partite"'){throw 'Fresh installation unexpectedly contains authenticated navigation'}
            if($ui -match 'text="Accedi"'){$loginVisible=$true;break}
        }
        if(-not $loginVisible){throw 'Cold login did not render with an empty session'}
        $memory=Invoke-Adb "memory-$sample" @('shell','dumpsys','meminfo','-s','app.kingscore.android')
        if($memory -notmatch 'TOTAL PSS:\s*([0-9]+)'){throw 'PSS summary unavailable'}
        $pss=[int]$Matches[1]
        if($sample -gt 0){$report.samples+=@{coldStartMs=$elapsed;pssKiB=$pss}}
    }
    $ordered=@($report.samples.coldStartMs|Sort-Object)
    if($ordered.Count -ne $Samples){throw 'Incomplete startup sample set'}
    $middle=[int][Math]::Floor($ordered.Count/2)
    $report.medianMs=if($ordered.Count%2){$ordered[$middle]}else{($ordered[$middle-1]+$ordered[$middle])/2.0}
    $report.p95Ms=$ordered[[int][Math]::Ceiling($ordered.Count*0.95)-1]
    $report.apkSha256=(Get-FileHash -LiteralPath $Apk).Hash.ToLowerInvariant()
    $report.status='PASS'
} catch {$report.failure=$_.Exception.Message}
finally {
    $report|ConvertTo-Json -Depth 6|Set-Content (Join-Path $run 'report.json') -Encoding utf8
    Write-Output "$($report.status): $run/report.json"
}
if($report.status -ne 'PASS'){exit 1}

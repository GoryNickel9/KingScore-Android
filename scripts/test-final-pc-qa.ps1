#requires -Version 7.0
[CmdletBinding()]
param([string]$Serial='emulator-5578',[string]$Adb=(Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'))
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-[0-9]+$'){throw 'PC emulator required'}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$run=Join-Path $repo ('.work/final-pc-qa/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force|Out-Null
$settings=@{}
$fontBefore=$null
$report=[ordered]@{status='FAIL';serial=$Serial;api=$null;tests=0;remoteDatabaseContacted=$false;syntheticRepository=$true;releaseBuild=$false;settingsRestored=$false;systemFontScaleRestored=$false;failure=$null}
function Invoke-Adb([string]$Name,[string[]]$Arguments) {
    $text=(& $Adb -s $Serial @Arguments 2>&1|Out-String);$code=$LASTEXITCODE
    [IO.File]::WriteAllText((Join-Path $run "$Name.log"),$text)
    if($code -ne 0){throw "ADB step failed: $Name"};return $text
}
try {
    if((Invoke-Adb 'boot' @('shell','getprop','sys.boot_completed')).Trim() -ne '1'){throw 'Emulator not ready'}
    $report.api=[int](Invoke-Adb 'api' @('shell','getprop','ro.build.version.sdk')).Trim()
    $fontBefore=(Invoke-Adb 'font-before' @('shell','settings','get','system','font_scale')).Trim()
    foreach($name in @('animator_duration_scale','window_animation_scale','transition_animation_scale')) {
        $settings[$name]=(Invoke-Adb "get-$name" @('shell','settings','get','global',$name)).Trim()
        [void](Invoke-Adb "set-$name" @('shell','settings','put','global',$name,'0'))
    }
    [void](Invoke-Adb 'install' @('install','-r',(Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk')))
    [void](Invoke-Adb 'install-test' @('install','-r',(Join-Path $repo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')))
    $suite=Invoke-Adb 'suite' @('shell','am','instrument','-w','-r','-e','class','app.kingscore.android.ui.FinalPcQaTest','app.kingscore.android.test/app.kingscore.android.data.local.PendingRecoveryProbe')
    if($suite -notmatch 'OK \(([0-9]+) tests\)'){throw 'Final PC QA suite failed; inspect suite.log'}
    $report.tests=[int]$Matches[1]
    $fontAfter=(Invoke-Adb 'font-after' @('shell','settings','get','system','font_scale')).Trim()
    if($fontBefore -ne $fontAfter){throw 'Tests did not restore system font scale'}
    $report.systemFontScaleRestored=$true
    $metrics=Invoke-Adb 'history-performance' @('shell','run-as','app.kingscore.android','cat','files/pc-qa-history-performance.json')
    $parsed=$metrics|ConvertFrom-Json
    if($parsed.fixtureRows -ne 1200 -or $parsed.historyRequests -ne 30){throw 'Performance fixture/request invariant failed'}
    [IO.File]::WriteAllText((Join-Path $run 'history-performance.json'),$metrics)
    foreach($name in @('large-font-profile','large-font-keyboard')) {
        $process=Start-Process -FilePath $Adb -ArgumentList @('-s',$Serial,'exec-out','run-as','app.kingscore.android','cat',"files/pc-qa-$name.png") -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $run "$name.png") -RedirectStandardError (Join-Path $run "$name-capture.log")
        if($process.ExitCode -ne 0){throw 'QA screenshot retrieval failed'}
    }
    $report.status='PASS'
} catch {$report.failure=$_.Exception.Message}
finally {
    try {
        if($null -ne $fontBefore){
            $arguments=if($fontBefore -eq 'null'){@('shell','settings','delete','system','font_scale')}else{@('shell','settings','put','system','font_scale',$fontBefore)}
            [void](Invoke-Adb 'restore-font-scale' $arguments)
        }
        foreach($name in $settings.Keys){
            $arguments=if($settings[$name] -eq 'null'){@('shell','settings','delete','global',$name)}else{@('shell','settings','put','global',$name,$settings[$name])}
            [void](Invoke-Adb "restore-$name" $arguments)
        }
        $report.settingsRestored=$true
    } catch {$report.status='FAIL';$report.failure='Emulator settings restore failed'}
    $report|ConvertTo-Json -Depth 4|Set-Content (Join-Path $run 'report.json') -Encoding utf8
    Write-Output "$($report.status): $run/report.json"
}
if($report.status -ne 'PASS'){exit 1}

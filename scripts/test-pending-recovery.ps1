#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5582',
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe')
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'This PC-only runner requires an emulator serial' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$run = Join-Path $repo ('.work/pending-recovery/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$report = [ordered]@{status='FAIL';serial=$Serial;remoteDatabaseContacted=$false;androidTests=0;writeProcess=$null;readProcess=$null;forceStopVerified=$false;commentRecoveryVerified=$false;settingsRestored=$false;failure=$null}
$settings = @{}
function Invoke-Adb([string]$Name, [string[]]$Arguments) {
    $output = (& $Adb -s $Serial @Arguments 2>&1 | Out-String)
    $exit = $LASTEXITCODE
    [IO.File]::WriteAllText((Join-Path $run "$Name.log"),$output)
    if ($exit -ne 0) { throw "ADB step failed: $Name" }
    return $output
}
try {
    $boot = Invoke-Adb 'boot' @('shell','getprop','sys.boot_completed')
    if ($boot.Trim() -ne '1') { throw 'Dedicated emulator is not booted' }
    foreach ($name in @('animator_duration_scale','window_animation_scale','transition_animation_scale')) {
        $settings[$name] = (Invoke-Adb "get-$name" @('shell','settings','get','global',$name)).Trim()
        [void](Invoke-Adb "set-$name" @('shell','settings','put','global',$name,'0'))
    }
    [void](Invoke-Adb 'install-app' @('install','-r',(Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk')))
    [void](Invoke-Adb 'install-test' @('install','-r',(Join-Path $repo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')))
    $component = 'app.kingscore.android.test/app.kingscore.android.data.local.PendingRecoveryProbe'
    $suite = Invoke-Adb 'suite' @('shell','am','instrument','-w','-r',$component)
    if ($suite -notmatch 'OK \(([0-9]+) tests\)') { throw 'Android suite failed; inspect suite.log' }
    $report.androidTests = [int]$Matches[1]
    $write = Invoke-Adb 'write' @('shell','am','instrument','-w','-e','mode','write',$component)
    if ($write -notmatch 'pendingProbe=PASS' -or $write -notmatch 'processId=([0-9]+)') { throw 'Write probe failed' }
    $report.writeProcess = [int]$Matches[1]
    [void](Invoke-Adb 'force-stop' @('shell','am','force-stop','app.kingscore.android'))
    $read = Invoke-Adb 'read' @('shell','am','instrument','-w','-e','mode','read',$component)
    if ($read -notmatch 'pendingProbe=PASS' -or $read -notmatch 'processId=([0-9]+)') { throw 'Read probe failed' }
    $report.readProcess = [int]$Matches[1]
    $report.forceStopVerified = $report.readProcess -ne $report.writeProcess
    $report.commentRecoveryVerified = $write -match 'commentProbe=PASS' -and $read -match 'commentProbe=PASS'
    if (-not $report.commentRecoveryVerified) { throw 'Comment process recovery failed' }
    if (-not $report.forceStopVerified) { throw 'Probe did not run in a fresh process' }
    $report.status = 'PASS'
} catch { $report.failure = $_.Exception.Message }
finally {
    try {
        foreach ($name in $settings.Keys) {
            $arguments = if ($settings[$name] -eq 'null') { @('shell','settings','delete','global',$name) }
                else { @('shell','settings','put','global',$name,$settings[$name]) }
            [void](Invoke-Adb "restore-$name" $arguments)
        }
        $report.settingsRestored = $true
    } catch { $report.status='FAIL';$report.failure='Emulator settings were not restored' }
    $report | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $run 'report.json') -Encoding utf8
    Write-Host "$($report.status): $run/report.json"
}
if ($report.status -ne 'PASS') { exit 1 }

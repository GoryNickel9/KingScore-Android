#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BaselineApk,
    [Parameter(Mandatory=$true)][string]$CandidateApk,
    [Parameter(Mandatory=$true)][string]$ProbeApk,
    [Parameter(Mandatory=$true)][string]$WrongSignerApk,
    [switch]$CompileEmulatorPackages,
    [string]$Serial='emulator-5582',
    [string]$Adb=(Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-[0-9]+$') {throw 'Private-release tests require a fresh PC emulator'}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$run=Join-Path $repo ('.work/private-apk/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force|Out-Null
$report=[ordered]@{status='FAIL';serial=$Serial;api=$null;remoteAccountAuthenticationTested=$false;remoteDatabaseContacted=$false;baselineInstalled=$false;wrongSignerRejected=$false;upgradeInstalled=$false;encryptedRecordsPreserved=$false;offlineBlocked=$false;coldLoginVisible=$false;liveDisconnectBlocked=$false;liveReconnectRestored=$false;debuggingDenied=$false;settingsRestored=$false;failure=$null}
$report.packagesPrecompiled=[bool]$CompileEmulatorPackages
$radio=@{}
function Invoke-Adb([string]$Name,[string[]]$Arguments,[switch]$AllowFailure) {
    $text=(& $Adb -s $Serial @Arguments 2>&1|Out-String);$code=$LASTEXITCODE
    [IO.File]::WriteAllText((Join-Path $run "$Name.log"),$text)
    if($code -ne 0 -and -not $AllowFailure) {throw "ADB step failed: $Name"}
    return $text
}
function PackageInfo([string]$Name) {
    $text=Invoke-Adb $Name @('shell','dumpsys','package','app.kingscore.android')
    if($text -notmatch '(?:userId|appId)=([0-9]+)') {throw 'Installed application UID not found'}
    $uid=[int]$Matches[1]
    if($text -notmatch 'versionCode=([0-9]+)') {throw 'Installed version not found'}
    $version=[int]$Matches[1]
    if($text -match 'pkgFlags=\[[^\]]*DEBUGGABLE') {throw 'Release app is debuggable'}
    return @{uid=$uid;version=$version}
}
function Compile-Package([string]$Name,[string]$Package) {
    if ($CompileEmulatorPackages) {
        # On a loaded PC emulator, cold instrumentation can exceed Android's process-start deadline.
        # This prepares ART code only; it does not change APK bytes or measure cold-start performance.
        $text=Invoke-Adb $Name @('shell','cmd','package','compile','-m','speed','-f',$Package)
        if($text -notmatch '(?m)^Success\s*$') {throw "Emulator package compilation failed: $Package"}
    }
}
function Probe([string]$Name,[string]$Mode) {
    $text=Invoke-Adb $Name @('shell','am','instrument','-w','-e','mode',$Mode,'app.kingscore.android.test/app.kingscore.android.data.local.PendingRecoveryProbe')
    if($text -notmatch 'pendingProbe=PASS' -or $text -notmatch 'commentProbe=PASS') {throw "Encrypted-store probe failed: $Name"}
}
function Wait-Ui([string]$Name,[string]$Expected) {
    for($attempt=0;$attempt -lt 12;$attempt++) {
        $deviceFile="/data/local/tmp/kingscore-release-$Name-$attempt.xml"
        [void](Invoke-Adb "$Name-dump-$attempt" @('shell','uiautomator','dump',$deviceFile) -AllowFailure)
        $text=Invoke-Adb "$Name-$attempt" @('shell','cat',$deviceFile) -AllowFailure
        if($text -match $Expected -and $text -notmatch 'text="Ultime partite"') {return $text}
        Start-Sleep -Milliseconds 500
    }
    throw "Expected release UI not shown: $Name"
}
function Capture-Ui([string]$Name) {
    $path=Join-Path $run "$Name.png"
    $process=Start-Process -FilePath $Adb -ArgumentList @('-s',$Serial,'exec-out','screencap','-p') -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $path -RedirectStandardError (Join-Path $run "$Name-capture.log")
    if($process.ExitCode -ne 0 -or (Get-Item -LiteralPath $path).Length -lt 100) {throw 'Release screenshot capture failed'}
}
function Wait-OfflineNetwork([string]$Name) {
    for($attempt=0;$attempt -lt 20;$attempt++) {
        $text=Invoke-Adb "$Name-network-$attempt" @('shell','dumpsys','connectivity')
        if($text -match 'Active default network: none') { return }
        Start-Sleep -Milliseconds 500
    }
    throw "Android still has a default network during the offline test: $Name"
}
try {
    if((Invoke-Adb 'boot' @('shell','getprop','sys.boot_completed')).Trim() -ne '1') {throw 'Emulator is not booted'}
    $report.api=[int](Invoke-Adb 'api' @('shell','getprop','ro.build.version.sdk')).Trim()
    $existing=Invoke-Adb 'existing' @('shell','pm','list','packages','app.kingscore.android')
    if($existing -match 'package:app.kingscore.android') {throw 'Use a fresh emulator; this runner never uninstalls existing app data'}
    $radio.wifi=(Invoke-Adb 'wifi-before' @('shell','settings','get','global','wifi_on')).Trim()
    $radio.data=(Invoke-Adb 'data-before' @('shell','settings','get','global','mobile_data')).Trim()
    [void](Invoke-Adb 'wifi-off' @('shell','svc','wifi','disable'))
    [void](Invoke-Adb 'data-off' @('shell','svc','data','disable'))
    [void](Invoke-Adb 'baseline-install' @('install',$BaselineApk))
    $baseline=PackageInfo 'baseline-package';$report.baselineInstalled=$true
    [void](Invoke-Adb 'probe-install' @('install',$ProbeApk))
    Compile-Package 'baseline-aot' 'app.kingscore.android'
    Compile-Package 'probe-aot' 'app.kingscore.android.test'
    Probe 'write-before-wrong-signer' 'write'
    $denied=Invoke-Adb 'wrong-signer' @('install','-r',$WrongSignerApk) -AllowFailure
    if($denied -notmatch 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {throw 'Wrong signing certificate was not rejected as expected'}
    $report.wrongSignerRejected=$true
    $unchanged=PackageInfo 'after-wrong-signer'
    if($unchanged.uid -ne $baseline.uid -or $unchanged.version -ne $baseline.version) {throw 'Rejected update changed the installed app'}
    Probe 'read-after-wrong-signer' 'read'
    Probe 'write-before-upgrade' 'write'
    [void](Invoke-Adb 'upgrade-install' @('install','-r',$CandidateApk))
    Compile-Package 'candidate-aot' 'app.kingscore.android'
    $candidate=PackageInfo 'candidate-package'
    if($candidate.uid -ne $baseline.uid -or $candidate.version -le $baseline.version) {throw 'Upgrade must preserve UID and increase versionCode'}
    $report.upgradeInstalled=$true;$report.baselineVersion=$baseline.version;$report.candidateVersion=$candidate.version;$report.applicationUid=$candidate.uid
    Probe 'read-after-upgrade' 'read';$report.encryptedRecordsPreserved=$true
    $debugging=Invoke-Adb 'run-as-denied' @('shell','run-as','app.kingscore.android','id') -AllowFailure
    if($debugging -notmatch 'not debuggable') {throw 'run-as should be denied for the release app'}
    $report.debuggingDenied=$true
    [void](Invoke-Adb 'offline-cold-stop' @('shell','am','force-stop','app.kingscore.android'))
    # First-boot provisioning can change radios while the APK/probe installs are running.
    [void](Invoke-Adb 'offline-wifi-off' @('shell','svc','wifi','disable'))
    [void](Invoke-Adb 'offline-data-off' @('shell','svc','data','disable'))
    Wait-OfflineNetwork 'offline'
    [void](Invoke-Adb 'launch-offline' @('shell','am','start','-W','-n','app.kingscore.android/.MainActivity'))
    [void](Wait-Ui 'offline' 'text="Connessione Internet non disponibile\."');$report.offlineBlocked=$true
    Capture-Ui 'offline'
    # No credentials are entered. With an empty session store, restoreVerifiedAccount returns before HTTP.
    [void](Invoke-Adb 'wifi-online' @('shell','svc','wifi','enable'))
    [void](Invoke-Adb 'data-online' @('shell','svc','data','enable'))
    [void](Invoke-Adb 'cold-stop' @('shell','am','force-stop','app.kingscore.android'))
    [void](Invoke-Adb 'launch-online' @('shell','am','start','-W','-n','app.kingscore.android/.MainActivity'))
    [void](Wait-Ui 'cold-login' 'text="Accedi"');$report.coldLoginVisible=$true
    Capture-Ui 'cold-login'
    [void](Invoke-Adb 'live-wifi-off' @('shell','svc','wifi','disable'))
    [void](Invoke-Adb 'live-data-off' @('shell','svc','data','disable'))
    Wait-OfflineNetwork 'live-disconnect'
    [void](Wait-Ui 'live-disconnect' 'text="Connessione Internet non disponibile\."');$report.liveDisconnectBlocked=$true
    Capture-Ui 'live-disconnect'
    [void](Invoke-Adb 'live-wifi-on' @('shell','svc','wifi','enable'))
    [void](Invoke-Adb 'live-data-on' @('shell','svc','data','enable'))
    [void](Wait-Ui 'live-reconnect' 'text="Accedi"');$report.liveReconnectRestored=$true
    $report.baselineSha256=(Get-FileHash -LiteralPath $BaselineApk).Hash.ToLowerInvariant()
    $report.candidateSha256=(Get-FileHash -LiteralPath $CandidateApk).Hash.ToLowerInvariant()
    $report.probeSha256=(Get-FileHash -LiteralPath $ProbeApk).Hash.ToLowerInvariant()
    $report.status='PASS'
} catch {$report.failure=$_.Exception.Message}
finally {
    try {
        if($radio.ContainsKey('wifi')) {[void](Invoke-Adb 'wifi-restore' @('shell','svc','wifi',$(if($radio.wifi -eq '1'){'enable'}else{'disable'})))}
        if($radio.ContainsKey('data')) {[void](Invoke-Adb 'data-restore' @('shell','svc','data',$(if($radio.data -eq '1'){'enable'}else{'disable'})))}
        $report.settingsRestored=$true
    } catch {$report.status='FAIL';$report.failure='Emulator radio settings were not restored'}
    $report|ConvertTo-Json -Depth 5|Set-Content (Join-Path $run 'report.json') -Encoding utf8
    Write-Output "$($report.status): $run/report.json"
}
if($report.status -ne 'PASS') {exit 1}

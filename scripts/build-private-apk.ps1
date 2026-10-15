#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateRange(1,2147483647)][int]$VersionCode=14,
    [ValidatePattern('^[0-9A-Za-z.+_-]{1,64}$')][string]$VersionName='0.5.0',
    [switch]$InitializeSigningKey,
    [switch]$Offline,
    [string]$AndroidSdk=(Join-Path $env:LOCALAPPDATA 'Android/Sdk')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$destination=Join-Path $repo ".work/releases/KingScore-$VersionName-$VersionCode.apk"
if (Test-Path -LiteralPath $destination) { throw 'Release artifact exists; use a new version instead of overwriting it' }
New-Item -ItemType Directory -Path (Split-Path $destination) -Force|Out-Null
$arguments=@(':app:testReleaseUnitTest',':app:lintRelease',':app:assembleRelease','--no-daemon','--console=plain','--max-workers=2',"-PkingScoreVersionCode=$VersionCode","-PkingScoreVersionName=$VersionName","-Dorg.gradle.daemon.registry.base=$repo/.work/release-daemon")
if ($Offline) { $arguments+='--offline' }
Push-Location $repo
try {
    & (Join-Path $repo 'gradlew.bat') @arguments *> "$destination.build.log"
    if ($LASTEXITCODE -ne 0) { throw "Release build failed; inspect $destination.build.log" }
    $unsigned=Join-Path $repo 'app/build/outputs/apk/release/app-release-unsigned.apk'
    $aapt=Join-Path $AndroidSdk 'build-tools/35.0.0/aapt.exe'
    $badging=(& $aapt dump badging $unsigned 2>&1|Out-String)
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='app.kingscore.android' versionCode='$VersionCode' versionName='$([Regex]::Escape($VersionName))'" -or $badging -match 'application-debuggable') { throw 'Release package/version/debuggability verification failed' }
    $manifest=(& $aapt dump xmltree $unsigned 'AndroidManifest.xml' 2>&1|Out-String)
    if ($LASTEXITCODE -ne 0 -or $manifest -match 'DebugRecreationActivity|PendingRecoveryProbe|ComposeTestActivity|PlayEditorRpcRecoveryTest|E: instrumentation') { throw 'Test entry point detected in release manifest' }
    if ($manifest -notmatch 'android:usesCleartextTraffic[^\r\n]*=\(type 0x12\)0x0(?:\s|$)' -or $manifest -match 'networkSecurityConfig') { throw 'Release network policy differs from the verified HTTPS-only manifest' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead($unsigned)
    try {
        if ($null -ne $archive.GetEntry('res/xml/local_test_network.xml')) { throw 'Debug loopback network policy detected in release resources' }
        foreach($entry in $archive.Entries|Where-Object FullName -Match '^classes[0-9]*\.dex$') {
            $stream=$entry.Open();$bytes=[IO.MemoryStream]::new()
            try { $stream.CopyTo($bytes);$text=[Text.Encoding]::Latin1.GetString($bytes.ToArray())
                if($text -match 'DebugRecreationActivity|PendingRecoveryProbe|ComposeTestActivity|PlayEditorRpcRecoveryTest') {throw 'Test-only class detected in release DEX'}
            } finally { $stream.Dispose();$bytes.Dispose() }
        }
    } finally { $archive.Dispose() }
    $signed=& (Join-Path $PSScriptRoot 'sign-private-apk.ps1') -InputApk $unsigned -OutputApk $destination -InitializeSigningKey:$InitializeSigningKey -AndroidSdk $AndroidSdk
    $tests=@(Get-ChildItem (Join-Path $repo 'app/build/test-results/testReleaseUnitTest/TEST-*.xml')|ForEach-Object {[xml]$xml=Get-Content $_.FullName;$xml.testsuite})
    [xml]$lint=Get-Content (Join-Path $repo 'app/build/reports/lint-results-release.xml')
    $applicationSource=Get-Content -LiteralPath (Join-Path $repo 'app/src/main/java/app/kingscore/android/KingScoreApplication.kt') -Raw
    if ($applicationSource -notmatch 'internal const val ATOMIC_PLAY_EDITOR_ENABLED = (true|false)') { throw 'Editor rollout flag could not be verified' }
    $adminEditorEnabled=$Matches[1] -eq 'true'
    $metadata=[ordered]@{status='PASS';candidate=$true;stable=$false;versionName=$VersionName;versionCode=$VersionCode;applicationId='app.kingscore.android';sourceCommit=(& git rev-parse HEAD).Trim();sourceDirty=[bool](& git status --porcelain);apk=$signed.apk;sha256=$signed.sha256;certificateSha256=$signed.certificateSha256;debuggable=$false;testHooksExcluded=$true;adminEditorEnabled=$adminEditorEnabled;remoteDatabaseContacted=$false;jvm=@{tests=[int]($tests|Measure-Object tests -Sum).Sum;failures=[int]($tests|Measure-Object failures -Sum).Sum;errors=[int]($tests|Measure-Object errors -Sum).Sum};lint=@{errors=@($lint.issues.issue|Where-Object severity -EQ Error).Count;warnings=@($lint.issues.issue|Where-Object severity -EQ Warning).Count}}
    $metadata|ConvertTo-Json -Depth 5|Set-Content "$destination.json" -Encoding utf8
    [IO.File]::WriteAllText("$destination.sha256","$($signed.sha256)  $([IO.Path]::GetFileName($destination))`n")
    [IO.File]::WriteAllText("$destination.manifest.log",$manifest)
    [IO.File]::WriteAllText("$destination.badging.log",$badging)
    Write-Output "PASS: $destination"
    Write-Output "SHA-256: $($signed.sha256)"
} finally { Pop-Location }

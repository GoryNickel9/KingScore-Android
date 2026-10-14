#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$InputApk,
    [Parameter(Mandatory=$true)][string]$OutputApk,
    [switch]$InitializeSigningKey,
    [string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android/Sdk')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function WorkspacePath([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'APK paths must stay in this repository' }
    return $full
}
$inputFile=WorkspacePath $InputApk
$outputFile=WorkspacePath $OutputApk
if (-not (Test-Path -LiteralPath $inputFile) -or $inputFile -eq $outputFile -or $outputFile -notmatch '\.apk$') { throw 'Separate input and output APK paths required' }
if (Test-Path -LiteralPath $outputFile) { throw 'Output APK already exists; preserve it and use a new version/path' }
if (-not $env:JAVA_HOME) { throw 'Configure JAVA_HOME first' }
$keytool=Join-Path $env:JAVA_HOME 'bin/keytool.exe'
$signer=Join-Path $AndroidSdk 'build-tools/35.0.0/apksigner.bat'
$align=Join-Path $AndroidSdk 'build-tools/35.0.0/zipalign.exe'
foreach($tool in @($keytool,$signer,$align)) { if (-not (Test-Path -LiteralPath $tool)) { throw 'JDK and Android build-tools 35.0.0 required' } }
$keyDir=Join-Path $repo '.work/signing'
$store=Join-Path $keyDir 'kingscore.p12'
$password=Join-Path $keyDir 'password.txt'
$certificate=Join-Path $keyDir 'certificate.der'
if ($InitializeSigningKey) {
    if (@($store,$password,$certificate|Where-Object {Test-Path -LiteralPath $_}).Count) { throw 'Signing material exists; never regenerate it implicitly' }
    New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $keyDir '/inheritance:r' '/grant:r' "${identity}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' *> (Join-Path $keyDir 'permissions.log')
    if ($LASTEXITCODE -ne 0) { throw 'Could not protect signing directory' }
    [IO.File]::WriteAllText($password,[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))+[Environment]::NewLine)
    & $keytool '-genkeypair' '-storetype' 'PKCS12' '-keystore' $store '-alias' 'kingscore' '-keyalg' 'RSA' '-keysize' '3072' '-sigalg' 'SHA256withRSA' '-validity' '9125' '-dname' 'CN=KingScore Android,O=KingScore,C=IT' '-storepass:file' $password '-keypass:file' $password *> (Join-Path $keyDir 'creation.log')
    if ($LASTEXITCODE -ne 0) { throw 'Signing-key creation failed; preserve files and inspect the local log' }
    & $keytool '-exportcert' '-keystore' $store '-alias' 'kingscore' '-storepass:file' $password '-file' $certificate *> (Join-Path $keyDir 'certificate.log')
    if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed' }
}
foreach($file in @($store,$password,$certificate)) {
    if (-not (Test-Path -LiteralPath $file)) { throw 'Signing material missing; initialize once with -InitializeSigningKey' }
    & git -C $repo check-ignore --quiet -- $file
    if ($LASTEXITCODE -ne 0) { throw 'Signing material must be ignored by Git' }
}
$expected=(Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash.ToLowerInvariant()
$outDir=Split-Path $outputFile
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$temporary="$outputFile.signing-$([Guid]::NewGuid().ToString('N')).apk"
# PKCS12 uses the same password for store and key; passing the file twice consumes two lines.
& $signer sign '--ks' $store '--ks-key-alias' 'kingscore' '--ks-pass' "file:$password" '--out' $temporary $inputFile *> "$outputFile.sign.log"
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed; no final APK published' }
$verification=(& $signer verify '--verbose' '--print-certs' $temporary 2>&1|Out-String)
[IO.File]::WriteAllText("$outputFile.verify.log",$verification)
if ($LASTEXITCODE -ne 0 -or $verification -notmatch 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})' -or $Matches[1].ToLowerInvariant() -ne $expected -or $verification -notmatch 'Number of signers: 1' -or $verification -notmatch 'Verified using v2 scheme.*: true') { throw 'APK signature/certificate verification failed; no final APK published' }
& $align '-P' '16' '-c' '4' $temporary *> "$outputFile.alignment.log"
if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed' }
Move-Item -LiteralPath $temporary -Destination $outputFile
$result=[ordered]@{apk=$outputFile;sha256=(Get-FileHash -LiteralPath $outputFile).Hash.ToLowerInvariant();certificateSha256=$expected;signatureVerified=$true;alignmentVerified=$true;alignment16KiBVerified=$true}
$result|ConvertTo-Json|Set-Content "$outputFile.signing.json" -Encoding utf8
[pscustomobject]$result

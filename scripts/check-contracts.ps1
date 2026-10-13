[CmdletBinding()]
param(
    [string]$KotlinCompiler,
    [string]$JavaExecutable = 'java'
)
$ErrorActionPreference = 'Stop'
if (-not $KotlinCompiler) {
    $command = Get-Command kotlinc -ErrorAction SilentlyContinue
    $KotlinCompiler = if ($command) { $command.Source } else {
        Join-Path $env:ProgramFiles 'Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc.bat'
    }
}
if (-not (Test-Path -LiteralPath $KotlinCompiler)) { throw 'Specificare -KotlinCompiler oppure installare Kotlin/Android Studio' }
$rootPath = Split-Path $PSScriptRoot -Parent
$workPath = Join-Path $rootPath '.work'
New-Item -ItemType Directory -Path $workPath -Force | Out-Null
$jarPath = Join-Path $workPath 'contracts-checks.jar'
& $KotlinCompiler (Join-Path $rootPath 'contracts/v1/Rows.kt') (Join-Path $rootPath 'contracts/v1/Commands.kt') (Join-Path $rootPath 'contracts/tests/ContractChecks.kt') -jvm-target 17 -include-runtime -d $jarPath
if ($LASTEXITCODE -ne 0) { throw 'Compilazione contratti fallita' }
& $JavaExecutable -jar $jarPath
if ($LASTEXITCODE -ne 0) { throw 'Verifica contratti fallita' }

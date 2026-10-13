[CmdletBinding()]
param(
    [string]$BackendPath = (Join-Path $PSScriptRoot '../../King-Score'),
    [string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android/Sdk')
)
$ErrorActionPreference = 'Stop'
$rootPath = Split-Path $PSScriptRoot -Parent
$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $BackendPath '.env.local')) {
    if ($line -match '^\s*(VITE_SUPABASE_URL|VITE_SUPABASE_ANON_KEY)\s*=\s*(.*?)\s*$') {
        $values[$Matches[1]] = $Matches[2].Trim('"', "'")
    }
}
$url = $values['VITE_SUPABASE_URL']
$key = $values['VITE_SUPABASE_ANON_KEY']
if (-not $url -or -not $key -or -not (Test-Path -LiteralPath $AndroidSdk)) { throw 'Configurazione locale incompleta' }
if ([Uri]::new($url).Scheme -ne 'https' -or $key.StartsWith('sb_secret_')) { throw 'URL o chiave pubblica non validi' }
[IO.File]::WriteAllText((Join-Path $rootPath 'local.properties'), "sdk.dir=$($AndroidSdk.Replace('\', '/').Replace(':', '\:'))`n")
[IO.File]::WriteAllText((Join-Path $rootPath 'supabase.properties'), "SUPABASE_URL=$url`nSUPABASE_PUBLIC_KEY=$key`n")
Write-Output 'Configurazione SDK e chiave pubblica salvata in file esclusi da Git; nessuna credenziale utente letta.'

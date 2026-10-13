[CmdletBinding()]
param([string]$BackendPath = (Join-Path $PSScriptRoot '../../King-Score'))
$ErrorActionPreference = 'Stop'
$envFile = Join-Path $BackendPath '.env.local'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Configurazione web non trovata' }
$values = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*(VITE_SUPABASE_URL|VITE_SUPABASE_ANON_KEY)\s*=\s*(.*?)\s*$') {
        $values[$Matches[1]] = $Matches[2].Trim('"', "'")
    }
}
$url = $values['VITE_SUPABASE_URL']
$key = $values['VITE_SUPABASE_ANON_KEY']
if (-not $url -or -not $key) { throw 'Configurazione Supabase pubblica incompleta' }
$uri = [Uri]$url
if ($uri.Scheme -ne 'https' -or $uri.Host -ne 'rixivmeipbizoxyvrvaf.supabase.co') { throw 'Progetto inatteso; nessuna richiesta inviata' }
$headers = @{ apikey = $key }
$results = @()
foreach ($table in @('games', 'plays', 'play_scores', 'players', 'play_comments', 'player_ratings', 'player_general_ratings', 'elo_history')) {
    # limit=0 deliberately retrieves no application rows.
    $response = Invoke-WebRequest -Uri "$url/rest/v1/${table}?select=id&limit=0" -Headers $headers -SkipHttpErrorCheck -TimeoutSec 20
    if ($response.StatusCode -ne 200 -or $response.Content.Trim() -ne '[]') { throw "Probe HTTP fallita: $table; status $($response.StatusCode)" }
    $results += @{ operation = "GET /rest/v1/${table}?select=id&limit=0"; status = [int]$response.StatusCode; rows = 0 }
}
$response = Invoke-WebRequest -Uri "$url/auth/v1/user" -Headers $headers -SkipHttpErrorCheck -TimeoutSec 20
if ($response.StatusCode -ne 401) { throw 'Endpoint Auth senza sessione: atteso 401' }
$results += @{ operation = 'GET /auth/v1/user without user session'; status = [int]$response.StatusCode }
@{ captured_at_utc = [DateTime]::UtcNow.ToString('o'); identity = 'Public app key only; no authenticated user token'; probes = $results } |
    ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $PSScriptRoot '../docs/evidence/http-probes.json') -Encoding utf8
Write-Output "$($results.Count) probe HTTP read-only completate; chiavi e righe applicative non esportate."

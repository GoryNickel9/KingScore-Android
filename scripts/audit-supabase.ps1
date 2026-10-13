[CmdletBinding()]
param(
    [string]$ProjectRef = 'rixivmeipbizoxyvrvaf',
    [string]$BackendPath = (Join-Path $PSScriptRoot '../../King-Score')
)
$ErrorActionPreference = 'Stop'
$sqlPath = Join-Path $PSScriptRoot 'audit-supabase.sql'
$outputPath = Join-Path $PSScriptRoot '../docs/evidence'
if ($ProjectRef -notmatch '^[a-z]{20}$') { throw 'ProjectRef non valido' }
if (-not (Test-Path -LiteralPath $BackendPath -PathType Container)) { throw 'Repository backend non trovato' }
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null

# Supabase CLI supplies its stored credentials. Never print or export them.
$queryOutput = & supabase db query --linked --project-ref $ProjectRef --workdir $BackendPath --file $sqlPath --output json
if ($LASTEXITCODE -ne 0) { throw 'Audit remoto fallito; evidenza precedente conservata' }
$result = ($queryOutput -join "`n") | ConvertFrom-Json
if ($result.rows.Count -ne 1 -or $result.rows[0].audit.read_only -ne 'on') {
    throw 'Risposta audit inattesa o transazione non read-only'
}
$record = [ordered]@{
    captured_at_utc = [DateTime]::UtcNow.ToString('o')
    project_ref = $ProjectRef
    source = 'Supabase CLI Management API; catalog metadata only; BEGIN READ ONLY / ROLLBACK'
    audit = $result.rows[0].audit
}
$record | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $outputPath 'supabase-audit.json') -Encoding utf8
Write-Output 'Audit salvato; nessun dato applicativo o credenziale esportato.'

[CmdletBinding()]
param(
    [string]$ProjectRef = 'rixivmeipbizoxyvrvaf',
    [string]$BackendPath = (Join-Path $PSScriptRoot '../../King-Score')
)
$ErrorActionPreference = 'Stop'
if ($ProjectRef -notmatch '^[a-z]{20}$') { throw 'ProjectRef non valido' }
$outputPath = Join-Path $PSScriptRoot '../docs/evidence'
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$results = @()
$cases = @(
    @{ name = 'anonymous_delete_plan'; role = 'anon'; appRole = 'user'; probe = "EXPLAIN (FORMAT JSON, COSTS FALSE) DELETE FROM public.plays WHERE id = '00000000-0000-0000-0000-000000000000';"; expected = 'plan' },
    @{ name = 'user_delete_plan'; role = 'authenticated'; appRole = 'user'; probe = "EXPLAIN (FORMAT JSON, COSTS FALSE) DELETE FROM public.plays WHERE id = '00000000-0000-0000-0000-000000000000';"; expected = 'plan' },
    @{ name = 'admin_delete_plan'; role = 'authenticated'; appRole = 'admin'; probe = "EXPLAIN (FORMAT JSON, COSTS FALSE) DELETE FROM public.plays WHERE id = '00000000-0000-0000-0000-000000000000';"; expected = 'plan' },
    @{ name = 'admin_comment_delete_plan'; role = 'authenticated'; appRole = 'admin'; probe = "EXPLAIN (FORMAT JSON, COSTS FALSE) DELETE FROM public.play_comments WHERE id = '00000000-0000-0000-0000-000000000000';"; expected = 'plan' },
    @{ name = 'anonymous_scores_rpc_denied'; role = 'anon'; appRole = 'user'; probe = "SELECT public.replace_play_scores('00000000-0000-0000-0000-000000000000', '[]'::jsonb);"; expected = '42501' },
    @{ name = 'user_scores_rpc_denied'; role = 'authenticated'; appRole = 'user'; probe = "SELECT public.replace_play_scores('00000000-0000-0000-0000-000000000000', '[]'::jsonb);"; expected = '42501' }
)
foreach ($case in $cases) {
    # Synthetic identity only. EXPLAIN has no ANALYZE. RPC probes reject before DML.
    # READ ONLY is a second guard against all attempted database mutations.
    $sql = @"
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL ROLE $($case.role);
SET LOCAL request.jwt.claims = '{"sub":"00000000-0000-0000-0000-000000000001","role":"$($case.role)","app_metadata":{"role":"$($case.appRole)"}}';
$($case.probe)
ROLLBACK;
"@
    $tempSql = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($tempSql, $sql)
        $probeOutput = & supabase db query --linked --project-ref $ProjectRef --workdir $BackendPath --file $tempSql --output json 2>&1
        $exitCode = $LASTEXITCODE
        $raw = ($probeOutput | ForEach-Object { "$_" }) -join "`n"
        if ($case.expected -eq 'plan') {
            if ($exitCode -ne 0) { throw "Probe $($case.name) fallita: $raw" }
            $start = $raw.IndexOf('{')
            $end = $raw.LastIndexOf('}')
            $parsed = $raw.Substring($start, $end - $start + 1) | ConvertFrom-Json
            $results += @{ name = $case.name; passed = $true; mode = 'EXPLAIN without ANALYZE'; rows = $parsed.rows }
        } else {
            if ($exitCode -eq 0 -or $raw -notmatch '42501') { throw "Diniego atteso non verificato: $($case.name): $raw" }
            $results += @{ name = $case.name; passed = $true; mode = 'READ ONLY; internal denial before DML'; sqlstate = '42501' }
        }
    } finally {
        Remove-Item -LiteralPath $tempSql -ErrorAction SilentlyContinue
    }
}
@{
    captured_at_utc = [DateTime]::UtcNow.ToString('o')
    project_ref = $ProjectRef
    identity = 'Synthetic SQL claims; not a real Auth login or HTTP end-to-end test'
    probes = $results
} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $outputPath 'permission-probes.json') -Encoding utf8
Write-Output "$($results.Count) probe read-only completate; nessuna scrittura eseguita."

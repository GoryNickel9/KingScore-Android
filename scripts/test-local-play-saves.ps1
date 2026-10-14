#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$PostgrestBin = (Join-Path $PSScriptRoot '../.work/undone-external-files/postgrest-14.1/postgrest.exe'),
    [switch]$Atomic
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workRoot = Join-Path $repo '.work/local-play-tests'
$runDirectory = Join-Path $workRoot ([Guid]::NewGuid().ToString('N'))
$cluster = Join-Path $runDirectory 'cluster'
$PostgrestBin = [IO.Path]::GetFullPath($PostgrestBin)
if (-not $PostgrestBin.StartsWith($repo + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'PostgREST must be a verified file inside this repository'
}
$expectedGatewayHash = '13e575ad59a05df3f1554164883f5a756c8231e4e4dd495ee48053fb3acfad5c'
if ((Get-FileHash -LiteralPath $PostgrestBin -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedGatewayHash) {
    throw 'PostgREST binary differs from the verified 14.1 Windows binary; no server started'
}
$node = (Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$binaries = @{}
foreach ($name in @('initdb','pg_ctl','psql')) {
    $binaryPath = [IO.Path]::GetFullPath((Join-Path $PostgresBin "$name.exe"))
    if (-not (Test-Path -LiteralPath $binaryPath -PathType Leaf)) { throw "Missing PostgreSQL binary: $name" }
    $binaries[$name] = $binaryPath
}
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$report = [ordered]@{
    status='FAIL'; mode='local-baseline-and-contract-preparation'; host='127.0.0.1'; database='kingscore_local_tests'
    runDirectory=$runDirectory; startedAtUtc=[DateTime]::UtcNow.ToString('o'); finishedAtUtc=$null
    postgresPort=$null; exitCodes=[ordered]@{}; serverStopped=$false; remoteContacted=$false
    futureContractImplemented=$false; githubCiExecuted=$false; failure=$null
}
function Invoke-LocalProcess([string]$Name, [string]$Binary, [string[]]$Arguments, [int]$TimeoutSeconds = 60) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Binary
    $start.WorkingDirectory = $repo
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    foreach ($key in @($start.Environment.Keys)) {
        if ($key -match '^(PG|PGRST|POSTGRES|NODE_OPTIONS|HTTPS?_PROXY|ALL_PROXY)') { [void]$start.Environment.Remove($key) }
    }
    $start.Environment['PGPASSFILE'] = Join-Path $runDirectory 'empty.pgpass'
    $process = [Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $timedOut = -not $process.WaitForExit($TimeoutSeconds * 1000)
    if ($timedOut) {
        $process.Kill($true)
        [void]$process.WaitForExit(5000)
    }
    # pg_ctl's background server can retain pipe handles on Windows after pg_ctl exits.
    foreach ($stream in @(@{task=$stdout;reader=$process.StandardOutput;suffix='stdout'},@{task=$stderr;reader=$process.StandardError;suffix='stderr'})) {
        if ($stream.task.Wait(1000)) { $captured = $stream.task.GetAwaiter().GetResult() }
        elseif ($Name -eq 'start') {
            $stream.reader.Close()
            $captured = 'Background server retained the startup pipe; inspect postgres.log.'
        } else { throw "Output pipe did not close: $Name" }
        [IO.File]::WriteAllText((Join-Path $runDirectory "$Name.$($stream.suffix).log"),$captured)
    }
    $report.exitCodes[$Name] = $process.ExitCode
    if ($timedOut) { throw "Own test process timed out: $Name; inspect its logs in $runDirectory" }
    if ($process.ExitCode -ne 0) { throw "Local step failed: $Name; inspect logs in $runDirectory" }
    Write-Host "[$Name] PASS"
    $process.Dispose()
}
$launchAttempted = $false
try {
    [IO.File]::WriteAllText((Join-Path $runDirectory 'empty.pgpass'),'')
    Invoke-LocalProcess 'contract-tests' $node @('--test', (Join-Path $repo 'contracts/tests/play-save-contracts.test.mjs'))
    $fixture = Join-Path $runDirectory 'baseline.sql'
    Invoke-LocalProcess 'generate-fixture' $node @((Join-Path $repo 'local-tests/generate-baseline.mjs'),$fixture)
    Invoke-LocalProcess 'initdb' $binaries.initdb @('-D',$cluster,'-U','postgres','--auth=trust','--encoding=UTF8','--locale=C') 120
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $report.postgresPort = $port
    $launchAttempted = $true
    Invoke-LocalProcess 'start' $binaries.pg_ctl @('-D',$cluster,'-l',(Join-Path $runDirectory 'postgres.log'),'-o',"-h 127.0.0.1 -p $port",'-w','-t','30','start')
    $connection = @('-X','-w','-h','127.0.0.1','-p',[string]$port,'-U','postgres','-v','ON_ERROR_STOP=1')
    Invoke-LocalProcess 'create-database' $binaries.psql ($connection + @('-d','postgres','-c','CREATE DATABASE kingscore_local_tests'))
    Invoke-LocalProcess 'mark-database' $binaries.psql ($connection + @('-d','postgres','-c',"ALTER DATABASE kingscore_local_tests SET kingscore.local_test = 'isolated-fixture-v1'"))
    Invoke-LocalProcess 'fixture' $binaries.psql ($connection + @('-d','kingscore_local_tests','-f',$fixture))
    Invoke-LocalProcess 'http-tests' $node @((Join-Path $repo 'local-tests/baseline-http.mjs'),[IO.Path]::GetFullPath($PostgresBin),[string]$port,$runDirectory,$PostgrestBin)
    $http = Get-Content -LiteralPath (Join-Path $runDirectory 'http-report.json') -Raw | ConvertFrom-Json
    if ($http.status -ne 'PASS' -or -not $http.ownGatewayStopped) { throw 'HTTP gateway did not complete and stop' }
    if ($Atomic) {
        Invoke-LocalProcess 'candidate' $binaries.psql ($connection + @('-d','kingscore_local_tests','-f',(Join-Path $repo 'local-tests/candidate.sql')))
        $report.futureContractImplemented = $true
        Invoke-LocalProcess 'atomic-acceptance' $node @((Join-Path $repo 'local-tests/atomic-acceptance.mjs'),[IO.Path]::GetFullPath($PostgresBin),[string]$port,$runDirectory,$PostgrestBin) 180
        $acceptance = Get-Content -LiteralPath (Join-Path $runDirectory 'acceptance-report.json') -Raw | ConvertFrom-Json
        if ($acceptance.status -ne 'PASS' -or $acceptance.cases.Count -ne 21 -or -not $acceptance.ownGatewayStopped) {
            throw 'Atomic acceptance incomplete or own gateway did not stop'
        }
        $report.mode = 'local-atomic-acceptance'
    }
    $report.status = 'PASS'
} catch {
    $report.failure = $_.Exception.Message
    Write-Host "FAIL: $($report.failure)"
} finally {
    if ($launchAttempted) {
        try {
            if (Test-Path -LiteralPath (Join-Path $cluster 'postmaster.pid')) {
                Invoke-LocalProcess 'stop' $binaries.pg_ctl @('-D',$cluster,'-m','fast','-w','-t','30','stop')
            }
            $report.serverStopped = -not (Test-Path -LiteralPath (Join-Path $cluster 'postmaster.pid'))
            if (-not $report.serverStopped) { throw 'Own database server did not stop' }
        } catch { $report.status='FAIL'; $report.failure='Own local database server did not stop; inspect its logs' }
    }
    $report.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'report.json') -Encoding utf8
    Write-Host "$($report.status): report $runDirectory/report.json"
}
if ($report.status -ne 'PASS') { exit 1 }

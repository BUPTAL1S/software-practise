#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$PackageDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
$client = Join-Path $package 'JavaSIP-Client-Windows-x64'
$backend = Join-Path $package 'JavaSIP-Backend-Windows-x64'
$evidence = Join-Path $package ('validation-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $evidence | Out-Null
$previousPath = $env:PATH; $previousJava = $env:JAVA_HOME
$process = $null; $identity = $null
try {
    # Prove neither the native loader nor the backend relies on the developer JDK/MSYS2 PATH.
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"; $env:JAVA_HOME = $null
    & (Join-Path $client 'jre\bin\java.exe') ('-Dsip.native.dir=' + (Join-Path $client 'native')) '-cp' (Join-Path $client 'lib\sip-client.jar') 'lab.sip.client.NativeLoader'
    if ($LASTEXITCODE -ne 0) { throw 'Portable client JNI preflight failed.' }
    $stdout = Join-Path $evidence 'backend.stdout.log'; $stderr = Join-Path $evidence 'backend.stderr.log'
    $arguments = @('-jar',(Join-Path $backend 'lib\sipim-admin.jar'),'--server.address=127.0.0.1','--server.port=0',
        '--freeswitch.esl.enabled=false',('--spring.datasource.url=jdbc:sqlite:'+(Join-Path $evidence 'test.db')),
        ('--sipim.avatar.upload-dir='+(Join-Path $evidence 'uploads')))
    $quoted = $arguments | ForEach-Object { '"' + $_ + '"' }
    $process = Start-Process -FilePath (Join-Path $backend 'jre\bin\java.exe') -ArgumentList $quoted -WorkingDirectory $evidence -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $identity = $process.StartTime
    $deadline = [DateTime]::UtcNow.AddSeconds(45); $port = 0; $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw "Portable backend exited: $(Get-Content -LiteralPath $stderr -Raw)" }
        $log = Get-Content -LiteralPath $stdout -Raw -ErrorAction SilentlyContinue
        if ($log -match 'Tomcat started on port (\d+)') { $port = [int]$Matches[1] }
        if ($port) {
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2
                if ($health.status -eq 'ok' -and -not $health.esl.enabled) { $healthy = $true; break }
            } catch { }
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $healthy) { throw "Portable backend did not become healthy; inspect $stdout" }
    # Startup runners seed the isolated database after the HTTP listener is ready.
    $login = $null
    for ($attempt=0; $attempt -lt 25 -and -not $login; $attempt++) {
        try { $login = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$port/api/login" -ContentType 'application/json' -Body '{"username":"1001","password":"1001"}' }
        catch { Start-Sleep -Milliseconds 200 }
    }
    if (-not $login -or -not $login.token) { throw 'Portable backend login failed.' }
    $headers = @{Authorization='Bearer '+$login.token}
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        $users = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/users" -Headers $headers
        $rooms = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/rooms" -Headers $headers
        if ($users.Count -eq 8 -and $rooms.Count -eq 2) { break }
        Start-Sleep -Milliseconds 200
    }
    $stats = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/stats" -Headers $headers
    if ($users.Count -ne 8 -or $rooms.Count -ne 2 -or $stats.totalCalls -ne 0) { throw "Portable API read-back failed: users=$($users.Count), rooms=$($rooms.Count), totalCalls=$($stats.totalCalls)" }
    [ordered]@{status='PASS';nativeLoader='PASS';backendHealth='PASS';login='PASS';users=$users.Count;rooms=$rooms.Count;
        totalCalls=$stats.totalCalls;eslEnabled=$health.esl.enabled;port=$port;pid=$process.Id;started=$identity.ToString('o');
        externalServiceUsed=$false;cameraOrMicrophoneOpened=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8
    Write-Output "PORTABLE_PACKAGE_PASS: $evidence"
} finally {
    if ($process -and -not $process.HasExited) {
        $current = Get-Process -Id $process.Id -ErrorAction SilentlyContinue
        if ($current -and $current.StartTime -eq $identity) { $process.Kill(); $process.WaitForExit(5000) | Out-Null }
    }
    $env:PATH=$previousPath; $env:JAVA_HOME=$previousJava
}

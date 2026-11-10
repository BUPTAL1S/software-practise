#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$ClientJar,
    [Parameter(Mandatory)][string]$BackendJar,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$TargetAddress = '127.0.0.1'
)
$ErrorActionPreference='Stop'
$Java=(Resolve-Path -LiteralPath $Java).Path
$ClientJar=(Resolve-Path -LiteralPath $ClientJar).Path
$BackendJar=(Resolve-Path -LiteralPath $BackendJar).Path
$output=$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw 'Choose a new output directory'}
New-Item -ItemType Directory -Path $output | Out-Null
$process=$null;$started=$null
$previousPath=$env:PATH;$previousJava=$env:JAVA_HOME
function Stop-TestBackend {
    if($script:process -and -not $script:process.HasExited){
        $current=Get-Process -Id $script:process.Id -ErrorAction SilentlyContinue
        if($current -and $current.StartTime -eq $script:started){$script:process.Kill();[void]$script:process.WaitForExit(10000)}
    }
}
function Start-TestBackend([string]$label){
    $stdout=Join-Path $output ($label+'.stdout.log');$stderr=Join-Path $output ($label+'.stderr.log')
    $arguments=@('-jar',$BackendJar,'--server.address=0.0.0.0','--server.port=0','--freeswitch.esl.enabled=false',
        '--sipconnect.discovery.enabled=true','--sipconnect.discovery.port=0',
        ('--spring.datasource.url=jdbc:sqlite:'+(Join-Path $output 'test.db')),
        ('--sipconnect.discovery.identity-file='+(Join-Path $output 'discovery-id')),
        ('--sipim.avatar.fs-dir='+(Join-Path $output 'avatars')),
        ('--sipim.avatar.upload-dir='+(Join-Path $output 'avatar-uploads')),
        ('--sipim.files.upload-dir='+(Join-Path $output 'uploads')))
    $quoted=$arguments|ForEach-Object{'"'+$_+'"'}
    $script:process=Start-Process -FilePath $Java -ArgumentList $quoted -WorkingDirectory $output -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $script:started=$script:process.StartTime
    $deadline=[DateTime]::UtcNow.AddSeconds(45)
    while([DateTime]::UtcNow -lt $deadline){
        if($script:process.HasExited){throw "Isolated backend exited; inspect $stderr"}
        $log=Get-Content -LiteralPath $stdout -Raw -ErrorAction SilentlyContinue
        if($log -match 'Tomcat started on port (\d+)'){$httpPort=[int]$Matches[1]}else{$httpPort=0}
        if($log -match 'LAN discovery listening on UDP (\d+)'){$udpPort=[int]$Matches[1]}else{$udpPort=0}
        if($httpPort -and $udpPort){try{
            $nonce=[Guid]::NewGuid().ToString('N')
            $descriptor=Invoke-RestMethod -Uri "http://127.0.0.1:$httpPort/api/discovery?nonce=$nonce" -TimeoutSec 2
            if($descriptor.service -eq 'sip-connect' -and $descriptor.nonce -eq $nonce){return @{http=$httpPort;udp=$udpPort;id=$descriptor.instanceId}}
        }catch{}}
        Start-Sleep -Milliseconds 150
    }
    throw 'Discovery backend did not become ready'
}
try{
    $env:JAVA_HOME=$null;$env:PATH="$env:SystemRoot\System32;$env:SystemRoot"
    $first=Start-TestBackend 'first'
    $one=& $Java -cp $ClientJar lab.sip.client.ServerDiscovery 'http://127.0.0.99:1' $first.id $first.udp $TargetAddress
    if($LASTEXITCODE -ne 0 -or $one -notlike "*http=http://${TargetAddress}:$($first.http)*"){throw 'Packaged client did not discover the first backend'}
    Stop-TestBackend
    $second=Start-TestBackend 'after-restart'
    if($second.id -ne $first.id){throw 'Server identity changed after restart'}
    $two=& $Java -cp $ClientJar lab.sip.client.ServerDiscovery "http://127.0.0.99:$($first.http)" $first.id $second.udp $TargetAddress
    if($LASTEXITCODE -ne 0 -or $two -notlike "*http=http://${TargetAddress}:$($second.http)*"){throw 'Packaged client did not recover from the stale address'}
    [ordered]@{status='PASS';serverIdPreserved=$true;staleAddressRecovered=$true;targetAddress=$TargetAddress;
        firstHttpPort=$first.http;secondHttpPort=$second.http;bundledJava=$Java;
        clientJarSha256=(Get-FileHash -LiteralPath $ClientJar -Algorithm SHA256).Hash;
        backendJarSha256=(Get-FileHash -LiteralPath $BackendJar -Algorithm SHA256).Hash;
        loginRequestsSent=$false;cameraOrMicrophoneOpened=$false;dockerChanged=$false} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
    Write-Output "LAN_DISCOVERY_PACKAGE_PASS: $output"
}finally{Stop-TestBackend;$env:PATH=$previousPath;$env:JAVA_HOME=$previousJava}

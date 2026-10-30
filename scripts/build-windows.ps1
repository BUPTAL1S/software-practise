#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn.cmd',
    [string]$OutputDirectory,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'This builder requires Windows x64 and a Windows x64 JDK 17.' }
$repo = Split-Path $PSScriptRoot -Parent
if (-not $JdkHome) { throw 'Pass -JdkHome or set JAVA_HOME to a Windows x64 JDK 17.' }
$release = Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw
if ($release -notmatch 'JAVA_VERSION="17\.' -or $release -notmatch 'OS_NAME="Windows"' -or
    $release -notmatch 'OS_ARCH="(x86_64|amd64)"') { throw 'A Windows x64 JDK 17 is required; cross-platform runtimes cannot be relabeled.' }
$native = Join-Path $repo 'native\windows-x64'
foreach ($name in @('pjsua2.dll','libopenh264-7.dll','SDL2.dll','libwinpthread-1.dll','libgcc_s_seh-1.dll','libstdc++-6.dll','callwindow.dll','winmirror.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $native $name) -PathType Leaf)) { throw "Missing native dependency: $name" }
}
$jni = [IO.File]::ReadAllBytes((Join-Path $native 'pjsua2.dll'))
$pe = [BitConverter]::ToInt32($jni, 0x3c)
if ([BitConverter]::ToUInt16($jni, $pe+4) -ne 0x8664) { throw 'pjsua2.dll must be an x64 PE binary.' }
if (-not [Text.Encoding]::ASCII.GetString($jni).Contains('Video latency policy: decode-floor=')) {
    throw 'This release requires the video latency patch. Rebuild with scripts/build-native-windows.ps1 before packaging.'
}
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo ('dist\windows-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw "Output already exists; choose a new directory: $output" }
$previousJava = $env:JAVA_HOME; $previousPath = $env:PATH
try {
    $env:JAVA_HOME = $JdkHome; $env:PATH = (Join-Path $JdkHome 'bin') + ';' + $previousPath
    $mavenArgs = @('-DskipTests','clean','package'); if ($Offline) { $mavenArgs = @('-o') + $mavenArgs }
    & $Maven '-f' (Join-Path $repo 'client\pom.xml') '-Ppackage-windows' @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Client packaging failed.' }
    & $Maven '-f' (Join-Path $repo 'admin\pom.xml') @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Backend packaging failed.' }
    $clientJar = Join-Path $repo 'client\target\sip-client-0.1.0-all.jar'
    $backendJar = Join-Path $repo 'admin\target\sipim-admin-0.1.0-SNAPSHOT.jar'
    $entries = & (Join-Path $JdkHome 'bin\jar.exe') 'tf' $clientJar
    if ($LASTEXITCODE -ne 0 -or $entries -notcontains 'glass.dll' -or
        $entries -notcontains 'com/sun/glass/ui/win/WinApplication.class') { throw 'The client JAR lacks JavaFX Windows classes or native graphics libraries.' }
    $client = Join-Path $output 'JavaSIP-Client-Windows-x64'
    $backend = Join-Path $output 'JavaSIP-Backend-Windows-x64'
    New-Item -ItemType Directory -Path (Join-Path $client 'lib'),(Join-Path $client 'native'),(Join-Path $backend 'lib') -Force | Out-Null
    # java.se includes naming, management, instrumentation, SQL and XML required by Spring/Hibernate.
    $modules = 'java.se,jdk.unsupported,jdk.unsupported.desktop,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.jfr,jdk.naming.dns'
    & (Join-Path $JdkHome 'bin\jlink.exe') '--add-modules' $modules '--strip-debug' '--no-man-pages' '--no-header-files' '--compress=2' '--output' (Join-Path $client 'jre')
    if ($LASTEXITCODE -ne 0) { throw 'Windows runtime creation failed.' }
    Copy-Item -LiteralPath (Join-Path $client 'jre') -Destination (Join-Path $backend 'jre') -Recurse
    Copy-Item -LiteralPath $clientJar -Destination (Join-Path $client 'lib\sip-client.jar')
    Copy-Item -LiteralPath $backendJar -Destination (Join-Path $backend 'lib\sipim-admin.jar')
    Get-ChildItem -LiteralPath $native -Filter '*.dll' | Copy-Item -Destination (Join-Path $client 'native')
    $clientLauncher = @'
@echo off
setlocal
cd /d "%~dp0"
set "PATH=%~dp0native;%PATH%"
if not defined SIP_MEDIA_PROFILE set "SIP_MEDIA_PROFILE=balanced"
if /I "%SIP_MEDIA_PROFILE%"=="legacy" if not defined SIP_VIDEO_LATENCY_MODE set "SIP_VIDEO_LATENCY_MODE=legacy"
"%~dp0jre\bin\java.exe" "-Dsip.native.dir=%~dp0native" "-Dsip.media.profile=%SIP_MEDIA_PROFILE%" -jar "%~dp0lib\sip-client.jar" %*
exit /b %ERRORLEVEL%
'@
    $backendLauncher = @'
@echo off
setlocal
cd /d "%~dp0"
if not exist data mkdir data
"%~dp0jre\bin\java.exe" -jar "%~dp0lib\sipim-admin.jar" "--spring.datasource.url=jdbc:sqlite:./data/sipim.db" "--sipim.avatar.upload-dir=./data/uploads" %*
exit /b %ERRORLEVEL%
'@
    [IO.File]::WriteAllText((Join-Path $client 'start-client.bat'), $clientLauncher.Replace("`r`n","`n").Replace("`n","`r`n") + "`r`n", [Text.ASCIIEncoding]::new())
    [IO.File]::WriteAllText((Join-Path $backend 'start-backend.bat'), $backendLauncher.Replace("`r`n","`n").Replace("`n","`r`n") + "`r`n", [Text.ASCIIEncoding]::new())
    @'
Windows x64 / Java 17 portable client. Run start-client.bat.
No installed Java, Maven or MSYS2 is required on the receiving computer.
Optional: set SIP_MEDIA_PROFILE=legacy (previous settings) or motion before launch.
Native video defaults to interactive buffering; set SIP_VIDEO_LATENCY_MODE=legacy for the old decoder policy.
SIP/HTTP server addresses are configured in the existing account settings.
This package contains no MCU or signaling server.
'@ | Set-Content -LiteralPath (Join-Path $client 'README.txt') -Encoding utf8
    @'
Windows x64 / Java 17 portable backend. Run start-backend.bat.
Default HTTP: 8080. Override: start-backend.bat --server.port=8088
For offline development: set ESL_ENABLED=false before launch.
For deployment on the MCU host: ESL_HOST=127.0.0.1, ESL_PORT=8021 and ESL_PASSWORD must match FreeSWITCH.
ESL may remain bound to loopback. Run this backend on the same host as the teammate's MCU.
Database and avatar uploads are under data/. Other existing upload paths remain relative to this package.
Keep the console open; Ctrl+C stops this instance. No other service is stopped.
This package does not start or modify Docker, Kamailio or FreeSWITCH.
'@ | Set-Content -LiteralPath (Join-Path $backend 'README.txt') -Encoding utf8
    $commit = & git -C $repo rev-parse HEAD
    $dirty = [bool](& git -C $repo status --porcelain)
    $manifest = [ordered]@{ platform='windows-x64'; java=([regex]::Match($release,'JAVA_VERSION="([^"]+)"').Groups[1].Value);
        commit=$commit; dirty=$dirty; createdAt=(Get-Date).ToString('o'); modules=$modules; files=@() }
    $manifest.files = @(Get-ChildItem -LiteralPath $client,$backend -Recurse -File | Where-Object {$_.Extension -in '.jar','.dll'} | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($output,$_.FullName); bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    })
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8
    foreach ($bundle in @($client,$backend)) { Compress-Archive -LiteralPath $bundle -DestinationPath ($bundle + '.zip') -CompressionLevel Optimal }
    Get-FileHash -LiteralPath ($client+'.zip'),($backend+'.zip') -Algorithm SHA256 | Format-Table -AutoSize
    Write-Output "PACKAGES_READY: $output"
} finally { $env:JAVA_HOME=$previousJava; $env:PATH=$previousPath }

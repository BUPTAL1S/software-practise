#requires -Version 7.0
[CmdletBinding()]
param([string]$JdkHome=$env:JAVA_HOME,[string]$Maven='mvn.cmd',
      [string]$MsysRoot='C:\msys64',[string]$NativeDirectory)
$ErrorActionPreference='Stop'
if(-not $IsWindows) {throw 'This regression test requires Windows.'}
$repo=Split-Path $PSScriptRoot -Parent
if(-not $NativeDirectory) {$NativeDirectory=Join-Path $repo 'native\windows-x64'}
$native=(Resolve-Path -LiteralPath $NativeDirectory).Path
if(-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'include\jni.h'))) {throw 'Pass a Windows x64 JDK 17.'}
$target=Join-Path $repo 'client\target'
New-Item -ItemType Directory -Path $target -Force | Out-Null
$probe=Join-Path $target 'sdl-idle-events-probe.dll'
$previousPath=$env:PATH; $previousJava=$env:JAVA_HOME
try {
    $env:JAVA_HOME=$JdkHome
    $env:PATH=(Join-Path $JdkHome 'bin')+';'+(Join-Path $MsysRoot 'mingw64\bin')+';'+$previousPath
    & (Join-Path $MsysRoot 'mingw64\bin\gcc.exe') -shared -O2 -Wall -Wextra '-Werror' `
        ('-I'+(Join-Path $JdkHome 'include')) ('-I'+(Join-Path $JdkHome 'include\win32')) `
        (Join-Path $PSScriptRoot 'media\sdl-idle-events-probe.c') -o $probe -luser32
    if($LASTEXITCODE -ne 0) {throw 'Test-only JNI heartbeat observer failed to build.'}
    & $Maven -o -f (Join-Path $repo 'client\pom.xml') '-Dtest=SdlIdleEventIntegrationTest' `
        '-Dsip.nativeIdleTest=true' ('-Dsip.native.dir='+$native) ('-Dsip.idleProbe='+$probe) test
    if($LASTEXITCODE -ne 0) {throw 'SDL idle event regression failed. See client/target/native-idle-events.log.'}
} finally {$env:PATH=$previousPath;$env:JAVA_HOME=$previousJava}

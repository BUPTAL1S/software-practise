#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$PjSource, [string]$JdkHome=$env:JAVA_HOME,
      [string]$MsysRoot='C:\msys64', [ValidateRange(1,16)][int]$Jobs=8)
$ErrorActionPreference='Stop'
if(-not $IsWindows) {throw 'Windows x64 is required.'}
$source=(Resolve-Path -LiteralPath $PjSource).Path
if($source -match '\s') {throw 'Use a separate PJPROJECT checkout with no spaces in its path; upstream make paths are not quoted throughout.'}
$repo=Split-Path $PSScriptRoot -Parent
$head=& git -C $source rev-parse HEAD
if($LASTEXITCODE -ne 0 -or $head -ne '5a457451fa2712ba18e12b01738e8ff3af2b26fd') {throw 'Use the pinned PJPROJECT 2.17 commit 5a457451fa2712ba18e12b01738e8ff3af2b26fd.'}
if(-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'include\jni.h'))) {throw 'A Windows JDK 17 with JNI headers is required.'}
$release=Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw
if($release -notmatch 'JAVA_VERSION="17\.' -or $release -notmatch 'OS_NAME="Windows"' -or $release -notmatch 'OS_ARCH="(x86_64|amd64)"') {throw 'Use a Windows x64 JDK 17.'}
foreach($name in @('video-hangup-2.17.patch','video-latency-2.17.patch')) {
    $patch=Join-Path $repo ('native\windows-x64\patches\'+$name)
    & git -C $source apply --check $patch 2>$null
    if($LASTEXITCODE -eq 0) {
        & git -C $source apply $patch
        if($LASTEXITCODE -ne 0) {throw "Applying $name failed."}
    } else {
        & git -C $source apply --reverse --check $patch 2>$null
        if($LASTEXITCODE -ne 0) {throw "$name conflicts with existing source changes; no reset or clean was performed."}
    }
}
$site=Join-Path $source 'pjlib\include\pj\config_site.h'
if(-not (Test-Path -LiteralPath $site)) {
    [IO.File]::WriteAllText($site,"#define PJMEDIA_HAS_VIDEO 1`n#define PJMEDIA_HAS_OPENH264_CODEC 1`n",[Text.UTF8Encoding]::new($false))
}
$configuration=Get-Content -LiteralPath $site -Raw
if($configuration -notmatch '#define\s+PJMEDIA_HAS_VIDEO\s+1' -or $configuration -notmatch '#define\s+PJMEDIA_HAS_OPENH264_CODEC\s+1') {throw 'Preserved config_site.h must enable video and OpenH264.'}
$userMake=Join-Path $source 'user.mak'
if(-not (Test-Path -LiteralPath $userMake)) {
    [IO.File]::WriteAllText($userMake,"export CFLAGS += -fPIC`nexport LDFLAGS += -lopenh264`n",[Text.UTF8Encoding]::new($false))
}
if((Get-Content -LiteralPath $userMake -Raw) -notmatch 'LDFLAGS.*-lopenh264') {throw 'Preserved user.mak must link OpenH264: export LDFLAGS += -lopenh264'}
$script=@'
#!/usr/bin/env bash
set -euo pipefail
export PATH=/mingw64/bin:/usr/bin:$PATH
cd "$PJ_LATENCY_SOURCE"
./configure --host=x86_64-w64-mingw32 --enable-video --with-sdl=/mingw64 --disable-ffmpeg --disable-libwebrtc
grep -q '^#define PJ_WIN64 1' pjlib/include/pj/compat/os_auto.h
make dep
make -j"$PJ_LATENCY_JOBS" lib
# Build JNI only; do not require sample Java programs or SDL test executables.
make -B -C pjsip-apps/src/swig/java output/pjsua2.dll
'@
$helper=Join-Path $source '.codex-native-windows-build.sh'
[IO.File]::WriteAllText($helper,$script.Replace("`r`n","`n")+"`n",[Text.UTF8Encoding]::new($false))
$previous=@{PATH=$env:PATH;JAVA_HOME=$env:JAVA_HOME;MSYSTEM=$env:MSYSTEM;PJ_LATENCY_SOURCE=$env:PJ_LATENCY_SOURCE;PJ_LATENCY_JOBS=$env:PJ_LATENCY_JOBS}
try {
    $env:PATH=(Join-Path $MsysRoot 'mingw64\bin')+';'+(Join-Path $MsysRoot 'usr\bin')+';'+$env:PATH
    $env:JAVA_HOME=$JdkHome.Replace('\','/');$env:MSYSTEM='MINGW64'
    $env:PJ_LATENCY_SOURCE=$source.Replace('\','/');$env:PJ_LATENCY_JOBS=[string]$Jobs
    & (Join-Path $MsysRoot 'usr\bin\bash.exe') $helper.Replace('\','/')
    if($LASTEXITCODE -ne 0) {throw 'Native build failed; no installed DLL was replaced.'}
    $dll=Join-Path $source 'pjsip-apps\src\swig\java\output\pjsua2.dll'
    $metadata=[ordered]@{sourceCommit=$head;host='x86_64-w64-mingw32';dll=$dll;sha256=(Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash.ToLowerInvariant();
        bytes=(Get-Item -LiteralPath $dll).Length;patches=@('video-hangup-2.17.patch','video-latency-2.17.patch');installed=$false}
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $source 'native-build-result.json') -Encoding utf8
    $metadata | ConvertTo-Json
} finally {foreach($name in $previous.Keys) {[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}

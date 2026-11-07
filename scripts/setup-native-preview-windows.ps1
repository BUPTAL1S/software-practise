#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Compiler = 'g++.exe',
    [string]$Archive
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows -or -not [Environment]::Is64BitProcess) { throw 'Run this with Windows x64 PowerShell 7.' }
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'include\jni.h'))) {
    throw 'Pass -JdkHome or set JAVA_HOME to your Windows x64 JDK 17.'
}
$repo = Split-Path $PSScriptRoot -Parent
$version = '0.83'
# Digest published by the official GitHub release asset, not a mirror.
$expected = '0126982f47157bb86a6dbb43c4f332f7f98beba9ad552c19b65f9db2e7d4f186'
if (-not $Archive) {
    $cache = Join-Path $PSScriptRoot 'cache'
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    $Archive = Join-Path $cache "LAVFilters-$version-x64.zip"
    if (-not (Test-Path -LiteralPath $Archive)) {
        Invoke-WebRequest "https://github.com/Nevcairiel/LAVFilters/releases/download/$version/LAVFilters-$version-x64.zip" -OutFile $Archive
    }
}
if ((Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'Official LAV archive SHA256 mismatch; nothing was installed.'
}
$compilerPath = (Get-Command $Compiler -ErrorAction Stop).Source
$machine = & $compilerPath -dumpmachine
if ($LASTEXITCODE -ne 0 -or $machine -notmatch '^x86_64-w64-mingw32') {
    throw 'Use the Windows x64 MinGW-w64 g++ compiler.'
}
$runtime = Join-Path $repo 'native\windows-x64\preview-runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
# Retain the upstream license, README and interface headers with the portable runtime.
Expand-Archive -LiteralPath $Archive -DestinationPath $runtime -Force
$previousPath = $env:PATH
try {
    $env:PATH = (Split-Path $compilerPath -Parent) + ';' + $previousPath
    & $compilerPath -shared -O2 -s -static -static-libgcc -static-libstdc++ -Wno-attributes `
        ('-I' + (Join-Path $JdkHome 'include')) ('-I' + (Join-Path $JdkHome 'include\win32')) `
        ('-I' + (Join-Path $runtime 'include')) (Join-Path $repo 'native\windows-x64\PreviewAudioWin.cpp') `
        -o (Join-Path $runtime 'previewaudio.dll') -lole32 -luuid
    if ($LASTEXITCODE -ne 0) { throw 'Native preview audio bridge compilation failed.' }
} finally { $env:PATH = $previousPath }
Write-Output "Native preview runtime ready: $runtime"
Write-Output 'Restart the client. No regsvr32, administrator rights, or Windows registry changes are required.'

[CmdletBinding()]
param([string]$MsysRoot = 'C:\msys64')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$compiler = Join-Path $MsysRoot 'mingw64\bin\g++.exe'
$include = Join-Path $MsysRoot 'mingw64\include'
$library = Join-Path $MsysRoot 'mingw64\lib\libopenh264.dll.a'
$output = Join-Path $repo 'client\target\media-benchmark'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$binary = Join-Path $output 'openh264-benchmark.exe'
$previousPath = $env:PATH
try {
    $env:PATH = (Join-Path $MsysRoot 'mingw64\bin') + ';' + $previousPath
    & $compiler '-O2' '-std=c++17' ('-I' + $include) (Join-Path $PSScriptRoot 'media\openh264-benchmark.cpp') $library '-o' $binary
    if ($LASTEXITCODE -ne 0) { throw 'OpenH264 benchmark compilation failed.' }
    $results = & $binary
    if ($LASTEXITCODE -ne 0) { throw 'OpenH264 benchmark failed.' }
    $results | Set-Content -LiteralPath (Join-Path $output 'results.csv') -Encoding utf8
    $results
    Get-FileHash -LiteralPath (Join-Path $MsysRoot 'mingw64\bin\libopenh264-7.dll') -Algorithm SHA256 | Select-Object Path,Hash
} finally { $env:PATH = $previousPath }

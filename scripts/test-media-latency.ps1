#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$PjSource, [string]$MsysRoot='C:\msys64')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'client\target\video-latency-probe'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$binary=Join-Path $output 'video-latency-probe.exe'
$previous=$env:PATH
try {
    $env:PATH=(Join-Path $MsysRoot 'mingw64\bin')+';'+(Join-Path $MsysRoot 'usr\bin')+';'+$previous
    & (Join-Path $MsysRoot 'usr\bin\make.exe') '-f' (Join-Path $PSScriptRoot 'media\video-latency-probe.mk') `
        ('PJ_DIR='+$PjSource.Replace('\','/')) ('PROBE_SOURCE='+(Join-Path $PSScriptRoot 'media\video-latency-probe.cpp').Replace('\','/')) `
        ('PROBE_OUTPUT='+$binary.Replace('\','/')) 'all'
    if ($LASTEXITCODE -ne 0) { throw 'Video latency probe compilation failed.' }
    $rows=[Collections.Generic.List[string]]::new()
    $rows.Add('mode,input_fps,render_every_input_frames,reordered,decoded_outputs,mean_rtp_lag_ms,max_rtp_lag_ms,nonmonotonic,reordered_frames,duplicate_outputs')
    foreach($mode in @('legacy','interactive')) {
      foreach($fps in @(15,25)) {
        foreach($renderEvery in @(1,5,25)) {
            foreach($reorder in @(0,1)) {
                $lines=& $binary $mode $renderEvery $reorder $fps
                if ($LASTEXITCODE -ne 0) { $lines; throw "Video latency probe failed: $mode / $renderEvery / $reorder" }
                $row=@($lines | Where-Object {$_ -match '^(legacy|interactive),'} )
                if($row.Count -ne 1) {throw 'Probe did not emit a unique CSV result.'}
                $rows.Add($row[0])
            }
        }
      }
    }
    $rows | Set-Content -LiteralPath (Join-Path $output 'results.csv') -Encoding utf8
    $rows
    $data=$rows | ConvertFrom-Csv
    foreach($current in @($data | Where-Object {$_.mode -eq 'interactive'})) {
        $legacy=$data | Where-Object {$_.mode -eq 'legacy' -and $_.input_fps -eq $current.input_fps -and $_.render_every_input_frames -eq $current.render_every_input_frames -and $_.reordered -eq $current.reordered}
        if([double]$current.max_rtp_lag_ms -gt (2000.0/[double]$current.input_fps+0.001) -or [double]$current.mean_rtp_lag_ms -ge [double]$legacy.mean_rtp_lag_ms) {
            throw 'Interactive buffering failed the synthetic lag bound/comparison.'
        }
    }
    Write-Output 'PASS: synthetic RTP buffering is bounded with a slow renderer and intra-frame reordering.'
} finally {$env:PATH=$previous}

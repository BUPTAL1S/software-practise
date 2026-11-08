#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PackageDirectory,
    [switch]$Gui
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'Windows is required to verify this EXE package.' }
$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
function PackagePath([string]$relative) {
    $full = [IO.Path]::GetFullPath((Join-Path $package $relative))
    if (-not $full.StartsWith($package + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Manifest path escapes the package directory.'
    }
    return $full
}
$manifest = Get-Content -LiteralPath (Join-Path $package 'manifest.json') -Raw | ConvertFrom-Json
if ($manifest.format -ne 'sipconnect-windows-exe-v1' -or $manifest.platform -ne 'windows-x64') {
    throw 'Not a supported Windows EXE package manifest.'
}
foreach ($file in @($manifest.files) + @($manifest.artifacts)) {
    $path = PackagePath $file.path
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.sha256) {
        throw "Package hash mismatch: $($file.path)"
    }
}
$image = PackagePath $manifest.imageDirectory
$exe = Join-Path $image ($manifest.application + '.exe')
$java = Join-Path $image 'runtime/bin/java.exe'
$jar = Join-Path $image 'app/sip-client.jar'
$native = Join-Path $image 'app/native'
$archive = @($manifest.artifacts | Where-Object {$_.path.EndsWith('.zip', [StringComparison]::OrdinalIgnoreCase)})
if ($archive.Count -ne 1) { throw 'Expected exactly one portable ZIP.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((PackagePath $archive[0].path))
try {
    $paths = @($zip.Entries | ForEach-Object {$_.FullName.Replace('\','/')})
    if ($paths -notcontains ($manifest.application + '/' + $manifest.application + '.exe') -or
        $paths -notcontains ($manifest.application + '/runtime/bin/java.exe')) { throw 'Portable ZIP is missing its EXE or Java runtime.' }
    if (@($paths | Where-Object {$_ -match '(^|/)(\.env|\.local-prefs)(/|$)|\.db(-wal|-shm)?$'}).Count) {
        throw 'Development preferences, environment files or databases were included in the ZIP.'
    }
} finally { $zip.Dispose() }
$evidence = Join-Path $package ('evidence/verify-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$previousJava = $env:JAVA_HOME; $previousPath = $env:PATH
$launcherProcess = $null; $ownedProcesses = @{}; $windowTitle = $null
try {
    $env:JAVA_HOME = $null
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    & $java ('-Dsip.native.dir=' + $native) ('-Dsip.build.id=' + $manifest.buildId) -cp $jar lab.sip.client.DeliveryDiagnostics *> (Join-Path $evidence 'native-preflight.log')
    if ($LASTEXITCODE -ne 0) { throw "Bundled native preflight failed; inspect $evidence" }
    $probe = Start-Process -FilePath $exe -ArgumentList '--version' -WorkingDirectory $evidence -WindowStyle Hidden -Wait -PassThru `
        -RedirectStandardOutput (Join-Path $evidence 'version.log') -RedirectStandardError (Join-Path $evidence 'version.err.log')
    if ($probe.ExitCode -ne 0 -or (Get-Content -LiteralPath (Join-Path $evidence 'version.log') -Raw) -notlike '*Fat JAR probe OK*') {
        throw 'The native EXE did not successfully run its bundled application.'
    }
    if ($Gui) {
        $launcherProcess = Start-Process -FilePath $exe -WorkingDirectory $evidence -WindowStyle Normal -PassThru `
            -RedirectStandardOutput (Join-Path $evidence 'gui.log') -RedirectStandardError (Join-Path $evidence 'gui.err.log')
        $ownedProcesses[$launcherProcess.Id] = $launcherProcess.StartTime
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        do {
            # jpackage can start a second instance of its launcher to host the JVM.
            $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($launcherProcess.Id)" |
                Where-Object {$_.Name -eq ($manifest.application + '.exe')})
            $candidates = @($launcherProcess.Id) + @($children | ForEach-Object {$_.ProcessId})
            foreach ($candidate in $candidates) {
                $current = Get-Process -Id $candidate -ErrorAction SilentlyContinue
                if (-not $current) { continue }
                if (-not $ownedProcesses.ContainsKey($current.Id)) { $ownedProcesses[$current.Id] = $current.StartTime }
                $current.Refresh()
                if ($current.MainWindowHandle -ne 0 -and $current.Responding -and $current.MainWindowTitle) {
                    $windowTitle = $current.MainWindowTitle
                    break
                }
            }
            if ($windowTitle) { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $windowTitle) { throw "No responding EXE desktop window appeared; inspect $evidence" }
    }
    [ordered]@{status='PASS';commit=$manifest.commit;buildId=$manifest.buildId;manifestHashes='PASS';
        portableZip='PASS';nativeCoreAndHelpers='PASS';exeVersion='PASS';externalJavaRequired=$false;
        externalMsys2Required=$false;guiVerified=[bool]$Gui;guiTitle=$windowTitle;
        installerCreated=[bool]$manifest.installerIncluded;installerInstalled=$false;
        cameraMicrophoneOrCallsTested=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8
    Write-Output "WINDOWS_EXE_PACKAGE_PASS: $evidence"
} finally {
    # Close only the GUI verification instance and its observed jpackage child.
    $owned = @($ownedProcesses.GetEnumerator()) | Sort-Object {if ($launcherProcess -and $_.Key -eq $launcherProcess.Id) {1} else {0}}
    foreach ($entry in $owned) {
        $current = Get-Process -Id $entry.Key -ErrorAction SilentlyContinue
        if ($current -and $current.StartTime -eq $entry.Value) {
            if ($current.MainWindowHandle -ne 0) { $current.CloseMainWindow() | Out-Null }
            if (-not $current.WaitForExit(5000)) { $current.Kill(); $current.WaitForExit(5000) | Out-Null }
        }
    }
    $env:JAVA_HOME = $previousJava; $env:PATH = $previousPath
}

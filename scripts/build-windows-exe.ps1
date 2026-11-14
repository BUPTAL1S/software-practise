#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn.cmd',
    [string]$NativeDirectory,
    [string]$WixDirectory,
    [string]$OutputDirectory,
    [ValidatePattern('^[A-Za-z][A-Za-z0-9-]*$')][string]$AppName = 'SIPConnect',
    [string]$AppVersion,
    [guid]$UpgradeUuid = 'a82e72e5-dcd1-4fbd-ab2c-7a771b83b0d8',
    [switch]$AppImageOnly,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows -or -not [Environment]::Is64BitProcess) { throw 'Use Windows x64 PowerShell 7.' }
$repo = Split-Path $PSScriptRoot -Parent
if (-not $JdkHome) { throw 'Pass -JdkHome or set JAVA_HOME to a Windows x64 JDK 17.' }
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
$release = Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw
if ($release -notmatch 'JAVA_VERSION="17\.' -or $release -notmatch 'OS_NAME="Windows"' -or
    $release -notmatch 'OS_ARCH="(x86_64|amd64)"') { throw 'A Windows x64 JDK 17 is required.' }
foreach ($tool in @('java.exe','jar.exe','jlink.exe','jpackage.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $JdkHome "bin/$tool"))) { throw "JDK tool missing: $tool" }
}
if (-not $NativeDirectory) { $NativeDirectory = Join-Path $repo 'native/windows-x64' }
$native = (Resolve-Path -LiteralPath $NativeDirectory).Path
foreach ($name in @('pjsua2.dll','libopenh264-7.dll','SDL2.dll','libwinpthread-1.dll','libgcc_s_seh-1.dll','libstdc++-6.dll','callwindow.dll','winmirror.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $native $name) -PathType Leaf)) { throw "Missing native dependency: $name" }
}
$jni = [IO.File]::ReadAllBytes((Join-Path $native 'pjsua2.dll'))
$pe = [BitConverter]::ToInt32($jni, 0x3c)
if ([BitConverter]::ToUInt16($jni, $pe + 4) -ne 0x8664) { throw 'pjsua2.dll must be x64.' }
$nativeText = [Text.Encoding]::ASCII.GetString($jni)
foreach ($marker in @('Video latency policy: decode-floor=','SDL viewport policy: renderer-output aspect-fit','SDL idle event policy: pump-without-streams')) {
    if (-not $nativeText.Contains($marker)) { throw "Rebuild PJSUA2 with the current Windows patches: missing $marker" }
}
foreach ($helper in @(@('callwindow.dll','Java_lab_sip_client_WindowLayout_attachScaledNative'),
                       @('winmirror.dll','Java_lab_sip_client_NativeMirror_mirrorApiVersionNative'))) {
    if (-not [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes((Join-Path $native $helper[0]))).Contains($helper[1])) {
        throw "Rebuild the ABI 2 native helper: $($helper[0])"
    }
}
if (-not $AppImageOnly) {
    if ($WixDirectory) {
        $WixDirectory = (Resolve-Path -LiteralPath $WixDirectory).Path
        foreach ($tool in @('candle.exe','light.exe')) {
            if (-not (Test-Path -LiteralPath (Join-Path $WixDirectory $tool))) { throw "WiX 3 tool missing: $tool" }
        }
    } else {
        foreach ($tool in @('candle.exe','light.exe')) {
            if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
                throw 'Install/extract WiX 3 and pass -WixDirectory, or use -AppImageOnly for a portable EXE.'
            }
        }
    }
}
[xml]$pom = Get-Content -LiteralPath (Join-Path $repo 'client/pom.xml') -Raw
if (-not $AppVersion) { $AppVersion = ([string]$pom.project.version) -replace '-.*$','' }
if ($AppVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'AppVersion must have three numeric components, such as 0.1.0.' }
$commit = & git -C $repo rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine source commit.' }
$dirty = [bool](& git -C $repo status --porcelain)
$buildId = $commit.Substring(0,12) + $(if ($dirty) { '-dirty' } else { '' })
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo ('dist/windows-exe-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw "Output already exists; choose a new directory: $output" }
if ($output.StartsWith($native + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Choose an output directory outside the native input directory.'
}
$previousJava = $env:JAVA_HOME; $previousPath = $env:PATH
try {
    $env:JAVA_HOME = $JdkHome
    $env:PATH = (Join-Path $JdkHome 'bin') + ';' + $previousPath
    if ($WixDirectory -and -not $AppImageOnly) { $env:PATH = $WixDirectory + ';' + $env:PATH }
    $mavenArgs = @('-B','-Ppackage-windows','-DskipTests','clean','package')
    if ($Offline) { $mavenArgs = @('-o') + $mavenArgs }
    & $Maven '-f' (Join-Path $repo 'client/pom.xml') @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Client clean package failed.' }
    $clientJar = Join-Path $repo ("client/target/$($pom.project.artifactId)-$($pom.project.version)-all.jar")
    $entries = & (Join-Path $JdkHome 'bin/jar.exe') tf $clientJar
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect client JAR.' }
    foreach ($entry in @('glass.dll','jfxmedia.dll','gstreamer-lite.dll','javafx/scene/media/MediaPlayer.class',
        'com/sun/glass/ui/win/WinApplication.class','org/bytedeco/ffmpeg/windows-x86_64/jniavcodec.dll',
        'org/bytedeco/ffmpeg/windows-x86_64/jniavformat.dll','org/bytedeco/javacpp/windows-x86_64/jnijavacpp.dll')) {
        if ($entries -notcontains $entry) { throw "Windows runtime missing from client JAR: $entry" }
    }
    $stage = Join-Path $output 'build'
    $inputDirectory = Join-Path $stage 'input'
    $evidence = Join-Path $output 'evidence'
    New-Item -ItemType Directory -Path (Join-Path $inputDirectory 'native'),$evidence -Force | Out-Null
    Copy-Item -LiteralPath $clientJar -Destination (Join-Path $inputDirectory 'sip-client.jar')
    Get-ChildItem -LiteralPath $native -Filter '*.dll' -File | Copy-Item -Destination (Join-Path $inputDirectory 'native')
    $audioRuntime = Join-Path $native 'preview-runtime'
    $includeAudio = $entries -contains 'lab/sip/client/WindowsPreviewAudio.class' -and
        (Test-Path -LiteralPath (Join-Path $audioRuntime 'previewaudio.dll'))
    if ($includeAudio) {
        Copy-Item -LiteralPath $audioRuntime -Destination (Join-Path $inputDirectory 'native/preview-runtime') -Recurse
        $audioNotes = Join-Path $repo 'native/windows-x64/PREVIEW.md'
        if (Test-Path -LiteralPath $audioNotes) { Copy-Item -LiteralPath $audioNotes -Destination $inputDirectory }
    }
    $readme = @"
$AppName Windows x64 / $AppVersion / build $buildId

Portable: open $AppName.exe and keep its entire folder together.
Installer: run installer/$AppName-$AppVersion.exe when included.
Java and the native DLLs are bundled; receiving PCs do not need Java, Maven or MSYS2.
Connect to your existing Spring Boot HTTP, Kamailio and FreeSWITCH services.
Configure SIP and HTTP addresses in the existing account settings.
Use a different account or close the old client before signing in with the same SIP account.
Packaging verification does not certify cameras, microphones or calls on another computer.
"@
    $readme | Set-Content -LiteralPath (Join-Path $inputDirectory 'README.txt') -Encoding utf8
    $modules = 'java.se,jdk.unsupported,jdk.unsupported.desktop,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.jfr,jdk.naming.dns'
    $runtime = Join-Path $stage 'runtime'
    & (Join-Path $JdkHome 'bin/jlink.exe') --add-modules $modules --strip-debug --no-man-pages --no-header-files --compress=2 --output $runtime
    if ($LASTEXITCODE -ne 0) { throw 'Bundled Java runtime build failed.' }
    $image = Join-Path $output "portable/$AppName"
    $packageArgs = @('--type','app-image','--name',$AppName,'--app-version',$AppVersion,
        '--vendor','Software Practise Team','--description','SIP Connect desktop client',
        '--input',$inputDirectory,'--dest',(Join-Path $output 'portable'),
        '--main-jar','sip-client.jar','--main-class','lab.sip.client.Launcher','--runtime-image',$runtime,
        '--java-options','-Dsip.native.dir=$APPDIR/native',
        '--java-options','-Djava.library.path=$APPDIR/native',
        '--java-options',('-Dsip.build.id=' + $buildId),
        '--java-options','-XX:ErrorFile=$APPDIR/../logs/hs_err_pid%p.log')
    & (Join-Path $JdkHome 'bin/jpackage.exe') @packageArgs
    if ($LASTEXITCODE -ne 0) { throw 'Portable Windows EXE build failed.' }
    New-Item -ItemType Directory -Path (Join-Path $image 'logs') -Force | Out-Null
    'Native JVM crash reports are written here.' | Set-Content -LiteralPath (Join-Path $image 'logs/README.txt') -Encoding ascii
    $artifacts = @()
    if (-not $AppImageOnly) {
        $installerArgs = @('--type','exe','--name',$AppName,'--app-version',$AppVersion,
            '--vendor','Software Practise Team','--description','SIP Connect desktop client',
            '--app-image',$image,'--dest',(Join-Path $output 'installer'),
            '--win-per-user-install','--win-dir-chooser','--win-menu','--win-menu-group','SIP Connect',
            '--win-shortcut','--win-shortcut-prompt','--install-dir',$AppName,
            '--win-upgrade-uuid',$UpgradeUuid.ToString())
        & (Join-Path $JdkHome 'bin/jpackage.exe') @installerArgs
        if ($LASTEXITCODE -ne 0) { throw 'Single-file EXE installer build failed.' }
        $artifacts += Join-Path $output "installer/$AppName-$AppVersion.exe"
    }
    $zip = Join-Path $output "$AppName-portable.zip"
    Compress-Archive -LiteralPath $image -DestinationPath $zip -CompressionLevel Optimal
    $artifacts += $zip
    function DescribeFile($file) {
        [ordered]@{path=[IO.Path]::GetRelativePath($output,$file.FullName);bytes=$file.Length;
            sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
    }
    $manifest = [ordered]@{format='sipconnect-windows-exe-v1';application=$AppName;version=$AppVersion;
        commit=$commit;dirty=$dirty;buildId=$buildId;platform='windows-x64';
        java=([regex]::Match($release,'JAVA_VERSION="([^"]+)"').Groups[1].Value);
        javafx=[string]$pom.project.properties.'javafx.version';createdAt=(Get-Date).ToString('o');modules=$modules;
        imageDirectory=[IO.Path]::GetRelativePath($output,$image);nativePreviewAudioIncluded=$includeAudio;
        installerIncluded=(-not $AppImageOnly);upgradeUuid=$UpgradeUuid.ToString();
        artifacts=@($artifacts | ForEach-Object { DescribeFile (Get-Item -LiteralPath $_) });
        files=@(Get-ChildItem -LiteralPath $image -Recurse -File | ForEach-Object { DescribeFile $_ })}
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8
    $readme | Set-Content -LiteralPath (Join-Path $output 'README.txt') -Encoding utf8
    Write-Output "WINDOWS_EXE_READY: $output"
    Write-Output 'Run scripts/verify-windows-exe.ps1 to verify the completed package.'
} finally { $env:JAVA_HOME = $previousJava; $env:PATH = $previousPath }

param([switch]$UseMirror, [switch]$IncludeDeviceTests, [string]$TestRunner)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $localGradle = Join-Path $projectRoot '.tools\gradle-8.7\bin\gradle.bat'
    $buildTool = if(Test-Path -LiteralPath $localGradle) { $localGradle } else { Join-Path $projectRoot 'gradlew.bat' }
    $tempRoot = Join-Path $projectRoot '.tools\temp'
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $tempOption = $tempRoot.Replace('\', '/')
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$tempOption -Djava.io.tmpdir=$tempOption".Trim()
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-home'
    $buildArgs = @(':core:test', ':app:assembleDebug', ':app:lintDebug', '--console=plain')
    if($UseMirror) { $buildArgs += '-PuseMirror=true' }
    if($IncludeDeviceTests) { $buildArgs += ':app:assembleDebugAndroidTest' }
    if($TestRunner) { $buildArgs += "-PtestRunner=$TestRunner" }
    & $buildTool @buildArgs
    if($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apkFile = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $apkArchive = [IO.Compression.ZipFile]::OpenRead($apkFile)
    try {
        $nativeFiles = @($apkArchive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/.*\.so$' })
        $incompatibleFiles = @($nativeFiles | Where-Object { $_.FullName -notmatch '^lib/armeabi-v7a/' })
        if($incompatibleFiles.Count -gt 0) { throw '安装包包含非 v7a 原生库，请检查依赖和 ABI 配置。' }
        Write-Output "v7a compatibility: $($nativeFiles.Count) native libraries; no incompatible ABI."
    } finally { $apkArchive.Dispose() }
} finally { Pop-Location }

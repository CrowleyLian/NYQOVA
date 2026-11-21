param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    # Export current working files, without publishing local Git history.
    $tracked = @(& git -c core.quotepath=false ls-files --cached)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read Git file list.' }
    $ignored = @(& git -c core.quotepath=false ls-files --cached --ignored --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read ignored file list.' }
    $releaseFiles = @('README.md', '.gitignore', '.gitattributes', 'LICENSE', 'NOTICE',
        'CONTRIBUTING.md', 'THIRD_PARTY_NOTICES.md', 'docs/github-release.md',
        'scripts/export-source.ps1')
    $files = @(@($tracked + $releaseFiles) | Sort-Object -Unique | Where-Object {
        $_ -notin $ignored -and (Test-Path -LiteralPath (Join-Path $projectRoot $_) -PathType Leaf)
    })
    foreach ($relativePath in $files) {
        if ($relativePath -match '(^|/)(\.git|\.tools|\.gradle[^/]*|artifacts|参考布局|build)/' -or
            $relativePath -match '(^|/)(local\.properties|keystore\.properties|signing\.properties|\.env(\..*)?)$' -or
            $relativePath -match '\.(jks|keystore|p12|pfx|pem|key|apk|aab|log)$') {
            throw "Private or generated file in export list: $relativePath"
        }
    }
    if ($files.Count -eq 0) { throw 'No source files found.' }
    $outputDir = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    $archivePath = Join-Path $outputDir 'NYQOVA-source.zip'
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $stream = [IO.File]::Open($archivePath, [IO.FileMode]::Create)
    try {
        $archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($relativePath in $files) {
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,
                    (Join-Path $projectRoot $relativePath), $relativePath.Replace('\', '/'),
                    [IO.Compression.CompressionLevel]::Optimal) | Out-Null
            }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
    $checksum = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $outputDir 'NYQOVA-source.sha256'), "$checksum  NYQOVA-source.zip`n")
    Write-Output "Exported $($files.Count) files to $archivePath"
    if (!(Test-Path -LiteralPath (Join-Path $projectRoot 'LICENSE'))) {
        Write-Warning 'Project license is pending. Confirm it and export again before publishing.'
    }
} finally { Pop-Location }

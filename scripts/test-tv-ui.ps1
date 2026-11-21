param([Parameter(Mandatory = $true)][string]$Serial, [string[]]$Phases = @('main','resume','interaction','menus','fixes','counts','search','remote','appearance','initials','current-track','themes','reliability'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
$adbTask = Join-Path $projectRoot '.tools\sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adbTask)) {
    $adbCommand = Get-Command adb -ErrorAction Stop
    $adbTask = $adbCommand.Source
}
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'artifacts') | Out-Null
$changedDisplay = $false
try {
    $deviceTask=(& $adbTask -s $Serial get-state 2>$null) -join ''
    if($LASTEXITCODE -ne 0 -or $deviceTask.Trim() -ne 'device') { throw 'USB debug device is not connected; reconnect before device validation.' }
    $sizeTask = (& $adbTask -s $Serial shell wm size) -join "`n"
    $densityTask = (& $adbTask -s $Serial shell wm density) -join "`n"
    $oldSizeTask = if($sizeTask -match 'Override size: (\d+x\d+)') { $Matches[1] } else { 'reset' }
    $oldDensityTask = if($densityTask -match 'Override density: (\d+)') { $Matches[1] } else { 'reset' }
    & $adbTask -s $Serial shell wm size 1080x1920
    & $adbTask -s $Serial shell wm density 320
    $changedDisplay = $true
    & $adbTask -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if($LASTEXITCODE -ne 0) { throw 'App installation failed' }
    & $adbTask -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if($LASTEXITCODE -ne 0) { throw 'Test installation failed' }
    & $adbTask -s $Serial logcat -c
    foreach($phaseTask in $Phases) {
        if($phaseTask -in @('resume','codec-resume')) { & $adbTask -s $Serial shell am force-stop com.nyqova.app }
        & $adbTask -s $Serial shell am instrument --abi armeabi-v7a -w -e require1080 true -e mode $phaseTask com.nyqova.app.test/com.nyqova.app.TvInstrumentation | Tee-Object -Variable resultTask
        $reportTask = ($resultTask -join "`n").TrimEnd() + "`n"
        [IO.File]::WriteAllText((Join-Path $projectRoot "artifacts/tv-0.6.1-$phaseTask.txt"),$reportTask)
        if($LASTEXITCODE -ne 0 -or $reportTask -match '(?m)^FAIL:|INSTRUMENTATION_FAILED' -or $reportTask -notmatch 'PASS') { throw "Device validation failed: $phaseTask" }
    }
    foreach($screenTask in @('tv-lucide-expanded-1080.png','tv-lucide-collapsed-1080.png','tv-lucide-player-1080.png','tv-idle-settings-1080.png','tv-albums-1080.png','tv-queue-1080.png','tv-menus-player-1080.png','tv-queue-clear-confirm-1080.png','tv-full-player-1080.png','tv-online-sources-1080.png','tv-counts-library-1080.png','tv-counts-albums-1080.png','tv-counts-artists-1080.png','tv-ape-cue-1080.png','tv-search-albums-1080.png','tv-about-1080.png','tv-playlist-albums-1080.png','tv-mini-focus-1080.png','tv-theme-blue-1080.png','tv-theme-green-1080.png','tv-collapsed-label-1080.png','tv-custom-playlists-1080.png','tv-initial-results-1080.png','tv-initial-keyboard-1080.png','tv-search-keyboard-1080.png','tv-search-zjl-1080.png','tv-theme-classic-settings-1080.png','tv-theme-midnight-settings-1080.png','tv-theme-warm-settings-1080.png','tv-theme-contrast-settings-1080.png','tv-theme-classic-home-1080.png','tv-theme-midnight-home-1080.png','tv-theme-warm-home-1080.png','tv-theme-contrast-home-1080.png','tv-theme-warm-search-1080.png','tv-theme-contrast-search-1080.png','tv-theme-contrast-player-1080.png','tv-theme-contrast-queue-1080.png','tv-discovery-responsive-1080.png')) {
        & $adbTask -s $Serial pull "/sdcard/Android/data/com.nyqova.app/files/$screenTask" "artifacts/$screenTask"
    }
} finally {
    if($changedDisplay) {
        & $adbTask -s $Serial shell wm size $oldSizeTask
        & $adbTask -s $Serial shell wm density $oldDensityTask
        & $adbTask -s $Serial shell am start -n com.nyqova.app/.MainActivity
    }
    Pop-Location
}

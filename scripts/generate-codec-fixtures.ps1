$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $fixtureRoot=Join-Path $projectRoot '.tools/codec-fixtures'
    New-Item -ItemType Directory -Force $fixtureRoot | Out-Null
    & wsl.exe -d Ubuntu -- bash -lc 'cache_root="${NYQOVA_NATIVE_CACHE:-$HOME/.cache/nyqova-native}"; cmake -S "$cache_root/ape/jni/maclib" -B "$cache_root/mac-host" -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED=OFF > "$cache_root/mac-host-config.log" && cmake --build "$cache_root/mac-host" -j4 > "$cache_root/mac-host-build.log" 2>&1'
    if($LASTEXITCODE -ne 0) { throw 'Build native codecs first, then create the reference encoder.' }
    & ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 120 -ac 2 -c:a pcm_s16le -y .tools/codec-fixtures/tone.wav
    if($LASTEXITCODE -ne 0) { throw 'FFmpeg WAV fixture failed' }
    & ffmpeg -hide_banner -loglevel error -i .tools/codec-fixtures/tone.wav -c:a alac -metadata title='ALAC 16 Test' -metadata artist=NYQOVA -metadata album='Codec Fixtures' -movflags +faststart -y .tools/codec-fixtures/alac16.m4a
    if($LASTEXITCODE -ne 0) { throw 'ALAC 16 fixture failed' }
    & ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=880:sample_rate=96000' -t 25 -ac 2 -c:a alac -sample_fmt s32p -metadata title='ALAC 24 Test' -metadata artist=NYQOVA -metadata album='Codec Fixtures' -movflags +faststart -y .tools/codec-fixtures/alac24.m4a
    if($LASTEXITCODE -ne 0) { throw 'ALAC 24 fixture failed' }
    & ffmpeg -hide_banner -loglevel error -i .tools/codec-fixtures/tone.wav -c:a libmp3lame -b:a 128k -metadata title='Mislabel Test' -metadata artist=NYQOVA -metadata album='Codec Fixtures' -y .tools/codec-fixtures/mislabel.mp3
    if($LASTEXITCODE -ne 0) { throw 'Mislabeled format fixture failed' }
    Copy-Item -LiteralPath (Join-Path $fixtureRoot 'mislabel.mp3') -Destination (Join-Path $fixtureRoot 'mislabel.ape')
    & ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=blue:s=64x64 -frames:v 1 -update 1 -y .tools/codec-fixtures/cover.jpg
    $wslFixtureRoot=(& wsl.exe -d Ubuntu -- wslpath -u $fixtureRoot).Trim()
    $apePath=Join-Path $fixtureRoot 'normal.ape'
    if(Test-Path -LiteralPath $apePath) { Remove-Item -LiteralPath $apePath }
    & wsl.exe -d Ubuntu -- bash -lc ('cache_root="${NYQOVA_NATIVE_CACHE:-$HOME/.cache/nyqova-native}"; "$cache_root/mac-host/mac" "'+$wslFixtureRoot+'/tone.wav" "'+$wslFixtureRoot+'/normal.ape" -c2000')
    if($LASTEXITCODE -ne 0) { throw 'APE reference encode failed' }
    @'
import pathlib,struct,shutil
root=pathlib.Path('.tools/codec-fixtures')
items=[]
for key,value in [('Title',b'APE Standalone Test'),('Artist',b'NYQOVA'),('Album',b'Codec Fixtures'),('Genre',b'Test'),('Track',b'1'),('Cover Art (Front)',b'cover.jpg\0'+(root/'cover.jpg').read_bytes())]:
    items.append(struct.pack('<II',len(value),2 if key.startswith('Cover') else 0)+key.encode()+b'\0'+value)
body=b''.join(items)
with (root/'normal.ape').open('ab') as out:
    out.write(body+struct.pack('<8sIIII8s',b'APETAGEX',2000,len(body)+32,len(items),0,b'\0'*8))
shutil.copyfile(root/'normal.ape',root/'album.ape')
(root/'album.cue').write_text('PERFORMER "NYQOVA"\nTITLE "APE CUE Album"\nFILE "album.ape" WAVE\n  TRACK 01 AUDIO\n    TITLE "Part One"\n    INDEX 01 00:00:00\n  TRACK 02 AUDIO\n    TITLE "Part Two"\n    INDEX 01 00:40:00\n  TRACK 03 AUDIO\n    TITLE "Part Three"\n    INDEX 01 01:20:00\n',encoding='utf-8')
'@ | python -
    if($LASTEXITCODE -ne 0) { throw 'Fixture metadata failed' }
} finally { Pop-Location }

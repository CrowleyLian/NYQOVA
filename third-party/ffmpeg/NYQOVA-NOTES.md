# NYQOVA integration

Java bridge and JNI source: AndroidX Media3 1.4.1, revision `c35a9d62baec57118ea898e271ac66819399649b` (Apache-2.0).
Source: https://github.com/androidx/media/tree/1.4.1/libraries/decoder_ffmpeg

Native library: FFmpeg 6.0, official `https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz`, SHA-256 `57be87c22d9b49c112b6d24bc67d42508660e6b718b3db89c44e47e289137082`.

Only the ALAC decoder and its required audio conversion dependencies are enabled. No GPL/nonfree codecs, executables, demuxers, network protocols or video decoders are enabled. FFmpeg uses LGPL-2.1-or-later in this configuration; see COPYING.LGPLv2.1. The JNI audio decoder class carries an explicit UnstableApi marker for app-level Lint; bridge behavior is unchanged.

`scripts/build-native-codecs.sh` downloads the exact source, checks hashes, builds static FFmpeg libraries, then links `libffmpegJNI.so` for armeabi-v7a / Android API 23 with NDK r26b. Build source and intermediate static libraries remain in `$NYQOVA_NATIVE_CACHE` (default `$HOME/.cache/nyqova-native`). The script can rebuild/relink a modified FFmpeg into a replacement JNI shared library; replace app/src/main/jniLibs/armeabi-v7a/libffmpegJNI.so and rebuild/sign the APK with the normal Gradle build. There is no integrity check that prevents a modified replacement library.

Both decoder licenses and notices are packaged under app assets/licenses and available in Settings → Appearance → Open-source licenses.

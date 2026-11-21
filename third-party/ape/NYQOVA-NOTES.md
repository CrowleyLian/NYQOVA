# NYQOVA integration

Source: https://github.com/eugenedibtsev/media3-decoder-ape
Revision: `a8b5b8976f68043e48269cdafbfd4fc12457e4fe` (Apache-2.0).

MACLib 13.27 is built from the official, SHA-256-checked SDK (BSD-3-clause; MD5 notice retained). Native source is downloaded by `scripts/build-native-codecs.sh`, rather than copied into this tree.

Local compatibility changes for Media3 1.4.1:

- Remove `experimentalSetCodecsToParseWithinGopSampleDependencies`, which is absent in 1.4.1.
- Remove the newer `TrackOutput.durationUs` call; duration continues to be published by the extractor's `SeekMap`.
- Mark package-private JNI/tag classes as `UnstableApi` for app-level Lint.

Compiled with NDK r26b for `armeabi-v7a` / Android API 23, Release, static C++ runtime; no Media3 version or Android minimum upgrade. Source headers and original notices are retained. Native code and Java bridge JNI package names remain aligned.

#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cache_root="${NYQOVA_NATIVE_CACHE:-$HOME/.cache/nyqova-native}"
mkdir -p "$cache_root"
cd "$cache_root"
if [[ ! -f ndk.zip ]]; then curl -fL --retry 3 -o ndk.zip https://dl.google.com/android/repository/android-ndk-r26b-linux.zip; fi
echo 'ad73c0370f0b0a87d1671ed2fd5a9ac9acfd1eb5c43a7fbfbd330f85d19dd632  ndk.zip' | sha256sum -c -
if [[ ! -d android-ndk-r26b ]]; then unzip -q ndk.zip; fi
if [[ ! -f ffmpeg.tar.xz ]]; then curl -fL --retry 3 -o ffmpeg.tar.xz https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz; fi
echo '57be87c22d9b49c112b6d24bc67d42508660e6b718b3db89c44e47e289137082  ffmpeg.tar.xz' | sha256sum -c -
if [[ ! -d ffmpeg-6.0 ]]; then tar xf ffmpeg.tar.xz; fi
if [[ ! -f maclib.zip ]]; then curl -fL --retry 3 -o maclib.zip https://monkeysaudio.com/files/MAC_1327_SDK.zip; fi
echo 'c47c6b36f6a7bd50d990f2eb36a70915c0074a7b9634be396c94464905e76686  maclib.zip' | sha256sum -c -
mkdir -p ape/jni ffmpeg/jni
cp "$repo_root"/third-party/ape/jni/* ape/jni/
cp "$repo_root"/third-party/ffmpeg/jni/* ffmpeg/jni/
if [[ ! -d ape/jni/maclib ]]; then unzip -q maclib.zip -d ape/jni/maclib; fi
ndk_path="$cache_root/android-ndk-r26b"
toolchain="$ndk_path/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd ffmpeg-6.0
./configure --target-os=android --arch=arm --cpu=armv7-a \
  --cc="$toolchain/armv7a-linux-androideabi23-clang" \
  --cxx="$toolchain/armv7a-linux-androideabi23-clang++" \
  --nm="$toolchain/llvm-nm" --ar="$toolchain/llvm-ar" --ranlib="$toolchain/llvm-ranlib" --strip="$toolchain/llvm-strip" \
  --enable-cross-compile --enable-static --disable-shared --disable-doc --disable-programs \
  --disable-everything --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter \
  --disable-symver --disable-v4l2-m2m --disable-vulkan --disable-network --enable-swresample --enable-decoder=alac \
  --extra-cflags='-march=armv7-a -mfloat-abi=softfp -fPIC' --extra-ldflags='-Wl,--fix-cortex-a8' \
  --libdir="$cache_root/ffmpeg-6.0/android-libs/armeabi-v7a" > "$cache_root/ffmpeg-configure.log"
make -j4 > "$cache_root/ffmpeg-make.log" 2>&1
make install-libs >> "$cache_root/ffmpeg-make.log" 2>&1
ln -sfn "$cache_root/ffmpeg-6.0" "$cache_root/ffmpeg/jni/ffmpeg"
for module in ape ffmpeg; do
  cmake -S "$cache_root/$module/jni" -B "$cache_root/$module/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_path/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-23 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
  cmake --build "$cache_root/$module/build" -j4 > "$cache_root/$module-build.log" 2>&1
done
mkdir -p "$repo_root/app/src/main/jniLibs/armeabi-v7a"
cp "$cache_root/ape/build/libapeJNI.so" "$cache_root/ffmpeg/build/libffmpegJNI.so" "$repo_root/app/src/main/jniLibs/armeabi-v7a/"
"$toolchain/llvm-strip" "$repo_root/app/src/main/jniLibs/armeabi-v7a/"*.so
cp "$cache_root/ffmpeg-6.0/COPYING.LGPLv2.1" "$repo_root/third-party/ffmpeg/COPYING.LGPLv2.1"
echo 'Native codecs built: armeabi-v7a, Android API 23, ALAC + MACLib APE.'

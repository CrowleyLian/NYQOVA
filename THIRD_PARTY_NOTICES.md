# 第三方组件与许可

以下为仓库中直接集成的组件及主要构建依赖，非完整传递依赖清单。项目级许可只适用于维护者有权授权的原创部分，不能替代或收紧第三方已有授权。发布二进制时还需核对完整依赖与所需声明。

| 组件 | 使用方式与版本 | 许可与来源记录 |
| --- | --- | --- |
| AndroidX Media3 FFmpeg 桥接 | 1.4.1 Java / JNI 源码 | Apache-2.0；[集成说明](third-party/ffmpeg/NYQOVA-NOTES.md)、[许可](third-party/ffmpeg/LICENSE) |
| FFmpeg | 6.0，仅 ALAC 解码及音频转换依赖，静态链接至 `libffmpegJNI.so` | LGPL-2.1-or-later；[完整许可](third-party/ffmpeg/COPYING.LGPLv2.1)、[构建脚本](scripts/build-native-codecs.sh) |
| media3-decoder-ape | 固定提交的 Java / JNI 桥接代码 | Apache-2.0；[集成说明](third-party/ape/NYQOVA-NOTES.md)、[许可](third-party/ape/LICENSE) |
| Monkey’s Audio / MACLib | 13.27，链接至 `libapeJNI.so` | 保留上游 BSD 三条款文本；[许可](third-party/ape/licenses/monkeys-audio-LICENSE.txt)、[NOTICE](third-party/ape/NOTICE) |
| RSA MD5 | MACLib 中使用 | [原始声明](third-party/ape/licenses/rsa-md5-NOTICE.txt) |
| Lucide / Feather 衍生图标 | 固定提交 SVG 与生成的 Android VectorDrawable | [上游完整许可](third-party/lucide/LICENSE)、[来源与哈希](third-party/lucide/README.md) |
| OpenCC 字典 | 简繁字符映射数据 | Apache-2.0；[来源](third-party/opencc/README.md)、[许可](third-party/opencc/LICENSE) |

主要 Maven 依赖包括 AndroidX Room 2.6.1、RecyclerView 1.3.2、Media3 1.4.1、OkHttp 4.12.0、SMBJ 0.13.0、SLF4J Android 1.7.36、desugar_jdk_libs 2.1.2、Checker Qual 3.33.0，以及测试依赖 JUnit 4.13.2。实际配置以 `app/build.gradle.kts` 与 `core/build.gradle.kts` 为准；发行前应核对下载构件内的 LICENSE / NOTICE 与传递依赖，不能只依赖本表。

## 原生库及 APK 分发

两份预编译 v7a JNI 库为构建所需文件，保留在 `app/src/main/jniLibs/armeabi-v7a/`。脚本记录来源、固定版本和 SHA-256 校验；详细改动保存在各组件的 `NYQOVA-NOTES.md`。

FFmpeg 使用静态链接。发布 APK 前，应按 LGPL 要求准备对应源码、修改说明及允许用户修改、重新链接的材料和步骤，并核对实际二进制与这些材料对应。当前仓库有源码下载和重建脚本，不等同于已经完成所有二进制发行义务。项目的 Apache-2.0 许可不替代第三方组件原有许可证，也不免除相应的分发义务。

应用内已有 `app/src/main/assets/licenses/`，设置中的许可入口可查看已打包的声明；这部分尚不是完整的 Maven 依赖声明汇总。源码公开前的文件整理不视为完整许可审计。

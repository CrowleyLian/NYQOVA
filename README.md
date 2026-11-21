# NYQOVA · 聆序

面向 Android TV 的原生音乐播放器，连接 SMB / WebDAV 音乐库，支持遥控器操作、CUE 分轨、在线歌词和封面。

当前版本：**0.6.1**。项目仍在开发中，目标设备的兼容性与长时间播放表现需要实机验证。

## 功能

- SMB2/3、WebDAV 只读文件浏览；连接信息通过 Android Keystore 加密保存。
- WAV、FLAC、MP3、AAC、Ogg/Opus、APE、APE+CUE、M4A/ALAC 播放；APE 使用 MACLib，ALAC 使用 FFmpeg。
- CUE 分轨，支持 UTF-8、GB18030、UTF-16；本地 LRC 与在线歌词、候选版本选择。
- 增量音乐库、专辑/歌手/风格浏览、中文首字母搜索、收藏、最近播放和自建歌单。
- 后台播放、队列及进度恢复、循环/随机播放、遥控器焦点与媒体按键。
- 四套外观主题、独立强调色、可收起侧栏及空闲自动进入播放页。

0.6.1 将局域网 NSD 初始化移入独立后台通道，降低部分电视固件服务阻塞造成界面无响应的风险；歌词存在候选时可自动显示首项，并支持手动更换。

## 兼容性

- 最低 Android 6.0（API 23），主要面向 Android TV 横屏设备。
- 当前原生解码库仅提供 **armeabi-v7a**。设备必须支持 32 位 ARM 应用；不适用于只支持 64 位应用的设备和 x86 模拟器。
- compileSdk / targetSdk 为 34；目标电视盒子以 Android 9 / v7a 为主要兼容约束。
- 手机可用于调试；手机测试不能替代目标电视盒子的遥控器、固件、HDMI / USB DAC 验证。
- 暂不承诺 DSD、SACD ISO、bit-perfect 或采样级无缝播放。

## 构建

准备 Git、JDK 21、Android SDK Platform 34 和 Build Tools 34.0.0。编译目标为 Java 17，Gradle Wrapper 使用 8.7。依赖首次下载需要网络。

在项目根目录创建 `local.properties`（已忽略），按自己的安装路径填写，例如：

```properties
sdk.dir=C:/Android/Sdk
```

Windows PowerShell：

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

Linux / macOS：

```bash
bash ./gradlew :core:test :app:assembleDebug :app:lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。这是调试包；正式分发前应单独配置发布签名，签名密钥和密码不能纳入仓库。

Windows 也可运行：

```powershell
.\scripts\build.ps1 -UseMirror
```

该脚本使用项目相对路径设置缓存和临时目录；优先使用本地 `.tools/gradle-8.7`，不存在时使用 Wrapper。`-UseMirror` 为可选参数，仅在需要时启用阿里云 Maven 镜像。直接调用 Gradle 时可传 `-PuseMirror=true`。

仓库保留构建所需的 Gradle Wrapper、Room schema 和两份 v7a JNI 库。通常无需重编原生库；需要修改时，在 Linux / WSL 中准备 Bash、curl、unzip、tar、make、CMake、Ninja 和 C/C++ 编译工具后执行 `bash scripts/build-native-codecs.sh`。该脚本下载并校验固定版本的 NDK、FFmpeg 与 MACLib，详见第三方集成说明。

## 使用

1. 在“设置 → 服务器设置”填写 `smb://服务器/共享` 或 `http(s)://服务器:端口/目录/`。
2. 按需填写用户名、密码和域；SMB 用户名留空表示访客。
3. 在“音乐库管理”扫描音乐目录，再按专辑、歌曲、歌手或歌单播放。
4. 歌词全屏页可手动更换候选版本；在线服务可在设置中选择。

应用没有预置 NAS 地址或账号。局域网发现由用户主动打开，部分网络仍需手动填入共享名、端口或子目录。WebDAV 重定向会被拒绝，避免将认证信息发送到其他地址。

在线匹配会向所选服务发送曲名、歌手、专辑及歌曲时长，不发送 NAS 凭据、文件路径或音频；本地封面和 LRC 优先。在线歌词与封面可能匹配失败或选错版本，可手动重试和更换。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | Android TV 界面、媒体服务、音乐库、网络来源与实机测试 |
| `core/` | CUE / LRC 解析、文本匹配和 JVM 测试 |
| `third-party/` | 第三方桥接代码、图标、来源记录和许可证 |
| `gradle/` | Gradle Wrapper |
| `scripts/` | 构建、图标导入、测试和干净源码导出 |
| `docs/` | 架构、版本说明、验证记录和发布指南 |

`.tools/`、`.gradle*/`、`local.properties`、`artifacts/` 和 `参考布局/` 为本机内容，不纳入公开源码。历史文档中的日志和截图路径指本地验证产物，公开仓库不附带这些文件。

## 文档与参与

- [架构](docs/architecture.md)
- [兼容性与连接](docs/compatibility-and-connections.md)
- [0.6.1 修复说明](docs/tv-0.6.1.md)、[0.6.0 外观主题](docs/tv-0.6.0.md)
- [在线歌词与封面](docs/online-media.md)
- [贡献与测试](CONTRIBUTING.md)
- [上传 GitHub 前的准备](docs/github-release.md)
- [第三方组件与许可](THIRD_PARTY_NOTICES.md)

## 许可证

NYQOVA 原创代码采用 [Apache License 2.0](LICENSE)，允许商业使用、修改和分发，具体条件以许可证原文为准。版权声明见 [NOTICE](NOTICE)。

第三方源码、字典、图标与原生解码库保留各自许可证，详见 [第三方声明](THIRD_PARTY_NOTICES.md)；项目许可证不替代这些组件的原有授权。

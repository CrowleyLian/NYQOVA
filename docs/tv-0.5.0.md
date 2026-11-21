# 0.5.0：APE / ALAC 与完整数量

## 播放与音乐库

- 增加 APE 单曲与 APE+CUE 入库、播放、相对定位、切歌、队列进度恢复；有效 CUE 对应的整轨按原规则隐藏。
- 支持 M4A 容器中的 ALAC，包括已测试的 16-bit/44.1kHz 与 24-bit/96kHz 双声道。没有把任意扩展名为 `.alac` 的文件直接视为可播放容器。
- APE 使用 MACLib 原生解码并向现有 Media3 音频管线输出 PCM；ALAC 使用与当前 Media3 1.4.1 对应的 FFmpeg 音频扩展。FFmpeg 只启用 ALAC，因此优先扩展不会接管 MP3/AAC/FLAC 等原有解码。
- APE/M4A 标签通过 Media3 容器解析读取；有界 NAS Range、总字节/时间预算、取消检查仍保留。读到音频格式、标签和时长后停止，不为标签解码整首。
- APE 文件先检查实际文件头。后缀为 APE、内容实际为 MP3/FLAC/MP4 的文件沿相应解析路径读取标签，不将后缀直接当成内容格式；真实 NAS 测试样本另外验证了 APE 文件头，避免只验证改名文件。
- 普通扫描会按新版目录指纹纳入此前跳过的 APE，不必使用强制重建。现有收藏、歌单、连接、CUE ID 与数据库版本保留。
- 兼容要求不变：compileSdk 34 / minSdk 23；新增两份原生库仅含 armeabi-v7a，按 Android API 23 构建，C++ runtime 静态链接。不提升 Android 系统下限，也不依赖 64 位进程。

## 数量显示

标题旁使用弱化文字显示总数，不增加焦点项或额外菜单。音乐库/收藏/最近/搜索及专辑详情显示歌曲数；专辑墙显示当前范围的专辑数；歌手、风格显示完整分组数，选中歌手原有信息区继续显示其曲目数；播放列表不计算“新建”按钮，包含四个内置播放入口；文件夹分别显示可见文件夹和歌曲数量。

计数在后台查询完整数据库范围，歌曲按 CUE 逻辑曲目计算，不以 80 项分页结果代替总数。搜索、收藏专辑筛选、歌单及递归文件夹使用对应范围。歌手数按现有歌手分组统计，未知歌手、联合署名均按当前分组规则处理，不宣称独立自然人人数。

## 构建与来源

源码与改动说明见 `third-party/ape/NYQOVA-NOTES.md`、`third-party/ffmpeg/NYQOVA-NOTES.md`。库使用 Apache-2.0、MACLib BSD-3 与 FFmpeg LGPL-2.1-or-later，未启用 GPL/nonfree FFmpeg 解码器。许可证已打包，设置 → 外观设置 → 开源许可可查看。

Windows 常规构建直接使用已提交的 v7a `.so`。需要重建原生库时，在已安装 CMake、Ninja、make、curl、unzip 的 WSL Ubuntu 中运行：

```powershell
wsl -d Ubuntu -- bash /mnt/d/project/NYQOVA/scripts/build-native-codecs.sh
./scripts/build.ps1 -UseMirror -IncludeDeviceTests -TestRunner com.nyqova.app.TvInstrumentation
```

原生脚本固定 NDK r26b、FFmpeg 6.0、MACLib 13.27 并校验压缩包 SHA-256。修改 FFmpeg 后可用同一脚本重链接 JNI 库、替换 `.so` 并自行重建/签名 APK；缓存保留构建所需源码和静态库。

## 验证

USB Android 10 手机以明确的 32 位进程与 1920×1080 画面测试；Android 9 目标盒子仍需实机验收，不承诺 bit-perfect、DSD、SACD ISO 或无间隙输出。

- 本地只读 DAV/Range 服务：APE 标签/封面/时长，ALAC 16-bit 与 24-bit/96kHz 的标签、播放与跳转；三轨 APE+CUE 的整轨隐藏、第二轨从 40s 开始、相对跳转与下一轨。
- 用户真实 NAS：总库 1,716 首，其中新增 365 首 APE 逻辑曲目；APE 单曲与 CUE 专辑播放/跳转、强制停止后的 APE+CUE 相对进度恢复通过。
- 计数：1,716 首、863 张专辑、765 个歌手分组；页面只加载 80 项时仍显示完整总数。实际标签补全后专辑/歌手分组数量可能继续变化。
- 搜索、空结果、专辑详情与原有菜单、播放、歌词、收藏/歌单、队列恢复回归通过。

复现格式测试需要 Windows FFmpeg、Python 与 WSL 中可构建的 MACLib SDK；样本为自行生成的测试音，不打包进 App，不向 NAS 写入：

```powershell
./scripts/generate-codec-fixtures.ps1
python scripts/codec-fixture-server.py --directory .tools/codec-fixtures
# 另一个终端：
./.tools/sdk/platform-tools/adb.exe reverse tcp:8765 tcp:8765
./scripts/test-tv-ui.ps1 -Phases codecs,codec-resume,counts,main,resume,menus
```

完成后停止测试 HTTP 服务，移除 `adb reverse tcp:8765`。APK 为 `artifacts/NYQOVA-0.5.0-v7a-debug.apk`，报告为 `artifacts/tv-0.5.0-*.txt`。

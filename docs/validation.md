# 第一阶段验证

日期：2026-10-26。状态随本轮实机测试更新。

- 调试设备：USB 荣耀 COL-AL10，Android 10 / API 29，arm64-v8a。
- 手机与 NAS 位于同一局域网，NAS 可达。
- SMB 来源：测试 NAS 的 SMB 共享，用户确认允许访客；本机地址不公开。
- WebDAV 来源：测试 NAS 的 WebDAV 服务，地址与凭据不记录在公开项目内。
- WebDAV PROPFIND 已成功读取根目录及两张专辑。
- 真实整轨样本：《林忆莲 - Love, Sandy》，包含 WAV 与 CUE。
- JVM 测试：CUE 时间、INDEX 00/01、多 FILE、异常倒序、中文编码、制表符，5 项通过。

## 已通过

- Debug APK 在 COL-AL10 安装、启动成功，横屏中文显示及焦点样式可见。
- SMB 访客连接、WebDAV 用户认证均通过 App 连接表单验证；错误地址会留在表单内并显示提示。
- 两种来源均成功列出 7 个支持项目，播放真实 FLAC，读取并解析真实 WAV+CUE 的 10 首曲目。
- 两种来源均从 CUE 第二轨开始播放，跳转至 60 秒后继续播放；定位到曲尾后自动进入第三轨。
- 两种来源的上一曲、暂停均通过实机自动验证。
- SMB MP3 另经界面点击播放验证，系统状态为 PLAYING 且进度前进。
- 遥控器媒体下一曲按键可将整轨第一轨切到第二轨。
- 返回手机桌面后第二轨继续播放，服务报告 `isForeground=true`。
- 最终正常运行的 App 已通过界面重新连接 WebDAV；测试时总 PSS 为 63,485 KB（约 62 MB）。这是单次调试观测值，不是性能保证。
- 5 项 JVM 测试通过；Android Lint：0 错误，14 条警告（依赖更新、国际化、固定横屏等）。
- 最终 APK SHA-256：`AD0E2A3922DFA85A46BE62F0582D4716DC2226E103654406ACEF2A53B66724CA`。

自动验证结果：`artifacts/smb-smoke.txt`、`artifacts/webdav-smoke.txt`。截图：`artifacts/smb-verified.png`、`artifacts/webdav-verified.png`。

验证程序为独立测试 APK；仅通过显式传入参数使用 WebDAV 凭据，结果中不输出密码。测试结束后系统会结束受测进程，正常启动时需重新输入 WebDAV 密码。

设备系统报告的播放状态可证明音频链路已运行，不能代替人工听音。E900V20D、外接 DAC、无缝切轨及 6 小时稳定性需后续实机测试。

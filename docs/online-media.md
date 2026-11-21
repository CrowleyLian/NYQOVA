# 0.2.1 在线封面与歌词修复

上一版 0.2.0 只接入本地 LRC、目录封面和内嵌封面；原方案中的在线查询尚未接上。NAS 没有匹配 LRC 时，歌词页因而没有内容。此版本补齐原定的在线链路。

## 行为

- 播放后自动查询：本地 LRC → 设备缓存 → LRCLIB。播放页底部预览当前歌词；点击“歌词”全屏查看。
- LRCLIB 精确查询使用曲名、歌手、专辑和时长；原专辑不存在时允许去掉专辑条件。中文查询尝试繁简形式。
- 自动应用需曲名、艺人和录音版本一致，已知时长差不超过 3 秒。现场版、其他歌手和时长明显不同的版本不会自动套用。匹配不确定时显示候选列表。
- 无匹配、纯音乐、只有纯文本、服务繁忙和网络失败分别显示。支持重试、手动搜索及版本选择。
- 在线歌词以曲目为时间轴；不会错误叠加整轨 CUE 的文件起点。本地整轨 LRC 仍按原文件时间轴同步。
- 本地/内嵌封面缺失时，在专辑墙和播放页查询 MusicBrainz + Cover Art Archive。缩略图保存在设备，并更新音乐库。
- 成功匹配的歌词与封面缓存可跨进程使用；确定无匹配的歌词缓存 1 小时、封面缓存 24 小时。网络异常不写成永久无结果。
- 切歌取消旧请求并检查曲目标识，避免旧查询覆盖新歌曲。音乐播放不等待歌词或封面查询。

## 数据与第三方

本会话用户已授权在线匹配。只发送曲名、歌手、专辑及歌曲时长，不发送 NAS 地址、账号密码、目录路径或音频。公共客户端与 NAS 客户端独立，并拒绝认证/Cookie 请求头。第三方图片重定向限 HTTPS 和指定封面存储域。

- 歌词：[LRCLIB API](https://lrclib.net/docs)
- 专辑：[MusicBrainz](https://musicbrainz.org/doc/MusicBrainz_API)，请求间隔至少 1.1 秒。
- 封面：[Cover Art Archive](https://musicbrainz.org/doc/Cover_Art_Archive/API)
- 繁简查询键：[OpenCC](https://github.com/BYVoid/OpenCC)，使用字符字典，许可证见 `third-party/opencc/`。原始显示标题不改写。

## 验证

USB 手机强制 32 位运行：英文同步歌词、中文《伤痕》的繁简匹配、MusicBrainz/CAA 封面、持久缓存、真实 NAS 播放自动查词，以及歌词页跳转高亮均通过。原始结果在 `artifacts/online-v7a.txt`。

独立测试：`scripts/build.ps1 -UseMirror -IncludeDeviceTests -TestRunner com.nyqova.app.OnlineInstrumentation`，安装测试包后运行该 Instrumentation；`-e cacheOnly true` 验证跨进程缓存。测试结果仅记录服务记录 ID 和状态，不包含歌词正文。

最低系统仍为 Android 6.0，目标盒子 Android 9 / v7a；不新增原生库、不升级现有播放依赖。部分歌曲、地区网络或服务故障仍可能没有在线结果，界面会明确显示状态。

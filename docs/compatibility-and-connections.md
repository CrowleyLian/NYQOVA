# Android 9 / v7a 与连接设置

## 兼容约束

目标盒子由用户确认为 Android 9（API 28），只能安装 armeabi-v7a 版本。项目保持 minSdk 23（Android 6.0），不会为新功能提高系统下限；新增原生解码器必须提供 armeabi-v7a。Gradle 已设置 `abiFilters = armeabi-v7a`，禁止今后误打包只支持 arm64 的原生库。

当前播放器包含 APE 与 FFmpeg 的 armeabi-v7a 原生 `.so`，安装目标需要支持该 ABI。`compileSdk=34` 是电脑上的编译 API，`targetSdk=34` 是新系统行为声明，不意味着最低需要 Android 14。实际安装下限由 `minSdk=23` 决定。当前版本没有升级 Media3、Kotlin 或 Android Gradle Plugin。

构建脚本会检查最终 APK 的 `lib/` 内容，出现非 armeabi-v7a 原生库时直接失败。最低系统和编译 SDK 的含义参见 [Android 官方说明](https://developer.android.com/guide/topics/manifest/uses-sdk-element)。

USB 手机可用 `am instrument --abi armeabi-v7a` 强制 32 位运行验证，但不能代替 E900V20D 的安装、遥控器、HDMI 和固件兼容实测。

## 一次设置与自动连接

连接地址、用户名、密码、域整体使用 AES-GCM 加密。加密密钥由 Android Keystore 保留，应用私有文件只保存密文和随机 IV。启动自动解密并恢复当前来源，强制停止或设备重启后无需重输密码。覆盖安装并保留应用数据时继续有效；清除数据、卸载或 NAS 改密后需要重新设置。

旧版仅存地址的配置继续读取一次，重新保存连接后迁移为加密配置。不会将密码写入 Git、APK 或测试结果。Android 系统禁止应用备份，避免将只属于本机的密文迁移到另一台设备。

## 局域网发现

在连接设置选择“发现局域网设备”。优先监听 `_smb._tcp`、`_webdav._tcp`、`_webdavs._tcp` 服务广播，同时对当前私有局域网的 /24 网段执行有界探测，检查 SMB 445 及 WebDAV 常用端口 8888、5005、5006、80、8080。不会探测公网，也不会发送已保存的账号密码。

发现的设备可直接选入地址栏。SMB 共享名仍可能需要修改，协议本身并不保证能匿名列出所有共享。带 DAV 响应头的服务标为 WebDAV；仅返回认证要求的 HTTP 服务标为候选，需要连接后确认。自定义端口、非根路径、未广播服务、客户端隔离或非 /24 网络可能需要手动补充地址。发现只在用户打开此功能时运行，关闭对话框即取消。

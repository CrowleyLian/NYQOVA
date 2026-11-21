# 参与与验证

提交问题时，请说明应用版本、设备型号、Android 版本、ABI、连接协议、复现步骤及预期行为。日志中先移除账号密码、NAS 地址、设备序列号、访问令牌及私人文件名。

修改前请阅读 README、架构及对应版本文档。保持 minSdk 23、armeabi-v7a 和遥控器焦点兼容；第三方代码的改动需要在其集成说明中记录。

## 本地验证

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

实机测试需要自行准备设备和 NAS，不在持续集成中自动访问网络音乐库。先构建独立测试 APK，再明确传入自己的设备序列号：

```powershell
.\scripts\build.ps1 -IncludeDeviceTests -TestRunner com.nyqova.app.TvInstrumentation
.\scripts\test-tv-ui.ps1 -Serial '你的设备序列号' -Phases reliability,initials,menus
```

TV 测试会安装调试应用与测试包、操作界面并临时修改显示参数；请使用专用测试设备，按测试阶段准备音乐库。脚本结束时尝试恢复显示参数，输出保存在忽略的 `artifacts/`。

`SmokeInstrumentation` 的 NAS 测试必须显式传入 `url`，无默认服务器。仅在自己的设备上按需传入凭据；不要把带密码的命令、终端历史或日志上传至问题和代码评审。

## 提交范围

源码、构建配置、Room schema、必要的 JNI 库和第三方声明应保留。缓存、SDK、APK、签名材料、私有配置、测试日志、截图与设计参考图不提交。

提交原创贡献时，请确认你有权按项目的 Apache-2.0 许可证提交；引用第三方代码时保留其来源和许可声明。

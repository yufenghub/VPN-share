# VPN share

将手机已连接的 VPN 共享给热点设备，无需 root，连接设备无需设置代理。内置无线 ADB，无需安装 Shizuku。

## 下载

前往 [Releases](https://github.com/yufenghub/VPN-share/releases/latest) 下载 APK。已安装旧版可直接覆盖升级。

## 使用

1. 首次使用：连接 Wi-Fi，开启开发者选项中的“无线调试”。
2. 在应用中点击“ADB → 配对”，允许通知。
3. 打开系统“使用配对码配对设备”，保持窗口打开，在应用通知内输入配对码并发送。
4. 连接手机上的 VPN，设置系统热点名称和密码，点击“启动共享”。
5. 其他设备连接该热点即可使用，结束时点击“停止共享”。

热点已开时优先复用；若出口切换失败，会自动重开热点一次。热点未开时先准备出口再开启热点。关闭系统热点会同步停止共享；停止共享也会关闭热点。

详细操作和常见问题见 [使用说明](使用说明.md)。

## 支持

- Android 11 及以上，ARM64 / x86_64；建议 Android 13 及以上，具体兼容性取决于系统热点接口。
- 应用不提供 VPN 节点，需先安装并连接自己的 VPN。
- 手机重启或无线调试关闭后，可能需要重新开启无线调试并点击“连接”。

构建方法和实现细节见 [开发说明](README-HOTSPOTSHARE.md)。

## 来源与许可证

基于 [Shizzi v0.4.0-rc.3](https://github.com/carlelieser/shizzi/releases/tag/v0.4.0-rc.3) 修改。

VPN share 原创新增代码和修改采用 [MIT 许可证](LICENSE)。上游代码及第三方组件保留各自权利和许可证，见 [NOTICE](NOTICE.md) 与 [ADB 组件说明](adb/NOTICE.md)。

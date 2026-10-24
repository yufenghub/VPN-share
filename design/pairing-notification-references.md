# 无线调试配对通知参考

查阅日期：2026-10-24。

- Shizuku 配对服务：https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt
  - mDNS 发现端口后发布带 RemoteInput 的输入通知。
  - 使用 PendingIntent.getForegroundService 接收回复；把发现的端口写入 PendingIntent，防止服务重建时丢失。
- Shizuku 官方中文手册：https://shizuku.rikka.app/zh-hans/guide/setup/
  - 保留系统配对码窗口，在 Shizuku 通知内输入。
  - MIUI 通知回复异常时，官方建议使用原生通知样式。
- Android 官方通知内回复：https://developer.android.com/develop/ui/compose/notifications/create-notification#reply-action
  - RemoteInput 在通知内输入，不打开 Activity；发送后更新通知确认已收到。
  - setOnlyAlertOnce(true) 会抑制更新时的声音、振动和视觉提醒。
- Android 官方通知渠道：https://developer.android.com/develop/ui/compose/notifications/channels
  - IMPORTANCE_HIGH 允许横幅提醒；渠道创建后，用户控制其提醒行为。

本应用将低优先级“配对搜索”与高优先级“配对码”分开，端口新出现时允许提醒；输入和配对阶段不重复提醒。通知只有输入及取消动作，输入使用系统通知回复框，不申请悬浮窗权限。是否显示横幅仍由手机通知设置及勿扰模式决定；横幅未显示时可下拉并展开同一通知输入。

本记录说明实现依据，不代表已完成真实手机上的通知回复验证。

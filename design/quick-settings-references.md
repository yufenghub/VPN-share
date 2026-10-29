# 快捷设置实现依据

- Android 官方 Tile 状态说明：UNAVAILABLE 没有点击动作；INACTIVE 和 ACTIVE 允许点击。因此需要进入设置的入口不能标为 UNAVAILABLE。https://developer.android.com/reference/android/service/quicksettings/Tile#STATE_UNAVAILABLE
- Android 官方快捷设置指南：监听生命周期可能多次开始和结束，需要取消旧监听并刷新状态。https://developer.android.com/develop/ui/views/quicksettings-tiles
- TileService 官方 API：Android 14 起使用 startActivityAndCollapse(PendingIntent)；有解锁需求时使用 unlockAndRun。https://developer.android.com/reference/android/service/quicksettings/TileService
- Android 后台 Activity 安全说明：目标 Android 15 起创建方默认不授予后台启动权限，需要时显式设置创建方 ActivityOptions。本项目仅为由用户点按的专用快捷设置 Activity PendingIntent 配置。https://developer.android.com/guide/components/activities/secure-bal

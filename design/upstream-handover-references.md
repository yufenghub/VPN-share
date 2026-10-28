# 热点出口切换依据

- Android 16 Tethering：setPreferTestNetworks 在处理线程记录偏好，不主动调用 chooseUpstreamType。https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/src/com/android/networkstack/tethering/Tethering.java
- Android 16 UpstreamNetworkMonitor：从现有候选中选择测试网络；已有出口时，新的非当前网络事件并不必然触发重新选择。https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/src/com/android/networkstack/tethering/UpstreamNetworkMonitor.java
- Android 16 TetheringManager：系统 upstream 回调可直接读取实际 Network；legacy tether/untether 在 Android 16 不受支持，不能用作 Wi-Fi 不重开的通用替代。https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/common/TetheringLib/src/android/net/TetheringManager.java
- Shizuku 官方手册：配对与服务激活是两个阶段，重启和系统关闭调试会影响服务。https://shizuku.rikka.app/guide/setup/

实现先准备可用的测试网络和 VPN 转发，再设置偏好、启动热点；回调确认出口。正常复用不重开，只有等待出口切换失败时恢复一次。恢复期间停用本轮热点关闭观察器，重开后重新观察，旧回调不能停止新观察器。

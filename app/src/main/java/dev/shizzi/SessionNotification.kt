package dev.shizzi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class SessionNotification(private val context: Context) {

    fun build(state: SessionUiState, isStopping: Boolean): Notification {
        createChannel()

        val text = bodyFor(state, isStopping)

        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(titleFor(state, isStopping))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppIntent())
            .setOngoing(state.status != UiStatus.ERROR)
            .addAction(stopAction())
            .build()
    }

    private fun titleFor(state: SessionUiState, isStopping: Boolean): String =
        when (state.status) {
            UiStatus.CONNECTED -> connectedTitle(state)
            UiStatus.LOADING -> if (isStopping) "正在停止…" else "正在准备…"
            UiStatus.ERROR -> "共享已停止"
            UiStatus.READY -> "共享已停止"
        }

    private fun connectedTitle(state: SessionUiState): String = when {
        state.isVpnBypassed -> "共享中 · 已绕过 VPN"
        state.isVpnBound -> "共享中 · VPN 已连接"
        else -> "共享中"
    }

    private fun bodyFor(state: SessionUiState, isStopping: Boolean): String = when {
        state.lastError.isNotEmpty() -> userFacingError(state.lastError)
        state.status == UiStatus.LOADING -> loadingBody(isStopping)
        state.status == UiStatus.CONNECTED -> connectedBody(state)
        else -> "未开启共享"
    }

    private fun userFacingError(raw: String): String = when {
        raw.contains(EXCEPTION_MARKER) -> "共享出现问题，请打开应用查看详情。"
        else -> raw
    }

    private fun loadingBody(isStopping: Boolean): String =
        if (isStopping) "正在关闭热点…" else "正在开启热点…"

    private fun connectedBody(state: SessionUiState): String = when (state.clientCount) {
        0 -> "等待设备连接"
        else -> "${deviceCount(state.clientCount)} · " +
            "$DOWN_ARROW ${Traffic.format(state.traffic.down)} · " +
            "$UP_ARROW ${Traffic.format(state.traffic.up)}"
    }

    private fun deviceCount(clients: Int): String = when (clients) {
        1 -> "1 台设备"
        else -> "$clients 台设备"
    }

    fun describeLoss(problem: String?): String = when (problem) {
        null -> "共享服务已停止，热点已关闭。请重新连接 ADB。"

        else -> "共享已结束，但热点可能仍在运行：$problem；" +
            "请在系统设置中关闭热点。"
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopAction(): Notification.Action = Notification.Action.Builder(
        null,
        "停止共享",
        PendingIntent.getService(
            context,
            1,
            Intent(context, SessionService::class.java).setAction(SessionService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "共享期间显示状态和停止按钮" }

        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private companion object {
        const val CHANNEL_ID = "tethering-session"

        const val EXCEPTION_MARKER = "Exception"

        const val DOWN_ARROW = "\u2193"
        const val UP_ARROW = "\u2191"
    }
}

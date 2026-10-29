package dev.shizzi

import android.service.quicksettings.Tile

enum class TileAction { START, STOP, OPEN_APP, NONE }

data class TileRender(
    val state: Int,
    val subtitle: String,
    val action: TileAction,
)

object SessionTile {

    const val LABEL = "VPN share"

    fun render(session: SessionUiState, shizuku: ShizukuState, isStopping: Boolean): TileRender {
        return when {
            session.isBusy || session.isStopping || session.status == UiStatus.LOADING -> TileRender(
                state = Tile.STATE_UNAVAILABLE,
                subtitle = if (isStopping || session.isStopping) "正在停止…" else "正在启动…",
                action = TileAction.NONE,
            )

            session.status == UiStatus.CONNECTED -> TileRender(
                state = Tile.STATE_ACTIVE,
                subtitle = describeConnected(session),
                action = TileAction.STOP,
            )

            shizuku !is ShizukuState.Ready -> needsSetup(shizuku)

            session.status == UiStatus.ERROR -> TileRender(
                state = Tile.STATE_INACTIVE,
                subtitle = "点按重试",
                action = TileAction.START,
            )

            else -> TileRender(
                state = Tile.STATE_INACTIVE,
                subtitle = "点按共享",
                action = TileAction.START,
            )
        }
    }

    private fun needsSetup(shizuku: ShizukuState) = TileRender(
        // STATE_UNAVAILABLE prevents SystemUI from delivering onClick at all.
        state = Tile.STATE_INACTIVE,
        subtitle = describe(shizuku),
        action = TileAction.OPEN_APP,
    )

    private fun describe(shizuku: ShizukuState): String = when (shizuku) {
        ShizukuState.NotInstalled -> "请配对 ADB"
        ShizukuState.NotRunning -> "请连接 ADB"
        ShizukuState.PermissionRequired -> "请配对 ADB"
        is ShizukuState.Ready -> ""
    }

    private fun describeConnected(session: SessionUiState): String = when (session.clientCount) {
        0 -> "等待设备连接"
        1 -> "1 台设备 · ${Traffic.format(session.traffic.down)}"
        else -> "${session.clientCount} 台设备 · ${Traffic.format(session.traffic.down)}"
    }
}

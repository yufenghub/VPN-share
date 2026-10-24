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
        if (shizuku !is ShizukuState.Ready) return unavailable(shizuku)

        return when (session.status) {
            UiStatus.LOADING -> TileRender(
                state = Tile.STATE_UNAVAILABLE,
                subtitle = if (isStopping) "正在停止…" else "正在启动…",
                action = TileAction.NONE,
            )

            UiStatus.CONNECTED -> TileRender(
                state = Tile.STATE_ACTIVE,
                subtitle = describeConnected(session),
                action = TileAction.STOP,
            )

            UiStatus.ERROR -> TileRender(
                state = Tile.STATE_INACTIVE,
                subtitle = "点按重试",
                action = TileAction.START,
            )

            UiStatus.READY -> TileRender(
                state = Tile.STATE_INACTIVE,
                subtitle = "点按共享",
                action = TileAction.START,
            )
        }
    }

    private fun unavailable(shizuku: ShizukuState) = TileRender(
        state = Tile.STATE_UNAVAILABLE,
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

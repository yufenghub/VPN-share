package dev.shizzi

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTileTest {

    private val ready = ShizukuState.Ready(uid = 2000, isRoot = false)

    @Test
    fun `is unavailable and opens the app while shizuku is missing`() {
        val render = SessionTile.render(SessionUiState(), ShizukuState.NotInstalled, false)

        assertEquals(Tile.STATE_UNAVAILABLE, render.state)
        assertEquals("请配对 ADB", render.subtitle)
        assertEquals(TileAction.OPEN_APP, render.action)
    }

    @Test
    fun `names the reason shizuku cannot be used`() {
        val stopped = SessionTile.render(SessionUiState(), ShizukuState.NotRunning, false)
        val ungranted =
            SessionTile.render(SessionUiState(), ShizukuState.PermissionRequired, false)

        assertEquals("请连接 ADB", stopped.subtitle)
        assertEquals("请配对 ADB", ungranted.subtitle)
    }

    @Test
    fun `offers a start while idle`() {
        val render = SessionTile.render(SessionUiState(status = UiStatus.READY), ready, false)

        assertEquals(Tile.STATE_INACTIVE, render.state)
        assertEquals("点按共享", render.subtitle)
        assertEquals(TileAction.START, render.action)
    }

    @Test
    fun `refuses a tap while a session is starting`() {
        val render = SessionTile.render(SessionUiState(status = UiStatus.LOADING), ready, false)

        assertEquals(Tile.STATE_UNAVAILABLE, render.state)
        assertEquals("正在启动…", render.subtitle)
        assertEquals(TileAction.NONE, render.action)
    }

    @Test
    fun `says it is stopping while tearing a session down`() {
        val render = SessionTile.render(SessionUiState(status = UiStatus.LOADING), ready, true)

        assertEquals("正在停止…", render.subtitle)
        assertEquals(TileAction.NONE, render.action)
    }

    @Test
    fun `offers a stop while connected`() {
        val state = SessionUiState(status = UiStatus.CONNECTED)

        val render = SessionTile.render(state, ready, false)

        assertEquals(Tile.STATE_ACTIVE, render.state)
        assertEquals(TileAction.STOP, render.action)
    }

    @Test
    fun `counts connected devices in the subtitle`() {
        val none = SessionUiState(status = UiStatus.CONNECTED)
        val one = none.copy(clientCount = 1, traffic = Traffic(down = 2_000))
        val many = none.copy(clientCount = 3, traffic = Traffic(down = 2_000))

        assertEquals("等待设备连接", SessionTile.render(none, ready, false).subtitle)
        assertEquals("1 台设备 · 2.0 KB", SessionTile.render(one, ready, false).subtitle)
        assertEquals("3 台设备 · 2.0 KB", SessionTile.render(many, ready, false).subtitle)
    }

    @Test
    fun `offers a retry after a failure`() {
        val render = SessionTile.render(SessionUiState(status = UiStatus.ERROR), ready, false)

        assertEquals(Tile.STATE_INACTIVE, render.state)
        assertEquals("点按重试", render.subtitle)
        assertEquals(TileAction.START, render.action)
    }
}

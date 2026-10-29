package dev.shizzi

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SessionTileService : TileService() {

    private var scope: CoroutineScope? = null

    private val controller = TetherClient()
    private var restoredSession: SessionUiState? = null
    private var pendingCommand: Boolean? = null

    override fun onStartListening() {
        super.onStartListening()

        scope?.cancel()
        pendingCommand = null
        restoredSession = null
        val created = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = created

        created.launch {
            SessionService.liveState.collectLatest { session ->
                if (SessionService.isRunning) {
                    restoredSession = null
                    if (session.isBusy || session.isStopping || session.status != UiStatus.READY) pendingCommand = null
                }
                publish(displayedSession())
            }
        }
        created.launch {
            dev.shizzi.adb.AdbAccess.state.collectLatest { access ->
                if (access.connected && !SessionService.isRunning) {
                    val outcome = runCatching { controller.status() }
                    currentCoroutineContext().ensureActive()
                    if (outcome.isSuccess && !SessionService.isRunning) {
                        restoredSession = SessionService.liveState.value.applyOutcome(outcome)
                    }
                }
                publish(displayedSession())
            }
        }
        dev.shizzi.adb.AdbAccess.reconnect()
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        pendingCommand = null
        restoredSession = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        controller.unbind()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()

        val render = currentRender()

        when (render.action) {
            TileAction.NONE -> Unit
            TileAction.OPEN_APP -> runUnlocked { openApp(adbSetup = true) }
            TileAction.START -> runUnlocked { command(isStop = false) }
            TileAction.STOP -> runUnlocked { command(isStop = true) }
        }
    }

    private fun runUnlocked(command: () -> Unit) {
        if (isLocked) unlockAndRun { command() } else command()
    }

    private fun command(isStop: Boolean) {
        pendingCommand = isStop
        restoredSession = null

        runCatching {
            when {
                isStop -> SessionService.stop(this)
                else -> SessionService.start(this)
            }
        }.onFailure { failure ->
            pendingCommand = null
            SessionLog.error(
                "tile could not reach the session service: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
            // Surface the app if the system rejects a background service start.
            openApp()
        }

        publish(displayedSession())
    }

    private fun displayedSession(): SessionUiState {
        val session = if (SessionService.isRunning) SessionService.liveState.value
            else restoredSession ?: SessionService.liveState.value
        return pendingCommand?.let { stopping ->
            session.copy(status = UiStatus.LOADING, isBusy = true, isStopping = stopping)
        } ?: session
    }

    private fun publish(session: SessionUiState) {
        val tile = qsTile ?: return
        val render = SessionTile.render(session, ShizukuGate.currentState(), session.isStopping)

        tile.state = render.state
        tile.label = SessionTile.LABEL
        tile.subtitle = render.subtitle
        tile.contentDescription = "${SessionTile.LABEL}, ${render.subtitle}"
        tile.updateTile()
    }

    private fun currentRender(): TileRender {
        val session = displayedSession()
        return SessionTile.render(session, ShizukuGate.currentState(), session.isStopping)
    }

    private fun openApp(adbSetup: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("adb_setup", adbSetup)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        3100,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        activityLaunchOptions(),
                    ),
                )

            else -> collapseOnLegacy(intent)
        }
    }

    private fun activityLaunchOptions(): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < 35) return null
        return ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapseOnLegacy(intent: Intent) = startActivityAndCollapse(intent)
}

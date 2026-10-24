package dev.shizzi

import dev.shizzi.adb.AdbAccess
import dev.shizzi.adb.ShellRpc
import kotlinx.coroutines.*
import org.json.JSONObject

enum class UiStatus { READY, LOADING, CONNECTED, ERROR }

data class SessionUiState(
    val shizukuState: ShizukuState = ShizukuState.NotRunning,
    val status: UiStatus = UiStatus.READY,
    val isBusy: Boolean = false,
    val detail: String = "",
    val interfaceName: String = "",
    val lastError: String = "",
    val isVpnBound: Boolean = false,
    val isVpnBypassed: Boolean = false,

    val clientCount: Int = 0,

    val traffic: Traffic = Traffic(),
) {

    val canStart: Boolean get() = shizukuState is ShizukuState.Ready && !isBusy
}

fun SessionUiState.asStopped(): SessionUiState = copy(
    isBusy = false,
    status = UiStatus.READY,
    lastError = "",
    detail = "Stopped",
    interfaceName = "",
    isVpnBound = false,
    isVpnBypassed = false,
    clientCount = 0,
    traffic = Traffic(),
)

fun SessionUiState.applyOutcome(outcome: Result<String>): SessionUiState {
    val failure = outcome.exceptionOrNull()
    if (failure != null) {
        return copy(
            isBusy = false,
            status = UiStatus.ERROR,
            lastError = "${failure.javaClass.simpleName}: ${failure.message}",

            interfaceName = "",
            isVpnBound = false,
            isVpnBypassed = false,
            clientCount = 0,
            traffic = Traffic(),
        )
    }

    val parsed = runCatching { JSONObject(outcome.getOrDefault("{}")) }.getOrNull()
    val sessionState = parsed?.optString("state").orEmpty()
    val sessionDetail = parsed?.optString("detail").orEmpty()

    return copy(
        isBusy = false,
        status = statusFor(sessionState),
        detail = sessionDetail,
        interfaceName = parsed?.optString("interface").orEmpty().takeIf { it != "null" }.orEmpty(),
        lastError = if (sessionState == "ERROR") sessionDetail else "",

        isVpnBound = parsed?.optBoolean("isVpnBound") == true,
        isVpnBypassed = parsed?.optBoolean("isVpnBypassed") == true,
        clientCount = parsed?.optInt("clientCount") ?: 0,
        traffic = Traffic(
            up = parsed?.optLong("bytesUp") ?: 0,
            down = parsed?.optLong("bytesDown") ?: 0,
        ),
    )
}

private fun statusFor(sessionState: String): UiStatus = when (sessionState) {
    "ACTIVE" -> UiStatus.CONNECTED
    "ERROR" -> UiStatus.ERROR
    "STARTING" -> UiStatus.LOADING
    else -> UiStatus.READY
}

class TetherClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var onSessionLost: (() -> Unit)? = null

    private suspend fun call(op: String, args: JSONObject = JSONObject()): String = withContext(Dispatchers.IO) {
        AdbAccess.ensureService()
        try { ShellRpc.call(op, args).getString("result") }
        catch (failure: Exception) {
            if (!AdbAccess.probe()) onSessionLost?.invoke()
            throw failure
        }
    }
    suspend fun runProbes(attemptTethering: Boolean) = call("probes", JSONObject().put("tether", attemptTethering))
    suspend fun checkCompatibility() = parseCapabilities(call("compatibility"))
    suspend fun installTetheringApex(path: String): StagingOutcome = error("ADB 模式不提供系统模块安装")
    suspend fun rebootDevice(): String = error("ADB 模式不提供重启操作")
    suspend fun start(logging: Boolean, vpnMode: VpnMode) = call("start", JSONObject().put("logging", logging))
    fun setLogging(enabled: Boolean) { scope.launch { runCatching { call("logging", JSONObject().put("enabled", enabled)) } } }
    suspend fun stop() = call("stop")
    suspend fun clearLog(): String? = runCatching { call("clearLog") }.exceptionOrNull()?.message
    suspend fun releaseOrphanedDownstream(): String? = runCatching { call("stop") }.exceptionOrNull()?.message
    suspend fun status() = call("status")
    // Keep the shell helper available when the UI or foreground service restarts.
    fun unbind() { scope.coroutineContext.cancelChildren() }
    fun unbindAndStopDaemon() = unbind()
}

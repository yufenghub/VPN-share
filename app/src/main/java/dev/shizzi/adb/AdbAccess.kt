package dev.shizzi.adb

import android.content.Context
import android.os.Build
import android.os.Process
import dev.shizzi.App
import dev.shizzi.BuildConfig
import dev.shizzi.TetherService
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AdbAccessState(
    val paired: Boolean = false,
    val busy: Boolean = false,
    val connected: Boolean = false,
    val error: String = "",
)

object AdbAccess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activation = Mutex()
    private val reconnectLock = Any()
    private var reconnectJob: Job? = null
    private var retainedManager: AbsAdbConnectionManager? = null
    private val localState = MutableStateFlow(AdbAccessState())
    val state = localState.asStateFlow()
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "adb-deadline").apply { isDaemon = true } }
    private val preferences get() = App.instance.getSharedPreferences("adb_access", 0)
    val paired: Boolean get() = preferences.getBoolean("paired_v1", false)

    fun initialize() {
        localState.value = AdbAccessState(paired = paired, busy = paired)
        if (paired) reconnect()
    }

    fun reconnect(port: Int? = null) {
        if (!paired) return
        synchronized(reconnectLock) {
            if (reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                for (attempt in 0..2) {
                    try {
                        ensureService(port)
                        return@launch
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (attempt == 2) return@launch
                        localState.update { it.copy(busy = true) }
                        delay(if (attempt == 0) 1000 else 2500)
                    }
                }
            }
        }
    }

    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            val alive = runCatching {
                ShellRpc.call("ping", timeout = 2500).getString("result").toInt() == TetherService.CONTRACT_VERSION
            }.getOrDefault(false)
            if (alive) {
                localState.update { it.copy(paired = paired, connected = true, error = "") }
                return@withContext true
            }
            if (attempt == 0) delay(200)
        }
        localState.update { if (it.busy) it else it.copy(paired = paired, connected = false) }
        false
    }

    suspend fun pair(port: Int, code: String, onPaired: () -> Unit = {}) = withContext(Dispatchers.IO) {
        val checkedPort = ShellProtocol.port(port.toString())
        val checkedCode = ShellProtocol.pairingCode(code)
        activation.withLock {
            localState.value = AdbAccessState(paired = paired, busy = true)
            PairingDiagnostics.begin(checkedPort)
            var manager: AbsAdbConnectionManager? = null
            try {
                manager = manager()
                check(manager.pair("127.0.0.1", checkedPort, checkedCode)) { "配对失败" }
                check(preferences.edit().putBoolean("paired_v1", true).commit())
                localState.value = AdbAccessState(paired = true)
                PairingDiagnostics.paired()
                onPaired()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                android.util.Log.e("VPNShareAdb", "Pairing failed", failure)
                PairingDiagnostics.failed(failure)
                localState.value = AdbAccessState(paired = paired, error = PairingFailure.describe(failure))
                throw failure
            } finally { runCatching { manager?.disconnect() } }
        }
        try { ensureService(); PairingDiagnostics.connected() }
        catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            PairingDiagnostics.activationFailed(failure)
            throw failure
        }
    }

    suspend fun ensureService(manualPort: Int? = null) = withContext(Dispatchers.IO) {
        activation.withLock {
            val existing = runCatching { ShellRpc.call("ping", timeout = 3000).getString("result").toInt() }.getOrNull()
            if (existing == TetherService.CONTRACT_VERSION) {
                localState.value = AdbAccessState(paired = paired, connected = true)
                return@withLock
            }
            if (existing != null) {
                runCatching { ShellRpc.call("shutdown", timeout = 15000) }
                delay(500)
            }
            check(paired) { "ADB 未配对" }
            localState.value = AdbAccessState(paired = true, busy = true)
            var manager: AbsAdbConnectionManager? = null
            try {
                runCatching { retainedManager?.disconnect() }
                retainedManager = null
                val connection = manager()
                manager = connection
                val rejectedPorts = mutableSetOf<Int>()
                var port = manualPort?.also { ShellProtocol.port(it.toString()) }
                if (port == null) {
                    val cached = preferences.getInt("connect_port", 0)
                    if (cached in 1..65535) {
                        connection.setTimeout(2, TimeUnit.SECONDS)
                        if (runCatching { connect(connection, cached) }.getOrDefault(false)) port = cached
                        else {
                            rejectedPorts += cached
                            runCatching { connection.disconnect() }
                        }
                        connection.setTimeout(8, TimeUnit.SECONDS)
                    }
                }
                if (port == null) port = AdbDiscovery.findPort(App.instance, AdbDiscovery.CONNECT, 12000, rejectedPorts)
                if (!connection.isConnected) check(connect(connection, port)) { "ADB 连接失败；请检查无线调试" }
                check(preferences.edit().putInt("connect_port", port).commit())
                val info = App.instance.applicationInfo
                val abi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: error("需要 64 位 Android")
                val nativePath = info.nativeLibraryDir + ":" + info.sourceDir + "!/lib/" + abi
                val command = ShellProtocol.launchCommand(info.sourceDir, nativePath, BuildConfig.APPLICATION_ID, ShellRpc.token(), Process.myUid())
                // Legacy shell: allocates a PTY whose hangup can kill a background
                // child before nohup has installed its signal handler.
                connection.openStream("shell,v2,raw:$command").use { stream ->
                    val deadline = timer.schedule({ runCatching { stream.close() } }, 5, TimeUnit.SECONDS)
                    try {
                        val input = stream.openInputStream()
                        var count = 0
                        val buffer = ByteArray(512)
                        while (count < 4096) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            count += read
                        }
                    } catch (_: java.io.IOException) {
                        // ADB represents normal shell stream closure as IOException as well.
                    } finally { deadline.cancel(false) }
                }
                var ready = false
                repeat(40) {
                    if (!ready) {
                        ready = runCatching {
                            ShellRpc.call("ping", timeout = 1000).getString("result").toInt() == TetherService.CONTRACT_VERSION
                        }.getOrDefault(false)
                        if (!ready) delay(250)
                    }
                }
                check(ready) { "本地服务启动失败" }
                // Reuse the authenticated ADB connection while the app remains alive.
                retainedManager = connection
                manager = null
                localState.value = AdbAccessState(paired = true, connected = true)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                android.util.Log.e("VPNShareAdb", "Activation failed", failure)
                localState.value = AdbAccessState(paired = true, error = connectionError(failure))
                throw failure
            } finally { runCatching { manager?.disconnect() } }
        }
    }

    private fun connect(manager: AbsAdbConnectionManager, port: Int): Boolean {
        val connected = manager.connect("127.0.0.1", port)
        check(!connected || manager.adbConnection?.isTlsConnection == true) { "需要无线调试 TLS 端口" }
        return connected
    }

    private fun manager(): AbsAdbConnectionManager {
        val identity = AdbIdentityStore(App.instance).loadOrCreate()
        return object : AbsAdbConnectionManager() {
            init { setApi(Build.VERSION.SDK_INT); setTimeout(8, TimeUnit.SECONDS); setThrowOnUnauthorised(true) }
            override fun getPrivateKey() = identity.privateKey
            override fun getCertificate() = identity.certificate
            override fun getDeviceName() = App.instance.getString(dev.shizzi.R.string.app_name)
        }
    }

    fun markDisconnected() { localState.value = localState.value.copy(connected = false) }

    private fun connectionError(failure: Throwable): String = when {
        failure.message?.contains("未发现") == true -> "未发现无线调试端口"
        failure.message?.contains("TLS") == true -> "需要无线调试 TLS 端口"
        failure.message?.contains("本地服务") == true -> "本地服务启动失败"
        failure is io.github.muntashirakon.adb.AdbPairingRequiredException -> "无线调试授权失效，请重新配对"
        else -> "自动连接失败，请确认系统无线调试已开启后点“连接”"
    }
}

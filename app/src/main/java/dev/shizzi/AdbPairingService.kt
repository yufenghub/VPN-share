package dev.shizzi

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Build
import android.provider.Settings
import dev.shizzi.adb.AdbAccess
import dev.shizzi.adb.AdbDiscovery
import dev.shizzi.adb.ShellProtocol
import dev.shizzi.adb.PairingSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PairingUiState(
    val active: Boolean = false,
    val port: Int = 0,
    val revision: Long = 0,
    val busy: Boolean = false,
    val finished: Boolean = false,
    val status: String = "无线调试 → 使用配对码配对设备",
)

class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var browser: AdbDiscovery? = null
    private var endpoint = PairingSession(java.security.SecureRandom().nextLong())
    private val savedEndpoint get() = getSharedPreferences("adb_pairing_notification", MODE_PRIVATE)
    private var pairing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification(ui.value.status))
        if (intent?.action == CANCEL) { stopSelf(); return START_NOT_STICKY }
        if (browser == null) {
            // Shizuku keeps the discovered port in its reply PendingIntent so a service
            // recreation does not lose it. Also validate our persisted revision and age.
            if (intent?.action == REPLY) {
                val revision = intent.getLongExtra(REVISION, Long.MIN_VALUE)
                val port = intent.getIntExtra(PORT, 0)
                val age = System.currentTimeMillis() - savedEndpoint.getLong("time", 0)
                if (port in 1..65535 && revision == savedEndpoint.getLong("revision", Long.MIN_VALUE)
                    && port == savedEndpoint.getInt("port", 0) && age in 0L..300000L) {
                    endpoint = PairingSession(revision - 1).also { it.discovered(port) }
                }
            }
            ui.value = PairingUiState(active = true, port = endpoint.port, revision = endpoint.revision)
            browser = AdbDiscovery(this, AdbDiscovery.PAIRING) { discovered ->
                val previous = endpoint.port
                if (discovered == previous) return@AdbDiscovery
                endpoint.discovered(discovered)
                savedEndpoint.edit().putInt("port", endpoint.port).putLong("revision", endpoint.revision)
                    .putLong("time", System.currentTimeMillis()).apply()
                update(ui.value.copy(port = discovered, revision = endpoint.revision, status = if (pairing) ui.value.status
                    else if (discovered == 0 && previous != 0) "配对窗口已关闭；请重新打开并输入新码"
                    else if (discovered == 0) "打开无线调试 → 使用配对码配对设备" else "点“输入配对码”，输入当前 6 位码后发送"),
                    alert = discovered != 0 && !pairing)
            }.also { it.start() }
            scope.launch {
                delay(300000)
                stopSelf()
            }
            update(ui.value)
        }
        val code = intent?.getStringExtra(CODE)
            ?: intent?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(CODE)?.toString()
        if (code != null) submitCode(code, intent?.getIntExtra(PORT, 0) ?: 0, intent?.getLongExtra(REVISION, Long.MIN_VALUE) ?: Long.MIN_VALUE)
        return START_NOT_STICKY
    }

    private fun submitCode(code: String, manualPort: Int, revision: Long) {
        if (pairing || ui.value.finished) return
        val checked = runCatching { ShellProtocol.pairingCode(code) }.getOrNull()
        if (checked == null) { update(ui.value.copy(status = "请输入 6 位配对码")); return }
        val selected = endpoint.endpoint(revision, manualPort)
        if (selected == null) {
            update(ui.value.copy(status = if (revision != endpoint.revision) "配对码已失效；请输入系统窗口的新码"
                else "未发现端口；请打开系统配对码窗口，等待输入通知"))
            return
        }
        pairing = true
        update(ui.value.copy(busy = true, status = "配对中"))
        scope.launch {
            var accepted = false
            val result = runCatching { AdbAccess.pair(selected, checked) { accepted = true } }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            pairing = false
            if (result.isSuccess || accepted) {
                update(ui.value.copy(busy = false, finished = true,
                    status = if (AdbAccess.state.value.connected) "已连接" else "已配对；请开启无线调试后连接"))
                delay(1200)
                stopSelf()
            } else update(ui.value.copy(busy = false, status = AdbAccess.state.value.error.ifBlank { "配对失败；请重新输入当前码" }))
        }
    }

    private fun update(value: PairingUiState, alert: Boolean = false) {
        ui.value = value
        getSystemService(NotificationManager::class.java).notify(ID, notification(value.status, alert))
    }

    private fun notification(status: String, alert: Boolean = false): Notification {
        prepareNotifications(this)
        val ready = ui.value.port > 0 && !ui.value.busy && !ui.value.finished
        val reply = PendingIntent.getForegroundService(this, 41, Intent(this, AdbPairingService::class.java).setAction(REPLY)
            .setData(Uri.parse("vpnshare-pair:${ui.value.revision}"))
            .putExtra(REVISION, ui.value.revision).putExtra(PORT, ui.value.port),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val cancel = PendingIntent.getService(this, 42, Intent(this, AdbPairingService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val input = RemoteInput.Builder(CODE).setLabel("6 位配对码").build()
        val action = Notification.Action.Builder(null, "输入配对码", reply).addRemoteInput(input)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).setAllowGeneratedReplies(false).build()
        val builder = Notification.Builder(this, if (ready) INPUT_CHANNEL else SEARCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_tethering)
            .setContentTitle(when { ui.value.finished -> "已配对"; ui.value.busy -> "配对中"; ready -> "输入配对码"; else -> "等待配对窗口" })
            .setContentText(status).setStyle(Notification.BigTextStyle().bigText(status))
            .setOngoing(true).setShowWhen(false).setOnlyAlertOnce(!alert).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true).setAllowSystemGeneratedContextualActions(false)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (ready) builder.addAction(action)
        return builder.addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
    }

    override fun onDestroy() {
        browser?.close()
        savedEndpoint.edit().clear().apply()
        scope.cancel()
        ui.value = ui.value.copy(active = false, busy = false, port = 0)
        super.onDestroy()
    }

    companion object {
        private const val INPUT_CHANNEL = "adb-pairing-code"
        private const val SEARCH_CHANNEL = "adb-pairing-search"
        private const val ID = 41
        private const val CODE = "pairing_code"
        private const val PORT = "pairing_port"
        private const val REVISION = "pairing_revision"
        private const val REPLY = "app.vpnshare.PAIR_REPLY"
        private const val CANCEL = "app.vpnshare.PAIR_CANCEL"
        private val ui = MutableStateFlow(PairingUiState())
        val state = ui.asStateFlow()
        fun start(context: Context) { context.startForegroundService(Intent(context, AdbPairingService::class.java)) }
        fun submit(context: Context, code: String, port: Int = 0) {
            context.startService(Intent(context, AdbPairingService::class.java).setAction(REPLY).putExtra(CODE, code).putExtra(PORT, port).putExtra(REVISION, ui.value.revision))
        }
        fun prepareNotifications(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel(SEARCH_CHANNEL, "配对搜索", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
                NotificationChannel(INPUT_CHANNEL, "配对码", NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false); setAllowBubbles(false)
                },
            ))
        }
        fun needsNotificationSettings(context: Context): Boolean {
            prepareNotifications(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            return !manager.areNotificationsEnabled() || manager.getNotificationChannel(INPUT_CHANNEL).importance < NotificationManager.IMPORTANCE_HIGH
        }
        fun notificationSettings(context: Context): Intent {
            prepareNotifications(context)
            val action = if (context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS
            return Intent(action)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, INPUT_CHANNEL)
        }
    }
}

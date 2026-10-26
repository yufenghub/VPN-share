package dev.shizzi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Watches the Wi-Fi AP itself, independently of USB tethering and client count. */
class HotspotWatchdog(
    private val context: Context,
    private val control: DownstreamControl,
    private val onStopped: (WifiHotspotState) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { command ->
        Thread(command, "hotspot-watchdog").apply { isDaemon = true }
    }
    private var registered = false
    private var poll: ScheduledFuture<*>? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION) return
            val hint = WifiHotspotState.fromPlatform(intent.getIntExtra("wifi_state", -1))
            try { executor.execute { checkState(hint) } }
            catch (_: RejectedExecutionException) { /* Already stopped. */ }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, IntentFilter(ACTION))
            }
            registered = true
        }.onFailure { SessionLog.warn("hotspot broadcasts unavailable; using state polling") }
        poll = executor.scheduleWithFixedDelay({ checkState(null) }, 0, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        running.set(false)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        poll?.cancel(false)
        poll = null
        // Teardown can run on this worker; interrupting it would abort resource cleanup.
        executor.shutdown()
    }

    private fun checkState(hint: WifiHotspotState?) {
        if (!running.get()) return
        val observed = control.wifiHotspotState()
        val state = if (observed == WifiHotspotState.UNKNOWN) hint else observed
        if (state?.isStopped == true && running.compareAndSet(true, false)) onStopped(state)
    }

    private companion object {
        const val ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    }
}

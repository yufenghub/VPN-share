package dev.shizzi

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

enum class SessionState { IDLE, STARTING, ACTIVE, ERROR }

class TetherSession(private val context: Context) {

    private val testNetworkApi = TestNetworkApi(context)
    private val inspector = UpstreamInspector()

    private var resources: SessionResources? = null
    private var state = SessionState.IDLE
    private var detail = "not started"
    private var interfaceName: String? = null

    private var activeSince: Long = 0
    private var vpnMode = VpnMode.AUTO
    private var watchdog: SessionWatchdog? = null
    private var hotspotWatchdog: HotspotWatchdog? = null
    private var upstreamMonitor: TetheringUpstreamMonitor? = null
    private var generation = 0L
    private var hotspotClosed = AtomicBoolean(false)
    private val control = DownstreamControl(context)
    private val teardown = SessionTeardown(context)
    private val downstream = DownstreamInspector()
    private var vpn = createVpnMonitor(0)

    val isActive: Boolean get() = state == SessionState.ACTIVE

    @Synchronized
    fun start(mode: VpnMode = VpnMode.AUTO): String {
        if (isActive) return status()

        generation++
        hotspotClosed = AtomicBoolean(false)
        vpn = createVpnMonitor(generation)
        vpnMode = mode
        state = SessionState.STARTING
        SessionLog.info("session start requested (vpn mode ${mode.name.lowercase()})")

        SessionLog.info(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), " +
                "contract ${TetherService.CONTRACT_VERSION}",
        )

        return runCatching { bringUp() }
            .getOrElse { failure ->
                if (failure !is HotspotClosedException) {
                    Log.e(TAG, "start failed", failure)
                    SessionLog.error(
                        "start failed: ${failure.javaClass.simpleName}: ${failure.message}",
                    )
                }
                finishSession(closeHotspot = failure !is HotspotClosedException)
                if (failure is HotspotClosedException) {
                    detail = "热点已关闭"
                } else {
                    state = SessionState.ERROR
                    detail = "${failure.javaClass.simpleName}: ${failure.message}"
                }
                status()
            }
    }

    private fun bringUp(): String {
        check(vpnMode != VpnMode.ALWAYS || vpn.isVpnPresent()) { "请先连接 VPN，再开启共享。" }
        val group = SessionResources(testNetworkApi, context.connectivityManager())
        resources = group

        val name = group.acquire(tunAddresses(), TEST_NETWORK_DNS_SERVERS, AVAILABILITY_TIMEOUT_MS)
        interfaceName = name
        SessionLog.info("tun up: $name (mtu $TUN_MTU, $TUN_ADDRESS, $TUN_ADDRESS_V6)")

        group.startDatapath(TUN_MTU)
        SessionLog.info("datapath attached to $name")
        followVpn(group)

        upstreamMonitor = runCatching {
            TetheringUpstreamMonitor(context, checkNotNull(group.acquiredNetwork), name).also { monitor ->
                try { monitor.start() } catch (failure: Exception) { monitor.close(); throw failure }
            }
        }.onFailure { SessionLog.warn("upstream callback unavailable; using dumpsys") }.getOrNull()
        preferTestNetworks()

        check(restartAndConfirmTetheringUpstream(
            restartHotspot = {
                SessionLog.info("preparing downstream: restarting Wi-Fi hotspot before upstream confirmation")
                restartHotspotForHandover()
            },
            checkSelected = { awaitUpstream(name) },
        )) { "verifyUpstream: test network was not selected after hotspot restart" }
        SessionLog.info("upstream verified: $name is sole upstream")
        requireHotspotOpen()

        state = SessionState.ACTIVE
        activeSince = System.currentTimeMillis()
        detail = "tethered clients routing through $name"

        teardown.installShutdownHook()
        startWatchdog(name)
        return status()
    }

    private fun followVpn(group: SessionResources) {
        if (vpnMode == VpnMode.NEVER) {
            SessionLog.info("vpn mode never: datapath left unbound")
            return
        }

        check(vpnMode != VpnMode.ALWAYS || vpn.isVpnPresent()) {
            "请先连接 VPN，再开启共享。"
        }
        vpn.follow(group, requireVpn = vpnMode == VpnMode.ALWAYS)
    }

    private fun startWatchdog(name: String) {
        val attempt = generation
        val guard = SessionWatchdog(name, observe = ::observeUpstream) { problem ->
            synchronized(this) {
                if (attempt == generation && isActive) {
                    val hotspot = control.wifiHotspotState()
                    if (hotspot.isStopped) onHotspotStopped(hotspot)
                    else {
                        SessionLog.warn("upstream drift: $problem")
                        tearDownAfter(problem)
                    }
                }
            }
        }
        watchdog = guard
        guard.start()
    }

    private fun createVpnMonitor(attempt: Long) = VpnUpstream(context) { problem ->
        synchronized(this) {
            if (attempt == generation && state != SessionState.IDLE) {
                val hotspot = control.wifiHotspotState()
                if (hotspot.isStopped) onHotspotStopped(hotspot) else tearDownAfter(problem)
            }
        }
    }

    private fun startHotspotWatchdog(attempt: Long, closed: AtomicBoolean) {
        lateinit var guard: HotspotWatchdog
        guard = HotspotWatchdog(context, control) { observed ->
            // Mark cancellation before taking the lock, so startup can stop between steps.
            closed.set(true)
            synchronized(this) {
                if (attempt == generation && hotspotWatchdog === guard && state != SessionState.IDLE) onHotspotStopped(observed)
            }
        }
        hotspotWatchdog = guard
        guard.start()
    }

    private fun onHotspotStopped(observed: WifiHotspotState) {
        SessionLog.info("Wi-Fi hotspot closed; stopping sharing")
        finishSession(closeHotspot = false)
        detail = "热点已关闭"
        if (observed == WifiHotspotState.FAILED) {
            state = SessionState.ERROR
            detail = "系统热点异常，已停止共享。"
        }
    }

    private fun requireHotspotOpen() {
        val observed = control.wifiHotspotState()
        check(observed != WifiHotspotState.FAILED) { "系统热点异常" }
        if (hotspotClosed.get() || observed.isStopped) throw HotspotClosedException()
    }

    private fun tearDownAfter(problem: String) {
        stop()

        val teardownProblem = detail.takeIf { state == SessionState.ERROR }
        state = SessionState.ERROR
        detail = when (teardownProblem) {
            null -> problem
            else -> "$problem; teardown incomplete: $teardownProblem"
        }
    }

    private fun preferTestNetworks() {
        val api = TetheringPreferenceApi(context)
        runCatching { api.setPreferTestNetworks(false) }
            .onFailure { SessionLog.warn("could not clear the stale test-network preference: ${it.message}") }
        api.setPreferTestNetworks(true)
    }

    private fun ensureDownstreamStarted() {
        val deadline = SystemClock.elapsedRealtime() + DOWNSTREAM_SETTLE_MS
        var observed = control.wifiHotspotState()
        // A start issued while the previous AP is disabling can otherwise be rejected.
        while (observed == WifiHotspotState.DISABLING && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(DOWNSTREAM_POLL_MS)
            observed = control.wifiHotspotState()
        }
        if (observed == WifiHotspotState.ENABLED || observed == WifiHotspotState.ENABLING) {
            SessionLog.info("Wi-Fi hotspot already enabled or enabling; reusing downstream")
        } else {
            val (didStart, startDetail) = control.startWifiTethering()
            // The user or system may have enabled it between the state read and start request.
            check(didStart || control.isWifiHotspotStarted()) {
                "ensureDownstreamStarted: hotspot did not start ($startDetail)"
            }
        }

        awaitDownstreamReady()
    }

    private fun awaitDownstreamReady() {
        val deadline = SystemClock.elapsedRealtime() + DOWNSTREAM_SETTLE_MS
        var sawEnabling = false
        while (SystemClock.elapsedRealtime() < deadline) {
            when (control.wifiHotspotState()) {
                WifiHotspotState.ENABLED -> {
                    SessionLog.info("Wi-Fi hotspot ready")
                    return
                }
                WifiHotspotState.ENABLING -> sawEnabling = true
                WifiHotspotState.DISABLING -> throw HotspotClosedException()
                WifiHotspotState.DISABLED -> if (sawEnabling) throw HotspotClosedException()
                WifiHotspotState.FAILED -> error("热点启动失败")
                WifiHotspotState.UNKNOWN -> Unit
            }
            Thread.sleep(DOWNSTREAM_POLL_MS)
        }
        error("等待热点开启超时，请检查系统热点设置。")
    }

    private fun awaitUpstream(name: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + UPSTREAM_SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            requireHotspotOpen()
            val observation = observeUpstream()
            val observed = observation.liveInterfaceNames(name)
            if (!observation.didTimeout && observed.isNotEmpty() && observed.all { it == name }) return true
            Thread.sleep(UPSTREAM_POLL_MS)
        }
        return false
    }

    private fun observeUpstream(): UpstreamObservation = upstreamMonitor?.observe() ?: inspector.observe()

    private fun restartHotspotForHandover() {
        // Ignore our deliberate AP transition; an old callback must not stop the new session.
        val previous = hotspotWatchdog
        hotspotWatchdog = null
        previous?.stop()
        hotspotClosed = AtomicBoolean(false)
        val current = control.wifiHotspotState()
        if (current != WifiHotspotState.DISABLED && current != WifiHotspotState.FAILED) {
            check(control.stopWifiTethering()) { "热点重启失败：无法关闭热点" }
            val deadline = SystemClock.elapsedRealtime() + DOWNSTREAM_SETTLE_MS
            while (control.wifiHotspotState() == WifiHotspotState.DISABLING && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(DOWNSTREAM_POLL_MS)
            }
            check(control.wifiHotspotState() == WifiHotspotState.DISABLED) { "热点重启失败：等待热点关闭超时" }
        }
        ensureDownstreamStarted()
        startHotspotWatchdog(generation, hotspotClosed)
    }

    @Synchronized
    fun stop(): String {
        finishSession(closeHotspot = true)
        return status()
    }

    private fun finishSession(closeHotspot: Boolean) {
        generation++
        hotspotWatchdog?.stop()
        hotspotWatchdog = null
        watchdog?.stop()
        watchdog = null
        vpn.stop()
        teardown.removeShutdownHook()

        val summary = if (activeSince == 0L) null else sessionSummary()

        val downstreamProblem = if (closeHotspot) teardown.releaseDownstream() else null

        upstreamMonitor?.close()
        upstreamMonitor = null

        // No downstream is using the TUN after a normal stop, so no route-settle wait is needed.
        teardown.releaseUpstreamSelection(if (downstreamProblem == null) null else interfaceName)

        val releaseProblems = resources?.release().orEmpty()
        resources = null

        interfaceName = null
        activeSince = 0
        state = if (downstreamProblem == null) SessionState.IDLE else SessionState.ERROR
        detail = downstreamProblem ?: "stopped"

        when (downstreamProblem) {
            null -> SessionLog.info("session stopped; downstream confirmed down")
            else -> SessionLog.error("teardown: $downstreamProblem")
        }
        if (releaseProblems.isNotEmpty()) {
            SessionLog.warn("${releaseProblems.size} resource(s) not released cleanly")
        }
        summary?.let(SessionLog::info)
    }

    private fun sessionSummary(): String {
        val traffic = interfaceName?.let(InterfaceCounters::read) ?: Traffic()
        val elapsed = when (activeSince) {
            0L -> 0L
            else -> System.currentTimeMillis() - activeSince
        }

        return "session summary: active ${formatDuration(elapsed)}, " +
            "${Traffic.format(traffic.up)} up, ${Traffic.format(traffic.down)} down"
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    @Synchronized
    fun status(): String {
        if (isActive) {
            val observed = control.wifiHotspotState()
            if (observed.isStopped || hotspotClosed.get()) onHotspotStopped(observed)
        }
        return JSONObject().apply {
        put("state", state.name)
        put("detail", detail)
        put("interface", interfaceName ?: JSONObject.NULL)

        put("isVpnBound", vpn.isBound)
        put("isVpnBypassed", isVpnBypassed())

        val traffic = interfaceName?.let(InterfaceCounters::read) ?: Traffic()
        put("bytesUp", traffic.up)
        put("bytesDown", traffic.down)
        put("clientCount", if (isActive) downstream.countDevices() else 0)
        }.toString()
    }

    private fun isVpnBypassed(): Boolean {
        if (vpnMode != VpnMode.NEVER) return false
        if (!isActive) return false

        return vpn.isVpnPresent()
    }

    private fun tunAddresses() = listOf(
        buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS), TUN_PREFIX_LENGTH),
        buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS_V6), TUN_PREFIX_LENGTH_V6),
    )

    private companion object {
        const val TAG = "TetherSession"
        const val TUN_ADDRESS = "192.0.2.2"
        const val TUN_PREFIX_LENGTH = 24

        const val TUN_ADDRESS_V6 = "2001:db8::2"
        const val TUN_PREFIX_LENGTH_V6 = 64

        const val TUN_MTU = 1500
        const val AVAILABILITY_TIMEOUT_MS = 10_000
        const val UPSTREAM_SETTLE_MS = 8_000L
        const val UPSTREAM_POLL_MS = 500L

        const val DOWNSTREAM_SETTLE_MS = 20_000L
        const val DOWNSTREAM_POLL_MS = 200L
    }
}

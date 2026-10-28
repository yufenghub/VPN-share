package dev.shizzi.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class AdbDiscovery(context: Context, private val type: String, private val onPort: (Int) -> Unit) : Closeable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val callbacks = Handler(Looper.getMainLooper())
    private val pending = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var selected: String? = null
    @Volatile private var active = false
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            if (!active) runCatching { manager.stopServiceDiscovery(this) }
        }
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(info: NsdServiceInfo) {
            callbacks.post { if (active && selected == info.serviceName) { selected = null; onPort(0) } }
        }
        override fun onServiceFound(info: NsdServiceInfo) {
            callbacks.post {
                if (active && pending.none { it.serviceName == info.serviceName } && pending.size < 32) {
                    pending.add(info)
                    resolveNext()
                }
            }
        }
    }

    private fun resolveNext() {
        if (!active || resolving || pending.isEmpty()) return
        resolving = true
        val info = pending.remove()
        runCatching {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    callbacks.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    callbacks.post {
                        resolving = false
                        val address = info.host
                        val local = address != null && (address.isLoopbackAddress || runCatching {
                            Collections.list(NetworkInterface.getNetworkInterfaces()).any { network ->
                                Collections.list(network.inetAddresses).any { it.address.contentEquals(address.address) }
                            }
                        }.getOrDefault(false))
                        if (active && local && info.port in 1..65535) { selected = info.serviceName; onPort(info.port) }
                        resolveNext()
                    }
                }
            })
        }.onFailure { resolving = false; resolveNext() }
    }

    fun start() {
        if (active) return
        active = true
        callbacks.post {
            if (active) runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { active = false }
        }
    }

    override fun close() {
        if (!active) return
        active = false
        callbacks.post {
            pending.clear()
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    companion object {
        const val CONNECT = "_adb-tls-connect._tcp."
        const val PAIRING = "_adb-tls-pairing._tcp."

        fun findPort(context: Context, type: String, timeoutMillis: Long, excludedPorts: Set<Int> = emptySet()): Int {
            val latch = CountDownLatch(1)
            val result = AtomicInteger(0)
            AdbDiscovery(context, type) { port -> if (port in 1..65535 && port !in excludedPorts) { result.set(port); latch.countDown() } }.use {
                it.start()
                check(latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) { "未发现无线调试端口；请开启无线调试" }
            }
            return result.get()
        }
    }
}

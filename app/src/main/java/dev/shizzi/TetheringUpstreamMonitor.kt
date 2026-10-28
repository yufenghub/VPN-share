package dev.shizzi

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/** Uses the system's selected upstream, rather than parsing a vendor's dumpsys format. */
class TetheringUpstreamMonitor(
    private val context: Context,
    private val expectedNetwork: Network,
    private val expectedInterface: String,
) : Closeable {
    private val selected = AtomicReference<Network?>(null)
    @Volatile private var received = false
    @Volatile private var active = true
    private val manager = context.getSystemService("tethering")
    private val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
    private val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "VPN share upstream callback"
            "onUpstreamChanged" -> {
                if (active) {
                    selected.set(args?.firstOrNull() as? Network)
                    received = true
                }
                null
            }
            else -> null
        }
    }

    fun start() {
        val handler = Handler(Looper.getMainLooper())
        val executor = Executor { command -> handler.post(command) }
        manager!!.javaClass.getMethod("registerTetheringEventCallback", Executor::class.java, callbackClass)
            .invoke(manager, executor, callback)
    }

    fun observe(): UpstreamObservation? {
        if (!active || !received) return null
        val network = selected.get()
        val names = when {
            network == null -> emptyList()
            network == expectedNetwork -> listOf(expectedInterface)
            else -> listOf(context.connectivityManager().getLinkProperties(network)?.interfaceName
                ?: "network:${network.networkHandle}")
        }
        return UpstreamObservation("system upstream callback: $names", names, false, authoritative = true)
    }

    override fun close() {
        active = false
        runCatching { manager?.javaClass?.getMethod("unregisterTetheringEventCallback", callbackClass)?.invoke(manager, callback) }
    }
}

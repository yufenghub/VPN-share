package dev.shizzi.adb

import android.content.Context
import java.net.Socket
import android.os.Looper
import android.os.Process
import dev.shizzi.TetherService
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

object ShellServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(Process.myUid() == 2000) { "ADB shell required" }
        require(args.size == 5)
        HiddenApiBypass.addHiddenApiExemptions("L")
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val main = activityThread.getMethod("systemMain").invoke(null)
        val context = activityThread.getMethod("getSystemContext").invoke(main) as Context
        val packageName = args[2]
        val token = args[3]
        val ownerUid = args[4].toInt()
        val installed = context.packageManager.getApplicationInfo(packageName, 0)
        require(installed.uid == ownerUid && installed.sourceDir == args[0])
        val service = TetherService(context)
        val server = ShellTransport.listen(token)
        val requests = java.util.concurrent.ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(8))
        val lifecycle = Executors.newSingleThreadScheduledExecutor()
        fun shutdown() {
            runCatching { synchronized(service) { service.stop() } }
            runCatching { server.close() }
            requests.shutdownNow(); lifecycle.shutdownNow()
            exitProcess(0)
        }
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { service.stop() } })
        lifecycle.scheduleWithFixedDelay({
            val current = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            if (current?.uid != ownerUid || current.sourceDir != args[0]) shutdown()
        }, 5, 5, TimeUnit.SECONDS)
        Thread({
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                try {
                    requests.execute {
                        serve(socket, service, token) { lifecycle.schedule({ shutdown() }, 200, TimeUnit.MILLISECONDS) }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) { socket.close() }
            }
        }, "vpnshare-rpc").start()
        Looper.loop()
    }

    private fun serve(socket: Socket, service: TetherService, token: String, shutdown: () -> Unit) {
        socket.use {
            it.soTimeout = 3000
            val channel = runCatching { ShellTransport.accept(it, token) }.getOrNull() ?: return
            it.soTimeout = 90000
            channel.use {
            val response = runCatching {
                val request = JSONObject(channel.read())
                val op = request.getString("op")
                require(op in ShellProtocol.operations)
                val args = request.optJSONObject("args") ?: JSONObject()
                val result = if (op == "ping") service.getContractVersion().toString() else synchronized(service) {
                    when (op) {
                        "start" -> service.start(args.optBoolean("logging", true), "ALWAYS")
                        "stop" -> service.stop()
                        "status" -> service.getStatus()
                        "compatibility" -> service.checkCompatibility()
                        "logging" -> { service.setLogging(args.optBoolean("enabled")); "" }
                        "clearLog" -> { service.clearLog(); "" }
                        "probes" -> service.runProbes(args.optBoolean("tether", false), 10000)
                        "shutdown" -> { val stopped = service.stop(); shutdown(); stopped }
                        else -> error("Operation")
                    }
                }
                JSONObject().put("ok", true).put("result", result)
            }.getOrElse { failure ->
                JSONObject().put("ok", false).put("error", "${failure.javaClass.simpleName}: ${failure.message}")
            }
            runCatching { channel.write(response.toString()) }
            }
        }
    }
}

package dev.shizzi.adb

import android.os.Build
import dev.shizzi.App
import dev.shizzi.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Last attempt metadata only. No code, keys, payloads, token, SSID, or raw exception messages. */
object PairingDiagnostics {
    private val file get() = File(App.instance.noBackupFilesDir, "adb-pairing-diagnostic.txt")
    private val value = MutableStateFlow(runCatching { file.readText() }.getOrDefault(""))
    val report = value.asStateFlow()
    private var startedAt = 0L
    private var header = ""

    @Synchronized fun begin(port: Int) {
        startedAt = System.nanoTime()
        header = "version=${BuildConfig.VERSION_NAME}\nandroid=${Build.VERSION.RELEASE}; sdk=${Build.VERSION.SDK_INT}\n" +
            "device=${Build.MANUFACTURER} ${Build.MODEL}\nabi=${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "pairing_port=$port\ntime=${System.currentTimeMillis()}"
        save("result=配对中")
    }
    @Synchronized fun failed(failure: Throwable) { save(PairingFailure.technical(failure)) }
    @Synchronized fun paired() { save("result=已配对；正在连接服务") }
    @Synchronized fun connected() { save("result=已配对；服务已连接") }
    @Synchronized fun activationFailed(failure: Throwable) {
        save("result=已配对；服务连接失败\nactivation_causes=${PairingFailure.chain(failure).joinToString(" -> ") { it.javaClass.name }}")
    }
    private fun save(result: String) {
        val text = "$header\nelapsed_ms=${(System.nanoTime() - startedAt) / 1_000_000}\n$result"
        value.value = text
        runCatching { file.writeText(text) }
    }
}

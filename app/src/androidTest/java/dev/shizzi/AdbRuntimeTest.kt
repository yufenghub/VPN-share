package dev.shizzi

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import dev.shizzi.adb.*
import io.github.muntashirakon.crypto.spake2.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class AdbRuntimeInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }
    override fun onStart() {
        val results = Bundle()
        try {
            if (arguments.getString("mode") == "pair-ui") {
                pairThroughNotification()
                results.putString("stream", "PASS: notification inline reply, automatic discovery and pairing\n")
            } else if (arguments.getString("mode") == "pair") {
                pairAgainstAndroid()
                results.putString("stream", "PASS: paired with Android; helper connected\n")
            } else {
                identitySurvivesReload()
                nativeCryptoExchangesKeys()
                shellHelperAuthenticatesAndAnswers()
                results.putString("stream", "PASS: identity, native crypto, shell helper RPC/authentication\n")
            }
            finish(Activity.RESULT_OK, results)
        } catch (failure: Throwable) {
            android.util.Log.e("VPNShareRuntimeTest", "Runtime test failed", failure)
            results.putString("stream", "FAIL: " + failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }
    private fun assertTrue(value: Boolean) = check(value)
    private fun assertTrue(message: String, value: Boolean) = check(value) { message }
    private fun assertFalse(value: Boolean) = check(!value)
    private fun assertArrayEquals(a: ByteArray, b: ByteArray) = check(a.contentEquals(b))

    fun identitySurvivesReload() {
        val first = AdbIdentityStore(App.instance).loadOrCreate()
        val second = AdbIdentityStore(App.instance).loadOrCreate()
        assertArrayEquals(first.privateKey.encoded, second.privateKey.encoded)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
    }

    fun nativeCryptoExchangesKeys() {
        val a = Spake2Context(Spake2Role.Alice, byteArrayOf(1), byteArrayOf(2))
        val b = Spake2Context(Spake2Role.Bob, byteArrayOf(2), byteArrayOf(1))
        try {
            val am = a.generateMessage(byteArrayOf(7, 8, 9))
            val bm = b.generateMessage(byteArrayOf(7, 8, 9))
            assertArrayEquals(a.processMessage(bm), b.processMessage(am))
        } finally { a.destroy(); b.destroy() }
    }

    fun shellHelperAuthenticatesAndAnswers() = runBlocking {
        val context = App.instance
        AdbAccess.ensureService()
        var ready = false
        repeat(40) {
            if (!ready) {
                ready = runCatching { ShellRpc.call("ping", timeout = 1000).getString("result").toInt() == TetherService.CONTRACT_VERSION }.getOrDefault(false)
                if (!ready) Thread.sleep(250)
            }
        }
        if (!ready) ShellRpc.call("ping", timeout = 1000)
        assertTrue("Shell helper did not answer; inspect /data/local/tmp/vpnshare-UID.log", ready)
        assertTrue(AdbAccess.probe())
        assertTrue(ShellRpc.call("status").getString("result").contains("state"))
        assertTrue(parseCapabilities(ShellRpc.call("compatibility").getString("result")).isNotEmpty())
        val socket = java.net.Socket("127.0.0.1", ShellTransport.ports(ShellRpc.token()).first())
        socket.use {
            it.soTimeout = 3000
            val accepted = runCatching { ShellTransport.client(it, "b".repeat(64)) }.isSuccess
            assertFalse(accepted)
        }
    }
    fun pairAgainstAndroid() = runBlocking {
        val args = arguments
        val automation = uiAutomation
        fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
            val out = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(n: android.view.accessibility.AccessibilityNodeInfo) {
                out += n
                for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
            }
            automation.rootInActiveWindow?.let(::visit)
            return out
        }
        var port = args.getString("pairPort")?.toInt()
        var code = args.getString("pairCode")
        if (port == null || code == null) {
            nodes().firstOrNull { it.text?.toString() == "使用配对码配对设备" }?.let {
                var target = it
                while (!target.isClickable && target.parent != null) target = target.parent
                target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            repeat(20) {
                if (port == null || code == null) {
                    Thread.sleep(250)
                    val texts = nodes().mapNotNull { it.text?.toString() }
                    code = texts.firstOrNull { it.matches(Regex("[0-9]{6}")) }
                    port = texts.firstNotNullOfOrNull { Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:([0-9]{1,5})").find(it)?.groupValues?.get(1)?.toInt() }
                }
            }
        }
        check(port != null && code != null) { "Open Android's pairing-code dialog before running this test" }
        AdbAccess.pair(port!!, code!!)
        assertTrue(AdbAccess.state.value.paired)
        assertTrue(AdbAccess.state.value.connected)
    }

    fun pairThroughNotification() = runBlocking {
        val automation = uiAutomation
        fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
            val out = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                out += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            automation.rootInActiveWindow?.let(::visit)
            return out
        }
        fun click(node: android.view.accessibility.AccessibilityNodeInfo) {
            var target = node
            while (!target.isClickable && target.parent != null) target = target.parent
            check(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        nodes().firstOrNull { it.text?.toString() == "使用配对码配对设备" }?.let(::click)
        var code: String? = null
        repeat(30) {
            if (code == null) {
                Thread.sleep(200)
                code = nodes().mapNotNull { it.text?.toString() }.firstOrNull { it.matches(Regex("[0-9]{6}")) }
            }
        }
        check(code != null) { "Open Android's pairing-code dialog first" }
        runOnMainSync { AdbPairingService.start(App.instance) }
        repeat(50) { if (AdbPairingService.state.value.port == 0) Thread.sleep(200) }
        check(AdbPairingService.state.value.port != 0) { "mDNS pairing port was not discovered" }
        val notification = App.instance.getSystemService(android.app.NotificationManager::class.java)
            .activeNotifications.first { it.id == 41 }.notification
        check(notification.contentIntent == null) { "Pairing notification must not open an Activity" }
        check(notification.actions.count { !it.remoteInputs.isNullOrEmpty() } == 1)
        automation.executeShellCommand("cmd statusbar expand-notifications").close()
        var action: android.view.accessibility.AccessibilityNodeInfo? = null
        repeat(30) {
            if (action == null) {
                Thread.sleep(200)
                action = nodes().firstOrNull { it.text?.toString() == "输入配对码" && it.isClickable }
            }
        }
        check(action != null) { "Expand the pairing notification to reveal its reply action" }
        click(action!!)
        var field: android.view.accessibility.AccessibilityNodeInfo? = null
        repeat(40) {
            if (field == null) { Thread.sleep(200); field = nodes().firstOrNull { it.isEditable } }
        }
        check(field != null) { "Notification inline reply did not open" }
        val input = Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code) }
        check(field!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, input))
        val send = nodes().firstOrNull { it.contentDescription?.toString() in listOf("发送", "Send") }
        check(send != null) { "SystemUI send button not found" }
        click(send!!)
        repeat(300) { if (!AdbPairingService.state.value.finished) Thread.sleep(200) }
        check(AdbPairingService.state.value.finished) { "Inline reply pairing failed: ${AdbPairingService.state.value.status}" }
        check(AdbAccess.state.value.connected) { "Paired, but helper not connected" }
    }
}

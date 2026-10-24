package dev.shizzi.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Framed local RPC. No command execution or unbounded packet allocations. */
object ShellProtocol {
    const val MAX_FRAME = 262144
    val operations = setOf("ping", "start", "stop", "status", "compatibility", "clearLog", "logging", "probes", "shutdown")

    fun write(output: OutputStream, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME) { "RPC frame size" }
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }

    fun read(input: InputStream): String {
        val data = DataInputStream(input)
        val size = data.readInt()
        require(size in 1..MAX_FRAME) { "RPC frame size" }
        return ByteArray(size).also(data::readFully).toString(Charsets.UTF_8)
    }

    fun authorize(actualUid: Int, ownerUid: Int, actualToken: String, expectedToken: String): Boolean =
        ownerUid >= 10000 && actualUid == ownerUid && expectedToken.matches(Regex("[0-9a-f]{64}")) &&
            MessageDigest.isEqual(actualToken.toByteArray(Charsets.US_ASCII), expectedToken.toByteArray(Charsets.US_ASCII))

    fun socketName(packageName: String, token: String): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        require(token.matches(Regex("[0-9a-f]{64}")))
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$packageName.adb.$hash".also { require(it.toByteArray().size < 100) }
    }

    fun port(raw: String): Int = raw.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("端口无效")

    fun pairingCode(raw: String): String = raw.trim().also {
        require(it.matches(Regex("[0-9]{6}"))) { "配对码应为 6 位数字" }
    }

    fun quote(value: String): String {
        require(!value.contains('\u0000') && !value.contains('\n') && !value.contains('\r'))
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun launchCommand(apk: String, nativePath: String, packageName: String, token: String, ownerUid: Int): String {
        require(ownerUid >= 10000)
        socketName(packageName, token)
        require(apk.startsWith("/data/app/") && apk.endsWith(".apk"))
        return "CLASSPATH=${quote(apk)} /system/bin/setsid /system/bin/nohup /system/bin/app_process /system/bin " +
            "--nice-name=vpnshare_shell dev.shizzi.adb.ShellBootstrap " +
            listOf(apk, nativePath, packageName, token, ownerUid.toString()).joinToString(" ") { quote(it) } +
            " </dev/null >${quote("/data/local/tmp/vpnshare-$ownerUid.log")} 2>&1 &"
    }
}

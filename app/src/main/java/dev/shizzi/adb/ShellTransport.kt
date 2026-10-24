package dev.shizzi.adb

import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated, encrypted loopback RPC; works with stock Android SELinux policy. */
object ShellTransport {
    private const val MAGIC = 0x56535031
    private val random = SecureRandom()
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun secret(token: String): ByteArray {
        require(token.matches(Regex("[0-9a-f]{64}")))
        return ByteArray(32) { token.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun mac(key: ByteArray, label: String, client: ByteArray, server: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(label.toByteArray(Charsets.US_ASCII)); update(client); update(server)
        }.doFinal()

    fun ports(token: String): List<Int> {
        val hash = MessageDigest.getInstance("SHA-256").digest(secret(token))
        return (0..7).map { i -> 20000 + (((hash[2*i].toInt() and 255) shl 8) or (hash[2*i+1].toInt() and 255)) % 40000 }.distinct()
    }
    fun listen(token: String): ServerSocket {
        var last: IOException? = null
        for (port in ports(token)) {
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.bind(java.net.InetSocketAddress(loopback, port), 8)
                return server
            } catch (failure: IOException) { server.close(); last = failure }
        }
        throw IOException("本地服务端口不可用", last)
    }
    fun connect(token: String, timeout: Int): Channel {
        var last: Exception? = null
        for (port in ports(token)) {
            val socket = Socket()
            try {
                socket.connect(java.net.InetSocketAddress(loopback, port), minOf(timeout, 250))
                socket.soTimeout = minOf(timeout, 1500)
                val channel = client(socket, token)
                socket.soTimeout = timeout
                return channel
            } catch (failure: Exception) { runCatching { socket.close() }; last = failure }
        }
        throw IOException("本地服务未连接", last)
    }
    fun client(socket: Socket, token: String): Channel {
        require(socket.inetAddress.isLoopbackAddress)
        val key = secret(token)
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val client = ByteArray(32).also(random::nextBytes)
        output.writeInt(MAGIC); output.write(client); output.flush()
        check(input.readInt() == MAGIC) { "RPC version" }
        val server = ByteArray(32).also(input::readFully)
        val proof = ByteArray(32).also(input::readFully)
        check(MessageDigest.isEqual(proof, mac(key, "vpnshare-rpc-server-v1", client, server))) { "本地服务认证失败" }
        output.write(mac(key, "vpnshare-rpc-client-v1", client, server)); output.flush()
        return Channel(socket, mac(key, "vpnshare-rpc-request-v1", client, server), mac(key, "vpnshare-rpc-response-v1", client, server))
    }
    fun accept(socket: Socket, token: String): Channel {
        require(socket.inetAddress.isLoopbackAddress)
        val key = secret(token)
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        check(input.readInt() == MAGIC) { "RPC version" }
        val client = ByteArray(32).also(input::readFully)
        val server = ByteArray(32).also(random::nextBytes)
        output.writeInt(MAGIC); output.write(server)
        output.write(mac(key, "vpnshare-rpc-server-v1", client, server)); output.flush()
        val proof = ByteArray(32).also(input::readFully)
        check(MessageDigest.isEqual(proof, mac(key, "vpnshare-rpc-client-v1", client, server))) { "RPC unauthorized" }
        return Channel(socket, mac(key, "vpnshare-rpc-response-v1", client, server), mac(key, "vpnshare-rpc-request-v1", client, server))
    }

    class Channel internal constructor(private val socket: Socket, private val sendKey: ByteArray, private val receiveKey: ByteArray) : Closeable {
        private var sent = false
        private var received = false
        fun write(message: String) {
            check(!sent) { "One RPC frame per direction" }
            val bytes = message.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..ShellProtocol.MAX_FRAME)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"))
            cipher.updateAAD("vpnshare-rpc-frame-v1".toByteArray(Charsets.US_ASCII))
            val encrypted = cipher.iv + cipher.doFinal(bytes)
            require(cipher.iv.size == 12)
            val out = DataOutputStream(socket.getOutputStream())
            out.writeInt(encrypted.size); out.write(encrypted); out.flush()
            sent = true
        }
        fun read(): String {
            check(!received) { "One RPC frame per direction" }
            val input = DataInputStream(socket.getInputStream())
            val size = input.readInt()
            require(size in 29..ShellProtocol.MAX_FRAME + 28) { "RPC frame size" }
            val encrypted = ByteArray(size).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD("vpnshare-rpc-frame-v1".toByteArray(Charsets.US_ASCII))
            val message = cipher.doFinal(encrypted.copyOfRange(12, size)).toString(Charsets.UTF_8)
            received = true
            return message
        }
        override fun close() { socket.close(); sendKey.fill(0); receiveKey.fill(0) }
    }
}

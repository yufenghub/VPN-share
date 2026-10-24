package dev.shizzi

import dev.shizzi.adb.AdbIdentityCrypto
import dev.shizzi.adb.ShellProtocol
import java.io.*
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class AdbProtocolTest {
    private val token = "a".repeat(64)

    @Test fun `frames preserve Unicode and boundaries`() {
        val out = ByteArrayOutputStream()
        ShellProtocol.write(out, "配对\u0000完成")
        ShellProtocol.write(out, "next")
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals("配对\u0000完成", ShellProtocol.read(input))
        assertEquals("next", ShellProtocol.read(input))
        assertEquals(0, input.available())
    }
    @Test fun `bad sizes are rejected before reading payload`() {
        for (size in listOf(Int.MIN_VALUE, -1, 0, ShellProtocol.MAX_FRAME + 1, Int.MAX_VALUE)) {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).writeInt(size)
            assertThrows(IllegalArgumentException::class.java) { ShellProtocol.read(ByteArrayInputStream(out.toByteArray())) }
        }
    }
    @Test fun `truncated header and payload fail`() {
        assertThrows(EOFException::class.java) { ShellProtocol.read(ByteArrayInputStream(byteArrayOf(0, 0))) }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(10); write(byteArrayOf(1, 2)) }
        assertThrows(EOFException::class.java) { ShellProtocol.read(ByteArrayInputStream(out.toByteArray())) }
    }
    @Test fun `RPC requires owning app UID and exact token`() {
        assertTrue(ShellProtocol.authorize(10123, 10123, token, token))
        for (uid in listOf(0, 2000, 10124)) assertFalse(ShellProtocol.authorize(uid, 10123, token, token))
        assertFalse(ShellProtocol.authorize(10123, 10123, "b".repeat(64), token))
        assertFalse(ShellProtocol.authorize(10123, 10123, token, ""))
        assertFalse(ShellProtocol.authorize(2000, 2000, token, token))
    }
    @Test fun `ports and pairing codes reject malformed input`() {
        for (p in listOf("", "0", "65536", "-1", "12;id", "host:1234")) {
            assertThrows(IllegalArgumentException::class.java) { ShellProtocol.port(p) }
        }
        assertEquals(65535, ShellProtocol.port("65535"))
        assertEquals("012345", ShellProtocol.pairingCode("012345"))
        for (c in listOf("12345", "1234567", "12a456", "１２３４５６")) {
            assertThrows(IllegalArgumentException::class.java) { ShellProtocol.pairingCode(c) }
        }
    }
    @Test fun `launcher quotes every argument and prevents line injection`() {
        assertEquals("'a'\\''b'", ShellProtocol.quote("a'b"))
        assertEquals("'\$(id); x'", ShellProtocol.quote("\$(id); x"))
        for (s in listOf("a\nb", "a\rb", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { ShellProtocol.quote(s) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellProtocol.launchCommand("/sdcard/app.apk", "/x", "app.test", token, 10123)
        }
        assertThrows(IllegalArgumentException::class.java) { ShellProtocol.socketName("x;id", token) }
    }
    @Test fun `encrypted identity round trips without replacing key bytes`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val identity = ByteArray(2048) { (it % 251).toByte() }
        val first = AdbIdentityCrypto.seal(key, identity)
        val second = AdbIdentityCrypto.seal(key, identity)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(identity, AdbIdentityCrypto.open(key, first))
        assertArrayEquals(identity, AdbIdentityCrypto.open(key, second))
        val wrong = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(Exception::class.java) { AdbIdentityCrypto.open(wrong, first) }
        for (offset in listOf(0, 1, 12, 13, first.lastIndex)) {
            val corrupted = first.clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows(Exception::class.java) { AdbIdentityCrypto.open(key, corrupted) }
        }
    }
}

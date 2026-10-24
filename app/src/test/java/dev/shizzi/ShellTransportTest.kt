package dev.shizzi

import dev.shizzi.adb.ShellTransport
import java.io.*
import java.net.*
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class ShellTransportTest {
    private val token = "a".repeat(64)
    private fun <T> exchange(server: (Socket) -> Unit, client: (Socket) -> T): T {
        val workers = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val result = workers.submit(Callable { listener.accept().use { it.soTimeout = 3000; server(it) } })
            try {
                val value = Socket("127.0.0.1", listener.localPort).use { it.soTimeout = 3000; client(it) }
                result.get(5, TimeUnit.SECONDS)
                return value
            } finally { workers.shutdownNow() }
        }
    }
    @Test fun `authenticated channels exchange encrypted frames once`() {
        exchange({ socket ->
            ShellTransport.accept(socket, token).use {
                assertEquals("配对\u0000请求", it.read())
                it.write("响应")
                assertThrows(IllegalStateException::class.java) { it.write("again") }
            }
        }, { socket ->
            ShellTransport.client(socket, token).use {
                it.write("配对\u0000请求")
                assertEquals("响应", it.read())
                assertThrows(IllegalStateException::class.java) { it.read() }
            }
        })
    }
    @Test fun `wrong server secret is rejected before request is sent`() {
        exchange({ socket ->
            assertThrows(Exception::class.java) { ShellTransport.accept(socket, "b".repeat(64)) }
        }, { socket ->
            assertThrows(IllegalStateException::class.java) { ShellTransport.client(socket, token) }
            socket.close()
        })
    }
    @Test fun `forged client proof is rejected before RPC dispatch`() {
        exchange({ socket ->
            assertThrows(IllegalStateException::class.java) { ShellTransport.accept(socket, token) }
        }, { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            out.writeInt(0x56535031); out.write(ByteArray(32)); out.flush()
            val input = DataInputStream(socket.getInputStream())
            assertEquals(0x56535031, input.readInt())
            input.readFully(ByteArray(64))
            out.write(ByteArray(32)); out.flush()
        })
    }
    @Test fun `forged encrypted frame is rejected`() {
        exchange({ socket ->
            ShellTransport.accept(socket, token).use {
                val out = DataOutputStream(socket.getOutputStream())
                out.writeInt(29); out.write(ByteArray(29)); out.flush()
            }
        }, { socket ->
            ShellTransport.client(socket, token).use {
                assertThrows(javax.crypto.AEADBadTagException::class.java) { it.read() }
            }
        })
    }
    @Test fun `oversized encrypted frame is rejected before allocation`() {
        exchange({ socket ->
            ShellTransport.accept(socket, token).use {
                DataOutputStream(socket.getOutputStream()).apply { writeInt(Int.MAX_VALUE); flush() }
            }
        }, { socket ->
            ShellTransport.client(socket, token).use {
                assertThrows(IllegalArgumentException::class.java) { it.read() }
            }
        })
    }
}

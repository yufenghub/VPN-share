package dev.shizzi

import dev.shizzi.adb.PairingFailure
import io.github.muntashirakon.adb.PairingException
import java.io.EOFException
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class PairingFailureTest {
    private fun protocol(stage: PairingException.Stage, cause: Throwable) = PairingException(stage, "AndroidOpenSSL", "platform.socket", cause)

    @Test fun `peer EOF is not presented as an incorrect pairing code`() {
        val text = PairingFailure.describe(protocol(PairingException.Stage.PEER_RECEIVE, EOFException()))
        assertTrue(text.contains("P10"))
        assertTrue(text.contains("设备关闭连接"))
        assertFalse(text.contains("配对码错误"))
    }
    @Test fun `TLS exporter reflection cause survives wrappers`() {
        val failure = protocol(PairingException.Stage.TLS_EXPORT,
            javax.net.ssl.SSLException(InvocationTargetException(NoSuchMethodException("private API"))))
        val text = PairingFailure.describe(failure)
        assertTrue(text.contains("P03"))
        assertTrue(text.contains("系统 TLS 接口不可用"))
        assertTrue(text.contains("NoSuchMethodException"))
    }
    @Test fun `refused port and timeout stay distinct`() {
        assertTrue(PairingFailure.describe(protocol(PairingException.Stage.CONNECT, ConnectException())).contains("端口拒绝"))
        assertTrue(PairingFailure.describe(protocol(PairingException.Stage.TLS_HANDSHAKE, SocketTimeoutException())).contains("等待设备超时"))
    }
    @Test fun `diagnostics omit raw exception messages and credentials`() {
        val report = PairingFailure.technical(protocol(PairingException.Stage.PEER_DECRYPT, IOException("code=012345 key=PRIVATE_SECRET")))
        assertTrue(report.contains("P11"))
        assertTrue(report.contains("AndroidOpenSSL"))
        assertFalse(report.contains("012345"))
        assertFalse(report.contains("PRIVATE_SECRET"))
    }
    @Test fun `cyclic causes cannot hang diagnostics`() {
        val a = IOException("first")
        val b = IOException("second", a)
        a.initCause(b)
        assertEquals(2, PairingFailure.chain(a).size)
        assertTrue(PairingFailure.describe(a).contains("P00"))
    }
    @Test fun `missing certificate signature algorithm is not treated as a pairing code error`() {
        val failure = org.bouncycastle.operator.OperatorCreationException("certificate signer", java.security.NoSuchAlgorithmException())
        val text = PairingFailure.describe(failure)
        assertTrue(text.contains("P00"))
        assertTrue(text.contains("签名或加密算法不可用"))
        assertFalse(text.contains("配对码错误"))
    }
}

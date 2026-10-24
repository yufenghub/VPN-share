package dev.shizzi.adb

import io.github.muntashirakon.adb.PairingException
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

object PairingFailure {
    fun chain(failure: Throwable): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (result.size < 12) {
            val item = current ?: break
            if (result.any { it === item }) break
            result += item
            current = item.cause
        }
        return result
    }

    fun describe(failure: Throwable): String {
        val causes = chain(failure)
        val protocol = causes.filterIsInstance<PairingException>().firstOrNull()
        val phase = when (protocol?.stage) {
            PairingException.Stage.CONNECT -> "P01 连接配对端口"
            PairingException.Stage.TLS_HANDSHAKE -> "P02 TLS 握手"
            PairingException.Stage.TLS_EXPORT -> "P03 TLS 密钥导出"
            PairingException.Stage.SPAKE_INIT -> "P04 配对算法初始化"
            PairingException.Stage.SPAKE_SEND -> "P05 发送配对数据"
            PairingException.Stage.SPAKE_RECEIVE -> "P06 接收配对数据"
            PairingException.Stage.SPAKE_DERIVE -> "P07 计算配对密钥"
            PairingException.Stage.PEER_ENCRYPT -> "P08 加密身份"
            PairingException.Stage.PEER_SEND -> "P09 发送身份"
            PairingException.Stage.PEER_RECEIVE -> "P10 接收设备确认"
            PairingException.Stage.PEER_DECRYPT -> "P11 校验设备确认"
            null -> "P00 配对初始化"
        }
        val reason = when {
            causes.any { it is UnsatisfiedLinkError } -> "原生组件加载失败"
            causes.any { it is NoSuchMethodException || it is IllegalAccessException } -> "系统 TLS 接口不可用"
            causes.any { it is ConnectException } -> "端口拒绝连接"
            causes.any { it is SocketTimeoutException } -> "等待设备超时"
            causes.any { it is EOFException } -> "设备关闭连接"
            causes.any { it is SSLException } -> "TLS 异常"
            causes.any { it is SocketException } -> "连接中断"
            protocol?.stage == PairingException.Stage.PEER_DECRYPT -> "认证数据不匹配"
            causes.any { it is java.security.NoSuchAlgorithmException } -> "签名或加密算法不可用"
            causes.any { it is java.security.GeneralSecurityException } -> "密钥组件异常"
            else -> "协议或组件异常"
        }
        return "$phase：$reason（${causes.last().javaClass.simpleName}）"
    }

    fun technical(failure: Throwable): String {
        val causes = chain(failure)
        val protocol = causes.filterIsInstance<PairingException>().firstOrNull()
        return listOf(
            "result=${describe(failure)}",
            "causes=${causes.joinToString(" -> ") { it.javaClass.name }}",
            "tls_provider=${protocol?.provider ?: "not-created"}",
            "tls_socket=${protocol?.socketType ?: "not-created"}",
        ).joinToString("\n")
    }
}

package dev.shizzi

/** A zero network handle is Android's unbound/default route. Never use it for VPN sharing. */
internal fun requireVpnHandle(handle: Long) {
    check(handle != 0L) { "VPN 在启动过程中断开，请重新连接后重试。" }
}

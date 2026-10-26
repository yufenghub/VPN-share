package dev.shizzi

enum class WifiHotspotState {
    UNKNOWN, DISABLING, DISABLED, ENABLING, ENABLED, FAILED;

    val isStopped: Boolean get() = this == DISABLING || this == DISABLED || this == FAILED

    companion object {
        fun fromPlatform(value: Int?): WifiHotspotState = when (value) {
            10 -> DISABLING
            11 -> DISABLED
            12 -> ENABLING
            13 -> ENABLED
            14 -> FAILED
            else -> UNKNOWN
        }
    }
}

internal class HotspotClosedException : IllegalStateException("热点已关闭")

package dev.shizzi.adb

/** Never reuse a code submission after the system closes or replaces its pairing endpoint. */
class PairingSession(initialRevision: Long) {
    var port: Int = 0
        private set
    var revision: Long = initialRevision
        private set

    fun discovered(value: Int) {
        require(value in 0..65535)
        if (value != port) { port = value; revision++ }
    }

    fun endpoint(expectedRevision: Long, manualPort: Int = 0): Int? {
        if (expectedRevision != revision || manualPort !in 0..65535) return null
        return (if (manualPort != 0) manualPort else port).takeIf { it in 1..65535 }
    }
}

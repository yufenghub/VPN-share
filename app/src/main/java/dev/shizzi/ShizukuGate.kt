package dev.shizzi

import android.content.Intent
import dev.shizzi.adb.AdbAccess

// Legacy state names retained for the existing session controllers.
sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState
    data object PermissionRequired : ShizukuState
    data class Ready(val uid: Int, val isRoot: Boolean) : ShizukuState
}

object ShizukuGate {
    fun currentState(): ShizukuState = when {
        AdbAccess.state.value.connected -> ShizukuState.Ready(2000, false)
        AdbAccess.paired -> ShizukuState.NotRunning
        else -> ShizukuState.PermissionRequired
    }
    fun hasPermission() = AdbAccess.state.value.connected
    fun requestPermission() = remedy(currentState())
    fun remedy(state: ShizukuState) {
        if (AdbAccess.paired) AdbAccess.reconnect()
        else App.instance.startActivity(Intent(App.instance, MainActivity::class.java)
            .putExtra("adb_setup", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    fun describeUid(uid: Int) = shortUid(uid)
    fun shortUid(uid: Int) = "$uid (shell)"
    fun installedVersion(): String? = null
}

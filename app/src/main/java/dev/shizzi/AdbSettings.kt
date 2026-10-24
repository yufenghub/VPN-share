package dev.shizzi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

object AdbSettings {
    fun open(context: Context) {
        // AOSP and OEM builds differ. Try known pages, then highlight the developer option.
        val candidates = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$WirelessDebuggingActivity")),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") }),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
        }
    }
}

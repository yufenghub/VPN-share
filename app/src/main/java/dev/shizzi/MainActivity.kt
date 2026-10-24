package dev.shizzi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.shizzi.adb.AdbAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: SessionViewModel by viewModels()
    private val asked = mutableSetOf<AppPermission>()
    private var startPairingAfterPermission = false
    private var setupRequested by mutableStateOf(false)
    private val wifiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasWifi()) beginPairing()
        else android.widget.Toast.makeText(this, "首次配对请先连接 Wi-Fi", android.widget.Toast.LENGTH_LONG).show()
    }
    private val notificationSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!AdbPairingService.needsNotificationSettings(this)) prepareWifi()
        else android.widget.Toast.makeText(this, "请开启配对码通知及横幅/悬浮通知", android.widget.Toast.LENGTH_LONG).show()
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshPermissions()
        if (startPairingAfterPermission) {
            startPairingAfterPermission = false
            if (it) preparePairing() else android.widget.Toast.makeText(this, "配对需要通知权限", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupRequested = intent.getBooleanExtra("adb_setup", false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(3000)
                    val access = AdbAccess.state.value
                    if (!access.busy && (access.paired || access.connected)) {
                        if (!AdbAccess.probe() && AdbAccess.paired) AdbAccess.reconnect()
                    }
                }
            }
        }
        setContent {
            val settings by viewModel.settings.collectAsState()
            if (settings != null) {
                val session by viewModel.state.collectAsState()
                val permissions by viewModel.permissionState.collectAsState()
                val compatibility by viewModel.compatibilityState.collectAsState()
                val dark = isSystemInDarkTheme()
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
                    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !dark
                }
                ShareDashboard(session, permissions, compatibility, viewModel::toggle,
                    viewModel::cancel, ::grantPermission, viewModel::checkCompatibility,
                    setupRequested, { setupRequested = false }, ::requestPairing)
            }
        }
    }

    private fun grantPermission(permission: AppPermission) {
        val name = permission.manifestName
        if (name == null || (permission in asked && !shouldShowRequestPermissionRationale(name))) {
            viewModel.openPermissionSettings(permission)
        } else { asked += permission; permissionLauncher.launch(name) }
    }

    private fun requestPairing() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            startPairingAfterPermission = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else preparePairing()
    }

    private fun hasWifi(): Boolean {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        return connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun preparePairing() {
        if (AdbPairingService.needsNotificationSettings(this)) {
            android.widget.Toast.makeText(this, "请开启配对码通知及横幅/悬浮通知", android.widget.Toast.LENGTH_LONG).show()
            runCatching { notificationSettingsLauncher.launch(AdbPairingService.notificationSettings(this)) }
        } else prepareWifi()
    }

    private fun prepareWifi() {
        if (hasWifi()) beginPairing()
        else {
            android.widget.Toast.makeText(this, "首次配对请先连接 Wi-Fi", android.widget.Toast.LENGTH_LONG).show()
            runCatching { wifiLauncher.launch(Intent(Settings.Panel.ACTION_WIFI)) }
                .recoverCatching { wifiLauncher.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
    }

    private fun beginPairing() {
        AdbPairingService.start(this)
        AdbSettings.open(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("adb_setup", false)) setupRequested = true
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
        AdbAccess.reconnect()
    }
}

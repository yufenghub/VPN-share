package dev.shizzi

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal val WhiteTheme = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color.White, onPrimaryContainer = Color.Black,
    secondary = Color.Black, onSecondary = Color.White,
    secondaryContainer = Color.White, onSecondaryContainer = Color.Black,
    tertiary = Color.Black, onTertiary = Color.White,
    tertiaryContainer = Color.White, onTertiaryContainer = Color.Black,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color.White, onSurfaceVariant = Color.Black,
    surfaceTint = Color.Black, outline = Color.Black, outlineVariant = Color.Black,
    error = Color.Black, onError = Color.White,
    errorContainer = Color.White, onErrorContainer = Color.Black,
)
internal val BlackTheme = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color.Black, onPrimaryContainer = Color.White,
    secondary = Color.White, onSecondary = Color.Black,
    secondaryContainer = Color.Black, onSecondaryContainer = Color.White,
    tertiary = Color.White, onTertiary = Color.Black,
    tertiaryContainer = Color.Black, onTertiaryContainer = Color.White,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color.Black, onSurfaceVariant = Color.White,
    surfaceTint = Color.White, outline = Color.White, outlineVariant = Color.White,
    error = Color.White, onError = Color.Black,
    errorContainer = Color.Black, onErrorContainer = Color.White,
)

@Composable
fun ShareDashboard(
    state: SessionUiState,
    permissions: List<PermissionStatus>,
    compatibility: CompatibilityState,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    onGrant: (AppPermission) -> Unit,
    onCheck: () -> Unit,
    setupRequested: Boolean,
    onSetupHandled: () -> Unit,
    onPairingNotification: () -> Unit,
) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) BlackTheme else WhiteTheme) {
        val context = LocalContext.current
        val colors = MaterialTheme.colorScheme
        val adb by dev.shizzi.adb.AdbAccess.state.collectAsState()
        var adbSetup by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(setupRequested) { if (setupRequested) { adbSetup = true; onSetupHandled() } }
        if (adbSetup) AdbSetupDialog(onDismiss = { adbSetup = false }, onNotificationPairing = onPairingNotification)
        val onShizuku: () -> Unit = { adbSetup = true }
        val hasVpn by produceState(false, context) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            while (isActive) {
                value = runCatching {
                    cm.getNetworkCapabilities(cm.activeNetwork)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }.getOrDefault(false)
                delay(1500)
            }
        }
        val ready = state.shizukuState is ShizukuState.Ready
        val connected = state.status == UiStatus.CONNECTED
        val busy = state.status == UiStatus.LOADING || state.isBusy
        var details by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(ready) { if (ready) onCheck() }

        fun openSettings(action: String) {
            runCatching { context.startActivity(Intent(action)) }
                .recoverCatching { context.startActivity(Intent(AndroidSettings.ACTION_SETTINGS)) }
        }

        val shizukuStatus = when {
            adb.busy -> "连接中"
            ready -> "已连接"
            adb.paired -> "已配对"
            else -> "未配对"
        }
        val action = when {
            state.isStopping -> "停止中"
            connected -> "停止共享"
            busy -> "取消启动"
            !ready -> "ADB 设置"
            !hasVpn -> "VPN 设置"
            !compatibility.isCompatible -> "检查系统接口"
            else -> "启动共享"
        }

        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WifiTethering, null, Modifier.padding(horizontal = 16.dp).size(28.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    }

                    Surface(
                        onClick = {
                            when {
                                state.isStopping -> Unit
                                connected -> onToggle()
                                busy -> onCancel()
                                !ready -> onShizuku()
                                !hasVpn -> openSettings(AndroidSettings.ACTION_VPN_SETTINGS)
                                !compatibility.isCompatible -> onCheck()
                                else -> onToggle()
                            }
                        },
                        color = colors.onBackground, contentColor = colors.background,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = colors.background, strokeWidth = 2.dp)
                            else Icon(if (connected) Icons.Outlined.CheckCircle else Icons.Outlined.PowerSettingsNew,
                                null, Modifier.size(32.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(when {
                                    state.isStopping -> "停止中"
                                    connected -> "运行中"
                                    busy -> "启动中"
                                    state.status == UiStatus.ERROR -> "错误"
                                    else -> "已停止"
                                }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                                Text(action, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    if (connected) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Metric("设备", state.clientCount.toString(), Modifier.weight(1f))
                            Metric("下载", Traffic.format(state.traffic.down), Modifier.weight(1f))
                            Metric("上传", Traffic.format(state.traffic.up), Modifier.weight(1f))
                        }
                    }
                    ActionCard(Icons.Outlined.VpnKey, "VPN", if (hasVpn) "已连接" else "未连接") {
                        openSettings(AndroidSettings.ACTION_VPN_SETTINGS)
                    }
                    ActionCard(Icons.Outlined.Wifi, "热点", "设置") {
                        openSettings("android.settings.TETHER_SETTINGS")
                    }
                    ActionCard(Icons.Outlined.VerifiedUser, "ADB", shizukuStatus,
                        onClick = onShizuku)
                    val checking = compatibility is CompatibilityState.Checking
                    ActionCard(Icons.Outlined.Tune, "系统接口", when {
                        checking -> "检查中"
                        compatibility.isCompatible -> "可用"
                        compatibility is CompatibilityState.Failed -> "检查失败"
                        compatibility is CompatibilityState.Complete || compatibility.isOnFixPath -> "不支持"
                        else -> "未检查"
                    }, onClick = if (ready && !checking) onCheck else null)

                    permissions.filter { !it.isGranted }.forEach { permission ->
                        ActionCard(
                            if (permission.permission == AppPermission.NOTIFICATIONS) Icons.Outlined.NotificationsNone else Icons.Outlined.BatteryFull,
                            permission.permission.title, "未授权",
                        ) { onGrant(permission.permission) }
                    }
                    if (state.lastError.isNotBlank()) {
                        Text(shareError(state.lastError), modifier = Modifier.padding(horizontal = 16.dp))
                        TextButton(onClick = { details = !details }) { Text(if (details) "收起" else "错误详情") }
                        if (details) SelectionContainer {
                            Text(state.lastError, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, status: String, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(icon, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
            if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
        }
    }
    if (onClick == null) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            color = colors.background, contentColor = colors.onBackground,
            border = BorderStroke(1.dp, colors.onBackground), content = content)
    } else {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            color = colors.background, contentColor = colors.onBackground,
            border = BorderStroke(1.dp, colors.onBackground), content = content)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun shareError(raw: String): String = when {
    raw.contains("VPN", ignoreCase = true) -> "VPN 连接失败"
    raw.contains("Permission", ignoreCase = true) || raw.contains("ADB", ignoreCase = true) -> "ADB 连接不可用"
    raw.contains("hotspot", ignoreCase = true) || raw.contains("Downstream", ignoreCase = true) -> "热点操作失败"
    raw.contains("verifyUpstream", ignoreCase = true) -> "热点出口切换失败"
    else -> "共享失败"
}

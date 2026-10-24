package dev.shizzi

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.shizzi.adb.AdbAccess
import dev.shizzi.adb.ShellProtocol
import kotlinx.coroutines.launch

@Composable
fun AdbSetupDialog(onDismiss: () -> Unit, onNotificationPairing: () -> Unit) {
    val context = LocalContext.current
    val state by AdbAccess.state.collectAsState()
    val pairing by AdbPairingService.state.collectAsState()
    val diagnostic by dev.shizzi.adb.PairingDiagnostics.report.collectAsState()
    val scope = rememberCoroutineScope()
    var pairPort by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var inputError by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val busy = state.busy || submitting
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ADB") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when { state.connected -> "已连接"; busy -> "连接中"; state.paired -> "已配对"; else -> "未配对" })
                if (!state.connected) {
                    Button(onClick = {
                        if (state.paired) AdbAccess.reconnect() else onNotificationPairing()
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.paired) "连接" else "配对") }
                    if (!state.paired || pairing.active) Text("打开系统配对码窗口 → 在通知内点“输入配对码” → 输入后发送")
                    if (pairing.active) OutlinedButton(onClick = {
                        AdbSettings.open(context)
                    }, modifier = Modifier.fillMaxWidth()) { Text("返回无线调试") }
                    if (state.paired) OutlinedButton(onClick = { AdbSettings.open(context) }, modifier = Modifier.fillMaxWidth()) { Text("无线调试设置") }
                }
                TextButton(onClick = { manual = !manual }) { Text(if (manual) "收起" else "高级设置") }
                if (manual) {
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(AdbPairingService.notificationSettings(context)) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("配对通知设置") }
                    OutlinedButton(onClick = onNotificationPairing, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("重新配对") }
                    Text("手动配对时，分屏保留系统配对码窗口")
                    OutlinedTextField(pairPort, { pairPort = it }, label = { Text("配对端口") }, singleLine = true,
                        enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(code, { code = it }, label = { Text("6 位配对码") }, singleLine = true,
                        enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        scope.launch {
                            inputError = ""
                            submitting = true
                            try { AdbAccess.pair(ShellProtocol.port(pairPort), ShellProtocol.pairingCode(code)); code = "" }
                            catch (failure: Exception) { inputError = state.error.ifBlank { failure.message ?: "配对失败" } }
                            finally { submitting = false }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("配对") }
                    OutlinedTextField(connectPort, { connectPort = it }, label = { Text("连接端口（留空自动发现）") },
                        singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        inputError = ""
                        runCatching { connectPort.takeIf { it.isNotBlank() }?.let(ShellProtocol::port) }
                            .onSuccess { AdbAccess.reconnect(it) }.onFailure { inputError = "端口无效" }
                    }, enabled = !busy && state.paired, modifier = Modifier.fillMaxWidth()) { Text("连接") }
                    if (diagnostic.isNotBlank()) TextButton(onClick = {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("配对诊断", diagnostic))
                        android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("复制配对诊断") }
                }
                val error = inputError.ifBlank { state.error }
                if (error.isNotBlank()) Text(error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    )
}

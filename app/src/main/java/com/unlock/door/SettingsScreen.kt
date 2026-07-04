package com.unlock.door

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unlock.door.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 设置页面 — 填入门锁参数并持久化保存
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {

    var deviceId by remember { mutableStateOf(SettingsManager.deviceId.toString()) }
    var projectId by remember { mutableStateOf(SettingsManager.projectId.toString()) }
    var credentialId by remember { mutableStateOf(SettingsManager.credentialId.toString()) }
    var chainKey by remember { mutableStateOf(SettingsManager.chainKey) }
    var deviceMac by remember { mutableStateOf(SettingsManager.deviceMac) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 拦截系统返回键 / 侧滑 → 回到主界面
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                "门锁设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // ── 表单 ──
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 在线登录（自动获取参数） ──
            var loginPhone by remember { mutableStateOf(SettingsManager.loginPhone) }
            var loginPassword by remember { mutableStateOf(SettingsManager.loginPassword) }
            var loginStatus by remember { mutableStateOf(
                if (SettingsManager.isLoggedIn()) "已登录" else ""
            ) }
            var loginLoading by remember { mutableStateOf(false) }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "登录你的住理生活账号自动获取门锁参数",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = loginPhone,
                        onValueChange = {
                            loginPhone = it
                            SettingsManager.loginPhone = it.trim()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("手机号", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Walnut, cursorColor = Walnut,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = loginPassword,
                        onValueChange = {
                            loginPassword = it
                            SettingsManager.loginPassword = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("密码", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Walnut, cursorColor = Walnut,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Button(
                            onClick = {
                                if (loginLoading) return@Button
                                loginLoading = true
                                loginStatus = ""
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    try {
                                        val result = ServerApi.login(loginPhone.trim(), loginPassword)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            if (result.success) {
                                                loginStatus = if (SettingsManager.showLogs)
                                                    "已登录\n${result.projectName}"
                                                else
                                                    "登录成功"
                                            } else {
                                                loginStatus = if (SettingsManager.showLogs)
                                                    result.error
                                                else
                                                    "登录失败"
                                            }
                                            loginLoading = false
                                        }
                                    } catch (e: Exception) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            loginStatus = "崩溃: ${e.message}"
                                            loginLoading = false
                                        }
                                    }
                                }
                            },
                            enabled = loginPhone.isNotBlank() && loginPassword.isNotBlank() && !loginLoading,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Moss,
                                disabledContainerColor = Moss.copy(alpha = 0.4f),
                            ),
                        ) {
                            if (loginLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = PureWhite, strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (loginLoading) "登录中..." else "登录",
                                fontSize = 14.sp,
                            )
                        }

                        if (loginStatus.isNotBlank()) {
                            Text(
                                loginStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (loginStatus.startsWith("已登录")) Moss else Rust,
                                lineHeight = 16.sp,
                            )
                        }
                    }

                    // 登录成功后显示自动获取按钮
                    if (SettingsManager.isLoggedIn()) {
                        Spacer(Modifier.height(8.dp))
                        var fetching by remember { mutableStateOf(false) }
                        var fetchResult by remember { mutableStateOf("") }

                        Button(
                            onClick = {
                                fetching = true
                                fetchResult = ""
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    try {
                                        val addr = ServerApi.fetchAddress()
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            if (addr.deviceId == 0) {
                                                fetchResult = "未找到门锁设备"
                                                fetching = false
                                                return@withContext
                                            }
                                            deviceId = addr.deviceId.toString()
                                            deviceMac = addr.mac
                                            credentialId = addr.credentialId.toString()
                                            projectId = SettingsManager.serverAppId
                                            chainKey = addr.chainKey
                                            SettingsManager.deviceId = addr.deviceId
                                            SettingsManager.deviceMac = addr.mac
                                            SettingsManager.credentialId = addr.credentialId
                                            SettingsManager.projectId = SettingsManager.serverAppId.toIntOrNull() ?: 0
                                            SettingsManager.chainKey = addr.chainKey
                                            fetchResult = "已获取: XN-${addr.deviceId} (${addr.buildingName} ${addr.roomName})"
                                            fetching = false
                                        }
                                    } catch (e: Exception) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            fetchResult = "获取失败: ${e.message}"
                                            fetching = false
                                        }
                                    }
                                }
                            },
                            enabled = !fetching,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Walnut),
                        ) {
                            if (fetching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = PureWhite, strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (fetching) "获取中..." else "自动获取参数",
                                fontSize = 14.sp,
                            )
                        }

                        if (fetchResult.isNotBlank()) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    fetchResult,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (fetchResult.startsWith("已获取")) Moss else Rust,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
            }

            // 手动参数区提示
            Text(
                "在上方填写手机号和密码登录后自动获取，或手动填入下方参数",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )

            // 设备 ID
            ParamField(
                label = "设备 ID (Device ID)",
                value = deviceId,
                onValue = { deviceId = it },
                hint = "门锁蓝牙名称 XN- 后面的数字",
                keyboardType = KeyboardType.Number,
            )

            // 项目 ID
            ParamField(
                label = "项目 ID (Project ID)",
                value = projectId,
                onValue = { projectId = it },
                hint = "宿舍楼项目编号",
                keyboardType = KeyboardType.Number,
            )

            // 凭证 ID
            ParamField(
                label = "凭证 ID",
                value = credentialId,
                onValue = { credentialId = it },
                hint = "数字凭证编号",
                keyboardType = KeyboardType.Number,
            )

            // BLE MAC
            ParamField(
                label = "门锁 MAC 地址（可选）",
                value = deviceMac,
                onValue = { deviceMac = it },
                hint = "格式 AA:BB:CC:DD:EE:FF，留空则自动扫描",
                keyboardType = KeyboardType.Ascii,
            )

            // chainKey（多行）
            Text(
                "Chain Key（32 字节 hex）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            OutlinedTextField(
                value = chainKey,
                onValueChange = { chainKey = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                minLines = 2,
                maxLines = 3,
                placeholder = {
                    Text("32 字节 hex 字符串，从 doorLockAddress[].credential 提取",
                        fontSize = 12.sp)
                },
                singleLine = false,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Walnut,
                    cursorColor = Walnut,
                ),
            )

            Spacer(Modifier.height(4.dp))

            // ── 日志开关 ──
            var showLogs by remember { mutableStateOf(SettingsManager.showLogs) }
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "运行日志",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "出现 Bug 时开启，将日志截图发给开发者协助排查",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            lineHeight = 16.sp,
                        )
                    }
                    Switch(
                        checked = showLogs,
                        onCheckedChange = {
                            showLogs = it
                            SettingsManager.showLogs = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureWhite,
                            checkedTrackColor = Walnut,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 保存按钮
            Button(
                onClick = {
                    SettingsManager.deviceId = deviceId.toIntOrNull() ?: SettingsManager.deviceId
                    SettingsManager.projectId = projectId.toIntOrNull() ?: SettingsManager.projectId
                    SettingsManager.credentialId = credentialId.toIntOrNull() ?: SettingsManager.credentialId
                    SettingsManager.chainKey = chainKey.trim()
                    SettingsManager.deviceMac = deviceMac.trim()
                    scope.launch {
                        snackbarHostState.showSnackbar("已保存  ·  BLE: XN-${SettingsManager.deviceId}")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Walnut,
                ),
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("保存", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))
            }
        }

        // Snackbar 固定在底部
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ParamField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint, fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Walnut,
                cursorColor = Walnut,
            ),
        )
    }
}

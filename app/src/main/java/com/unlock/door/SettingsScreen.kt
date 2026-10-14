package com.unlock.door

import android.content.ComponentName
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
fun SettingsScreen(
    onBack: () -> Unit,
    onWriteNfcTag: () -> Unit,
) {

    var deviceId by remember { mutableStateOf(SettingsManager.deviceId.toString()) }
    var projectId by remember { mutableStateOf(SettingsManager.projectId.toString()) }
    var credentialId by remember { mutableStateOf(SettingsManager.credentialId.toString()) }
    var chainKey by remember { mutableStateOf(SettingsManager.chainKey) }
    var deviceMac by remember { mutableStateOf(SettingsManager.deviceMac) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

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
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, SeuLine),
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
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SeuGreen, cursorColor = SeuGreen,
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
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SeuGreen, cursorColor = SeuGreen,
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
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SeuGreenDeep,
                                disabledContainerColor = SeuGreenDeep.copy(alpha = 0.4f),
                            ),
                        ) {
                            if (loginLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = SeuSurface, strokeWidth = 2.dp,
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
                                color = if (loginStatus.startsWith("已登录")) SeuGreenDeep else SeuError,
                                lineHeight = 16.sp,
                            )
                        }
                    }

                    // ── 支付宝一键登录（本变体占用了官方包名，直接复用官方 App 的支付宝授权） ──
                    Spacer(Modifier.height(10.dp))
                    var alipayLoading by remember { mutableStateOf(false) }
                    var alipayStatus by remember { mutableStateOf("") }

                    OutlinedButton(
                        onClick = {
                            if (alipayLoading) return@OutlinedButton
                            val act = ctx as? android.app.Activity
                            if (act == null) {
                                alipayStatus = "无法获取当前界面"
                                return@OutlinedButton
                            }
                            alipayLoading = true
                            alipayStatus = ""
                            scope.launch {
                                try {
                                    val authInfo = ServerApi.fetchAlipayAuthInfo()
                                    val code = AlipayLogin.auth(act, authInfo)
                                    val result = ServerApi.loginByAlipayAuthCode(code)
                                    if (result.success) {
                                        loginPhone = SettingsManager.loginPhone
                                        loginStatus = if (SettingsManager.showLogs)
                                            "已登录\n${result.projectName}" else "登录成功"
                                    } else {
                                        alipayStatus = result.error
                                    }
                                } catch (e: Exception) {
                                    alipayStatus = e.message ?: e.javaClass.simpleName
                                }
                                alipayLoading = false
                            }
                        },
                        enabled = !alipayLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, SeuGreen),
                    ) {
                        if (alipayLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = SeuGreen, strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (alipayLoading) "唤起支付宝中…" else "支付宝一键登录",
                            fontSize = 14.sp,
                            color = SeuGreenDeep,
                        )
                    }
                    if (alipayStatus.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            alipayStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = SeuError,
                            lineHeight = 16.sp,
                        )
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
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(containerColor = SeuGreen),
                        ) {
                            if (fetching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = SeuSurface, strokeWidth = 2.dp,
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
                                    color = if (fetchResult.startsWith("已获取")) SeuGreenDeep else SeuError,
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
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SeuGreen,
                    cursorColor = SeuGreen,
                ),
            )

            Spacer(Modifier.height(4.dp))

            // ── 日志开关 ──
            var showLogs by remember { mutableStateOf(SettingsManager.showLogs) }
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, SeuLine),
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
                            checkedThumbColor = SeuSurface,
                            checkedTrackColor = SeuGreen,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── NFC 开锁 ──
            val context = LocalContext.current
            val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
            var showHceLog by remember { mutableStateOf(false) }

            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, SeuLine),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "NFC 开锁",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    // ① 磁标签开门 (即写即用)
                    Text(
                        "① 磁标签开门",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "把写好指令的 NFC 标签贴在门边，手机碰一下自动开门。",
                        style = MaterialTheme.typography.labelSmall,
                        color = SeuInkSoft,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onWriteNfcTag,
                        enabled = nfcAdapter != null,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SeuGreenFill,
                            disabledContainerColor = SeuGreenFill.copy(alpha = 0.4f),
                        ),
                    ) {
                        Text("写入 NFC 标签", fontSize = 14.sp)
                    }

                    Spacer(Modifier.height(18.dp))

                    // ② 手机碰锁感应区开门 (官方 0xB1 协议已破解并移植) —— App 前台时走读卡器模式
                    Text(
                        "② 手机碰锁感应区开门",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "App 打开时，手机直接碰门锁感应区即可开门。",
                        style = MaterialTheme.typography.labelSmall,
                        color = SeuInkSoft,
                        lineHeight = 18.sp,
                    )
                    PrincipleNote(
                        title = "协议流程",
                        body = "读锁标签 NDEF 取设备 ID（无 NDEF 则用已配置设备 ID）\n" +
                            "→ 写入 0xB1[RC4(projectId + chainKey)]\n" +
                            "→ 锁返回新密钥（自动轮换）",
                    )
                    TextButton(
                        onClick = { showHceLog = true },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        Text("查看 NFC 通讯日志", fontSize = 13.sp, color = SeuGreen)
                    }

                    Spacer(Modifier.height(18.dp))

                    // ③ 碰门锁本体直接开门 (TECH_DISCOVERED 后台分发, 先用 UID 判定是否本门锁)
                    var lockAsTag by remember { mutableStateOf(SettingsManager.nfcLockAsTagEnabled) }
                    Text(
                        "③ 碰门锁本体直接开门",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "不必打开 App：解锁屏幕后碰一下门锁感应区就能开门。",
                        style = MaterialTheme.typography.labelSmall,
                        color = SeuInkSoft,
                        lineHeight = 18.sp,
                    )
                    PrincipleNote(
                        body = "门锁感应区没有可供匹配的 NDEF 内容，系统只能按「技术类型」分发 —— " +
                            "而 NfcA 几乎是所有 Type A 卡的基础技术，校园卡 / 公交卡 / 银行卡都会一起命中。\n" +
                            "本 App 收到分发后再自己筛一道：UID 必须以 ASCII「XN-」开头，" +
                            "由它反推的 deviceId 必须与上面配置的一致。任一条不满足就立刻静默退出，" +
                            "连闪一下都没有 —— 不会去动别人的锁。\n" +
                            "关掉下面这个开关，系统就完全不再分发（比静默退出更彻底）。",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "启用（会响应所有 NfcA 卡）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = lockAsTag,
                            onCheckedChange = { v ->
                                lockAsTag = v
                                SettingsManager.nfcLockAsTagEnabled = v
                                val state = if (v) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                runCatching {
                                    context.packageManager.setComponentEnabledSetting(
                                        ComponentName(context, NfcTagDispatchActivity::class.java),
                                        state, PackageManager.DONT_KILL_APP,
                                    )
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SeuSurface,
                                checkedTrackColor = SeuGreen,
                            ),
                        )
                    }

                    if (nfcAdapter == null) {
                        Text(
                            "本机不支持 NFC，NFC 相关功能不可用，BLE 开门不受影响",
                            style = MaterialTheme.typography.labelSmall,
                            color = SeuError,
                            lineHeight = 16.sp,
                        )
                    } else if (!nfcAdapter.isEnabled) {
                        Text(
                            "NFC 已关闭，请到系统设置开启",
                            style = MaterialTheme.typography.labelSmall,
                            color = SeuError,
                        )
                    }
                }
            }

            // NFC 通讯日志弹窗（每 0.5 秒自动刷新，碰锁时开着即可实时看到指令）
            if (showHceLog) {
                var logs by remember { mutableStateOf(NfcDoorLockManager.snapshotLog()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        logs = NfcDoorLockManager.snapshotLog()
                        kotlinx.coroutines.delay(500)
                    }
                }
                val clipboard = LocalClipboardManager.current
                AlertDialog(
                    onDismissRequest = { showHceLog = false },
                    title = { Text("NFC 通讯日志", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            if (logs.isEmpty()) {
                                Text(
                                    "暂无日志（每 0.5 秒自动刷新）。\n\n" +
                                        "实验步骤：保持本 App 停留在本页面，手机贴住门锁感应区 3~5 秒。\n" +
                                        "正常应出现『═══ 标签发现 ═══』和 0xB1 指令记录；\n" +
                                        "若始终无日志，请确认 NFC 已开启且手机碰的是锁的 NFC 感应区。",
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 20.sp,
                                )
                            } else {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Column {
                                        logs.takeLast(30).forEach { line ->
                                            Text(
                                                line,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 16.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row {
                            if (logs.isNotEmpty()) {
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                                }) { Text("复制全部", fontSize = 13.sp) }
                                Spacer(Modifier.width(4.dp))
                            }
                            TextButton(onClick = { showHceLog = false }) { Text("关闭") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { NfcDoorLockManager.clearLog() }) { Text("清空") }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large,
                )
            }

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
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SeuGreen,
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
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SeuGreen,
                cursorColor = SeuGreen,
            ),
        )
    }
}

// ── 可折叠的「原理」说明 ──

/**
 * 设置页不该是说明书。
 *
 * 长段解释默认收起 —— 折叠态只占一行，把版面还给真正要动手填的输入项；
 * 想知道原理的人点一下展开，看完再收起来。
 */
@Composable
private fun PrincipleNote(
    body: String,
    title: String = "原理与注意事项",
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = SeuGreenDeep,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = SeuGreenDeep,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                body,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp, end = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = SeuInkSoft,
                lineHeight = 19.sp,
            )
        }
    }
}

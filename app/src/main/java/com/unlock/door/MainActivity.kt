package com.unlock.door

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.unlock.door.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var bleManager: BleUnlockManager? = null
    private val logMessages = mutableStateListOf<String>()

    private val requestBluetoothEnable =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val allGranted = perms.values.all { it }
            if (!allGranted) {
                Toast.makeText(this, "需要蓝牙权限才能开门", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SettingsManager.init(this)

        bleManager = BleUnlockManager(this) { msg ->
            logMessages.add(msg)
            if (logMessages.size > 100) logMessages.removeAt(0)
        }

        setContent {
            SimpleUnlockTheme {
                var showSettings by remember { mutableStateOf(false) }
                var showDisclaimer by remember { mutableStateOf(!SettingsManager.agreedDisclaimer) }

                // 首次启动：免责弹窗
                if (showDisclaimer) {
                    DisclaimerDialog(
                        onAgree = {
                            SettingsManager.agreedDisclaimer = true
                            showDisclaimer = false
                        },
                        onDisagree = { finish() },
                    )
                }

                // 主界面 ↔ 设置 侧滑切换
                AnimatedContent(
                    targetState = showSettings,
                    transitionSpec = {
                        if (targetState) {
                            // 进入设置：从右滑入
                            slideInHorizontally(tween(300)) { it } togetherWith
                                slideOutHorizontally(tween(300)) { -it }
                        } else {
                            // 回到主界面：从左滑入
                            slideInHorizontally(tween(300)) { -it } togetherWith
                                slideOutHorizontally(tween(300)) { it }
                        }
                    },
                ) { inSettings ->
                    if (inSettings) {
                        SettingsScreen(onBack = { showSettings = false })
                    } else {
                        val showLogs = SettingsManager.showLogs
                        UnlockScreen(
                            onUnlock = { doUnlock() },
                            onOpenSettings = { showSettings = true },
                            state = bleManager?.state?.collectAsState()?.value
                                ?: BleUnlockManager.State.IDLE,
                            statusText = bleManager?.statusText?.collectAsState()?.value ?: "就绪",
                            logs = if (showLogs) logMessages.toList() else emptyList(),
                        )
                    }
                }
            }
        }

        checkPermissions()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            "door_unlock", "共享开门", android.app.NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(android.app.NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val needRequest = perms.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest) requestPermissions.launch(perms.toTypedArray())
    }

    private fun doUnlock() {
        val manager = bleManager ?: return
        if (!manager.hasPermissions()) { checkPermissions(); return }
        if (!manager.isBluetoothEnabled()) {
            requestBluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        logMessages.clear()
        lifecycleScope.launch(Dispatchers.Main) {
            try { withContext(Dispatchers.IO) { manager.unlock() } }
            catch (_: Exception) {}
            // 成功后 3 秒自动恢复初始状态
            delay(3000)
            manager.cancel()
        }
    }
}

// ─── 主界面 ───

@Composable
fun UnlockScreen(
    onUnlock: () -> Unit,
    onOpenSettings: () -> Unit,
    state: BleUnlockManager.State,
    statusText: String,
    logs: List<String>,
) {
    val isProcessing = state == BleUnlockManager.State.SCANNING ||
            state == BleUnlockManager.State.CONNECTING ||
            state == BleUnlockManager.State.UNLOCKING

    val isSuccess = state == BleUnlockManager.State.SUCCESS
    val isFailed  = state == BleUnlockManager.State.FAILED

    var pressScale by remember { mutableStateOf(1f) }
    val buttonScale by animateFloatAsState(
        targetValue = if (isProcessing) 0.96f else pressScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
    )

    var showLogs by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // ── 顶部：设备信息 + 设置齿轮 ──
            TopInfo(onOpenSettings = onOpenSettings)

            Spacer(Modifier.weight(0.6f))

            // ── 中央：开门按钮 ──
            UnlockButton(
                state = state,
                isProcessing = isProcessing,
                isSuccess = isSuccess,
                isFailed = isFailed,
                scale = buttonScale,
                onPressStart = { pressScale = 0.95f },
                onPressEnd = { pressScale = 1f },
                onClick = {
                    if (!isProcessing) {
                        pressScale = 0.92f
                        onUnlock()
                    }
                },
            )

            Spacer(Modifier.height(28.dp))

            // ── 状态文字 ──
            StatusText(state = state, text = statusText)

            Spacer(Modifier.weight(0.4f))

            // ── 底部：日志 ──
            if (logs.isNotEmpty()) {
                LogSection(logs = logs, expanded = showLogs, onToggle = { showLogs = !showLogs })
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── 顶部信息 ──

@Composable
private fun TopInfo(onOpenSettings: () -> Unit) {
    val deviceName = SettingsManager.bleName()
    val mac = SettingsManager.deviceMac.ifBlank { "自动扫描" }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 占位，保持文字居中
            Spacer(Modifier.width(48.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    deviceName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            mac,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 设置齿轮
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = WarmGray,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

// ── 开门按钮 ──

@Composable
private fun UnlockButton(
    state: BleUnlockManager.State,
    isProcessing: Boolean,
    isSuccess: Boolean,
    isFailed: Boolean,
    scale: Float,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onClick: () -> Unit,
) {
    val ringTransition = rememberInfiniteTransition()
    val ringRotation by ringTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
    )
    val ringSweep by ringTransition.animateFloat(
        initialValue = 60f, targetValue = 300f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isSuccess -> Moss
            isFailed  -> Rust
            else      -> Walnut
        },
        animationSpec = tween(400),
    )

    val contentColor = PureWhite

    val shakeOffset by animateFloatAsState(
        targetValue = if (isFailed) 1f else 0f,
        animationSpec = if (isFailed) repeatable(
            iterations = 3, animation = tween(80), repeatMode = RepeatMode.Reverse,
        ) else tween(0),
    )
    val shakePx = with(LocalDensity.current) { (shakeOffset * 8f).dp.toPx() }

    Box(
        modifier = Modifier
            .graphicsLayer { translationX = if (isFailed) shakePx else 0f }
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        if (isProcessing) {
            Canvas(modifier = Modifier.size(230.dp)) {
                val strokeW = 3.dp.toPx()
                drawArc(
                    color = Walnut.copy(alpha = 0.25f),
                    startAngle = ringRotation,
                    sweepAngle = ringSweep,
                    useCenter = false,
                    topLeft = Offset(strokeW / 2, strokeW / 2),
                    size = Size(size.width - strokeW, size.height - strokeW),
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )
            }
        }

        if (isSuccess) {
            Canvas(modifier = Modifier.size(230.dp)) {
                drawCircle(
                    color = Moss.copy(alpha = 0.12f),
                    radius = size.minDimension / 2,
                )
            }
        }

        Surface(
            onClick = onClick,
            modifier = Modifier.size(170.dp),
            shape = CircleShape,
            color = buttonColor,
            contentColor = contentColor,
            shadowElevation = 4.dp,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                    },
                ) { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (s) {
                        BleUnlockManager.State.SUCCESS -> {
                            Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("已开", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                        BleUnlockManager.State.FAILED -> {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("重试", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                        BleUnlockManager.State.SCANNING,
                        BleUnlockManager.State.CONNECTING,
                        BleUnlockManager.State.UNLOCKING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = contentColor,
                                strokeWidth = 3.dp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                when (s) {
                                    BleUnlockManager.State.SCANNING -> "扫描中"
                                    BleUnlockManager.State.CONNECTING -> "连接中"
                                    else -> "开门中"
                                },
                                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        else -> {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("开门", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            }
        }
    }
}

// ── 状态文字 ──

@Composable
private fun StatusText(state: BleUnlockManager.State, text: String) {
    val color = when (state) {
        BleUnlockManager.State.SUCCESS -> Moss
        BleUnlockManager.State.FAILED  -> Rust
        BleUnlockManager.State.UNLOCKING,
        BleUnlockManager.State.SCANNING,
        BleUnlockManager.State.CONNECTING -> Walnut
        else -> WarmGray
    }

    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
    ) { t ->
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                t,
                style = MaterialTheme.typography.bodyLarge,
                color = color,
            fontWeight = if (state != BleUnlockManager.State.IDLE) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
        }
    }
}

// ── 日志区域 ──

@Composable
private fun LogSection(logs: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "运行日志",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        logs.takeLast(15).forEach { msg ->
                            Text(
                                msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 首次启动免责弹窗 ──

@Composable
private fun DisclaimerDialog(onAgree: () -> Unit, onDisagree: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 不可点外部关闭 */ },
        title = {
            Text(
                "免责声明",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                "本软件仅供学习研究蓝牙低功耗（BLE）通信协议使用，不会收集、上传或分享任何个人信息，" +
                    "所有数据仅存储在设备本地。\n\n" +
                    "在使用本软件获取门锁凭证及开门前，请确认你对该门锁设备拥有合法使用权（如已获授权、为合法租户等）。" +
                    "请勿将本软件用于非法侵入他人住所或任何违法违规用途。\n\n" +
                    "因使用本软件产生的一切后果由使用者自行承担。点击「同意」即表示你已知晓并接受以上条款。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp,
            )
        },
        confirmButton = {
            Button(onClick = onAgree, colors = ButtonDefaults.buttonColors(containerColor = Walnut)) {
                Text("同意")
            }
        },
        dismissButton = {
            TextButton(onClick = onDisagree) {
                Text("不同意", color = Rust)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
    )
}

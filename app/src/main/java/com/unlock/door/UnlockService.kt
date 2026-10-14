package com.unlock.door

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class UnlockService : Service() {

    private var scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var unlockManager: BleUnlockManager? = null
    private var isRunning = false
    private var resetRunnable: Runnable? = null
    private var requestId = 0  // 用于区分新旧请求

    companion object {
        private const val NOTIFICATION_ID = 10086
        private const val CHANNEL_ID = "door_unlock"
        private const val CHANNEL_NAME = "共享开门"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        unlockManager = BleUnlockManager(this) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 自动恢复期间合并重复点击，保留当前重试次数和连接。
        if (isRunning) return START_NOT_STICKY
        // 取消上一次的延迟重置
        resetRunnable?.let {
            Handler(Looper.getMainLooper()).removeCallbacks(it)
            resetRunnable = null
        }

        // 递增 requestId，旧协程的 finish() 会发现 requestId 已变而跳过
        requestId++
        val currentRequestId = requestId

        // 创建新的 BleUnlockManager，旧协程持有的旧实例不会影响新协程
        unlockManager?.disconnect()
        unlockManager = BleUnlockManager(this) { }

        isRunning = true

        // 立即更新 Widget 为 UNLOCKING 状态
        val widgetIntent = Intent(DoorLockWidgetProvider.ACTION_UPDATE_UI).apply {
            setClass(this@UnlockService, DoorLockWidgetProvider::class.java)
            putExtra("state", DoorLockWidgetProvider.STATE_UNLOCKING)
        }
        sendBroadcast(widgetIntent)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("开门中...")
            .setContentText("正在连接门锁")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val currentManager = unlockManager
        scope.launch {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    finish(false, "请先开启蓝牙", currentRequestId)
                    return@launch
                }
                if (currentManager?.hasPermissions() != true) {
                    finish(false, "请先打开 App 授权 BLE 权限", currentRequestId)
                    return@launch
                }

                coroutineScope {
                    val updates = launch {
                        currentManager.statusText.collect { message ->
                            if (currentRequestId == requestId &&
                                currentManager.state.value != BleUnlockManager.State.SUCCESS &&
                                currentManager.state.value != BleUnlockManager.State.FAILED) {
                                updateNotification("开门中...", message)
                                val progressIntent = Intent(DoorLockWidgetProvider.ACTION_UPDATE_UI).apply {
                                    setClass(this@UnlockService, DoorLockWidgetProvider::class.java)
                                    putExtra("state", DoorLockWidgetProvider.STATE_UNLOCKING)
                                    putExtra("message", message)
                                }
                                sendBroadcast(progressIntent)
                            }
                        }
                    }
                    try { currentManager.unlock() }
                    finally { updates.cancelAndJoin() }
                }
                finish(true, currentManager.statusText.value, currentRequestId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(false, e.message ?: "开门失败", currentRequestId)
            }
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun finish(success: Boolean, message: String, requestId: Int = -1) {
        // 如果 requestId 不匹配，说明是旧请求的 finish()，跳过
        if (requestId != this.requestId) {
            return
        }
        isRunning = false

        // 结果通知
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(if (success) "开门成功 🔓" else "开门失败")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)

        // 更新 widget
        val widgetIntent = Intent(DoorLockWidgetProvider.ACTION_UPDATE_UI).apply {
            setClass(this@UnlockService, DoorLockWidgetProvider::class.java)
            putExtra("state",
                if (success) DoorLockWidgetProvider.STATE_SUCCESS
                else DoorLockWidgetProvider.STATE_FAILED
            )
            putExtra("message", if (success) "已开门 ✓" else "开门失败，请查看通知")
        }
        sendBroadcast(widgetIntent)

        // 失败状态保留；服务立即退出，下一次点击可以开始新的尝试。
        if (!success) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        val delayMs = 3000L
        val runnable = Runnable {
            // 再次检查 requestId
            if (this.requestId == requestId) {
                val resetIntent = Intent(DoorLockWidgetProvider.ACTION_UPDATE_UI).apply {
                    setClass(this@UnlockService, DoorLockWidgetProvider::class.java)
                    putExtra("state", DoorLockWidgetProvider.STATE_IDLE)
                }
                sendBroadcast(resetIntent)
                stopForeground(STOP_FOREGROUND_DETACH)
                isRunning = false
                resetRunnable = null
                stopSelf()
            }
        }
        resetRunnable = runnable
        Handler(Looper.getMainLooper()).postDelayed(runnable, delayMs)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        resetRunnable?.let {
            Handler(Looper.getMainLooper()).removeCallbacks(it)
        }
        isRunning = false
        scope.cancel()
        unlockManager?.disconnect()
        super.onDestroy()
    }
}

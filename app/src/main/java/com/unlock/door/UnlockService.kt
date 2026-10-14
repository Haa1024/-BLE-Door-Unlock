package com.unlock.door

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class UnlockService : Service() {

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var unlockManager: BleUnlockManager? = null
    private var isRunning = false
    private var resetRunnable: Runnable? = null
    private var requestId = 0  // 用于区分新旧请求

    /** 调用方（碰锁浮层）传入的结果回调，为空表示无人等待结果 */
    private var callback: ResultReceiver? = null

    /** 静默模式：结果已由屏幕上的浮层呈现，通知栏不再提醒 */
    private var silent = false

    companion object {
        private const val NOTIFICATION_ID = 10086
        private const val CHANNEL_ID = "door_unlock"
        private const val CHANNEL_NAME = "共享开门"

        /** 结果回调（ResultReceiver）与静默开关的 Intent key */
        const val EXTRA_CALLBACK = "unlock_callback"
        const val EXTRA_SILENT = "unlock_silent"

        const val RESULT_SUCCESS = 0
        const val RESULT_FAILURE = 1
        const val RESULT_KEY_MESSAGE = "unlock_message"
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        unlockManager = BleUnlockManager(this) { }
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 接上调用方的结果回调：碰锁浮层会传一个 ResultReceiver 来收开门结果
        callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_CALLBACK, ResultReceiver::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_CALLBACK) as? ResultReceiver
        }
        silent = intent?.getBooleanExtra(EXTRA_SILENT, false) ?: false

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

                updateNotification("开门中...", "正在发送凭证")
                currentManager.unlock()
                finish(true, "门已打开 🔓", currentRequestId)
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

        // 先把结果回给等待中的调用方（碰锁浮层），屏幕上的那一下反馈由它渲染
        val payload = Bundle().apply { putString(RESULT_KEY_MESSAGE, message) }
        runCatching {
            callback?.send(if (success) RESULT_SUCCESS else RESULT_FAILURE, payload)
        }
        callback = null

        // 结果通知
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(if (success) "开门成功 🔓" else "开门失败")
            .setContentText(message)
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (silent) {
            // 浮层已经在屏幕上给出结果，通知栏这边静音，避免二次打扰
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOnlyAlertOnce(true)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, builder.build())

        // 更新 widget
        val widgetIntent = Intent(DoorLockWidgetProvider.ACTION_UPDATE_UI).apply {
            setClass(this@UnlockService, DoorLockWidgetProvider::class.java)
            putExtra("state",
                if (success) DoorLockWidgetProvider.STATE_SUCCESS
                else DoorLockWidgetProvider.STATE_FAILED
            )
        }
        sendBroadcast(widgetIntent)

        // 成功: 3 秒后还原; 失败: 1 秒后还原 (避免打断重试)
        val delayMs = if (success) 3000L else 1000L
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

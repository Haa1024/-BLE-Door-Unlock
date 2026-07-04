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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var unlockManager: BleUnlockManager? = null
    private var isRunning = false

    companion object {
        private const val NOTIFICATION_ID = 10086
        private const val CHANNEL_ID = "door_unlock"
        private const val CHANNEL_NAME = "共享开门"
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        unlockManager = BleUnlockManager(this) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_NOT_STICKY
        isRunning = true

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

        scope.launch {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    finish(false, "请先开启蓝牙")
                    return@launch
                }
                if (unlockManager?.hasPermissions() != true) {
                    finish(false, "请先打开 App 授权 BLE 权限")
                    return@launch
                }

                updateNotification("开门中...", "正在发送凭证")
                unlockManager?.unlock()
                finish(true, "门已打开 🔓")
            } catch (e: Exception) {
                finish(false, e.message ?: "开门失败")
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

    private fun finish(success: Boolean, message: String) {
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
        }
        sendBroadcast(widgetIntent)

        // 3 秒还原
        Handler(Looper.getMainLooper()).postDelayed({
            val resetIntent = Intent(DoorLockWidgetProvider.ACTION_UPDATE_UI).apply {
                setClass(this@UnlockService, DoorLockWidgetProvider::class.java)
                putExtra("state", DoorLockWidgetProvider.STATE_IDLE)
            }
            sendBroadcast(resetIntent)
            stopForeground(STOP_FOREGROUND_DETACH)
            isRunning = false
            stopSelf()
        }, 3000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        unlockManager?.disconnect()
        super.onDestroy()
    }
}

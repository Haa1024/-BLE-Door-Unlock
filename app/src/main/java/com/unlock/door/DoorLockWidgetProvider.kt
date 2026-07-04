package com.unlock.door

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/**
 * 桌面小组件: 一键开门 — 用户可自由拖放调整大小
 *
 * 微 (1x1 小屏) → 仅圆形按钮
 * 紧 (1x1 大屏) → 圆形 + 状态文字
 * 宽 (2x1+)      → 圆形 + 文字 + 设备名
 * 大 (2x2+)      → 全部显示 + 更大间距
 */
class DoorLockWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_UNLOCK = "com.unlock.door.ACTION_UNLOCK"
        const val ACTION_UPDATE_UI = "com.unlock.door.ACTION_UPDATE_WIDGET"

        const val STATE_IDLE = 0
        const val STATE_UNLOCKING = 1
        const val STATE_SUCCESS = 2
        const val STATE_FAILED = 3

        fun updateWidget(context: Context, state: Int = STATE_IDLE) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, DoorLockWidgetProvider::class.java)
            )
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val views = buildViews(context, options, state)
                manager.updateAppWidget(id, views)
            }
        }

        private fun buildViews(context: Context, options: Bundle, state: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_door_lock)

            // 获取小组件实际尺寸
            val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)

            // 四级自适应:
            // 微: 1x1 小屏 (< 80dp)   → 仅圆形
            // 紧: 1x1 大屏 (80-140dp) → 圆形 + 文字
            // 宽: 2x1+    (140-240dp) → 圆形 + 文字 + 设备名
            // 大: 2x2+    (>240dp)    → 全部, 更宽敞
            val isMicro    = w in 1..79
            val isCompact  = w in 80..139
            val isNormal   = w in 140..239
            val isExpanded = w >= 240 || h >= 200

            // ─── 圆形区域 ───
            val (circleBg, iconText, showProgress, showIcon) = when (state) {
                STATE_UNLOCKING ->
                    Tuple4(R.drawable.widget_circle_idle, "🔒", true, false)
                STATE_SUCCESS ->
                    Tuple4(R.drawable.widget_circle_success, "🔓", false, true)
                STATE_FAILED ->
                    Tuple4(R.drawable.widget_circle_failed, "⚠️", false, true)
                else ->
                    Tuple4(R.drawable.widget_circle_idle, "🔒", false, true)
            }

            views.setImageViewResource(R.id.widget_circle_bg, circleBg)
            views.setTextViewText(R.id.widget_icon, iconText)

            // 切换图标和加载圈
            views.setViewVisibility(R.id.widget_icon,
                if (showIcon) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_progress,
                if (showProgress) View.VISIBLE else View.GONE)

            // ─── 文字区域 ───
            val statusText = when (state) {
                STATE_UNLOCKING -> "开门中..."
                STATE_SUCCESS   -> "已开门 ✓"
                STATE_FAILED    -> "失败, 重试"
                else            -> "点击开门"
            }
            val statusColor = when (state) {
                STATE_SUCCESS   -> "#4A7C59"
                STATE_FAILED    -> "#C44B4B"
                else            -> "#1A1814"
            }

            // 根据尺寸层级控制文字可见性
            when {
                isMicro -> {
                    // 仅圆形按钮，隐藏所有文字
                    views.setViewVisibility(R.id.widget_status, View.GONE)
                    views.setViewVisibility(R.id.widget_device, View.GONE)
                }
                isCompact -> {
                    // 圆形 + 状态文字
                    views.setTextViewText(R.id.widget_status, statusText)
                    views.setTextColor(R.id.widget_status,
                        android.graphics.Color.parseColor(statusColor))
                    views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_device, View.GONE)
                }
                isNormal -> {
                    // 圆形 + 状态文字 + 设备名
                    views.setTextViewText(R.id.widget_status, statusText)
                    views.setTextColor(R.id.widget_status,
                        android.graphics.Color.parseColor(statusColor))
                    views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                    views.setTextViewText(R.id.widget_device, "XN-${SettingsManager.deviceId}")
                    views.setViewVisibility(R.id.widget_device, View.VISIBLE)
                }
                isExpanded -> {
                    // 全部显示
                    views.setTextViewText(R.id.widget_status, statusText)
                    views.setTextColor(R.id.widget_status,
                        android.graphics.Color.parseColor(statusColor))
                    views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                    views.setTextViewText(R.id.widget_device, "XN-${SettingsManager.deviceId}")
                    views.setViewVisibility(R.id.widget_device, View.VISIBLE)
                }
                else -> {
                    // 首次放置，默认显示文字
                    views.setTextViewText(R.id.widget_status, statusText)
                    views.setTextColor(R.id.widget_status,
                        android.graphics.Color.parseColor(statusColor))
                    views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_device, View.GONE)
                }
            }

            // ─── 点击事件 ───
            val intent = Intent(context, DoorLockWidgetProvider::class.java).apply {
                action = ACTION_UNLOCK
            }
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)

            return views
        }

        // 简易四元组 (避免额外依赖)
        data class Tuple4<T1, T2, T3, T4>(val a: T1, val b: T2, val c: T3, val d: T4)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            manager.updateAppWidget(id, buildViews(context, options, STATE_IDLE))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, options: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, id, options)
        manager.updateAppWidget(id, buildViews(context, options, STATE_IDLE))
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_UNLOCK -> {
                updateWidget(context, STATE_UNLOCKING)
                val serviceIntent = Intent(context, UnlockService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            ACTION_UPDATE_UI -> {
                val state = intent.getIntExtra("state", STATE_IDLE)
                updateWidget(context, state)
            }
            else -> super.onReceive(context, intent)
        }
    }
}

package com.unlock.door

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.unlock.door.ui.StatusGlyphView

/**
 * NFC 触发的开门入口 —— 承接两类标签，确认命中才弹底部浮层，否则完全无感地静默退出。
 *
 * **入口一：我们自己写入的磁标签**（NDEF 内容形如 `unlockdoor://open`）
 *   标签里的 URI 本身就是入场券，直接开门，不再校验 UID。开锁走蓝牙。
 *
 * **入口二：门锁本体的感应区**（系统按 TECH_DISCOVERED 分发）
 *   这一层系统只能过滤到"技术类型"，而 NfcA 几乎是所有 Type A 卡的基础技术，
 *   校园卡 / 公交卡 / 别人家门禁都会一起命中。所以自己补一道闸门，任一条不满足即静默退出：
 *     1. UID 必须以 ASCII "XN-" 开头（住理门锁的固定前缀）
 *     2. 从 UID[3..6] 小端读出后右移 8 位，得到 deviceId
 *     3. 反推的 deviceId 必须与设置页配置一致 —— 否则拒绝，不冒险去开别人的锁
 *
 *   过闸之后优先用 **NFC 指令**开门：把 Tag 交给 [NfcDoorLockManager]，由它读 NDEF 取
 *   deviceId、构造 0xB1 帧、经 NfcA.transceive 直发门锁感应区 —— 这正是官方 App
 *   「碰一下」的原理，不用配对、不用开蓝牙。三次都没拿到合法响应时才回退蓝牙。
 *
 * ⚠️ **必须先解锁屏幕**。Android 10 起限制了后台 / 锁屏状态下的 Activity 启动，系统不会
 *    在锁屏时把 NFC 分发投过来 —— 实测（Android 16）确认：锁屏碰锁毫无反应，解锁后碰锁
 *    才会走到这里。所以任何文案都不要再说"锁屏也能开门"。
 *    下面 configureWindow 里的 setShowWhenLocked / setTurnScreenOn 只是保险：万一某台设备
 *    真在锁屏下投递了分发，也能正常显示并点亮屏幕。
 *
 * **开关**：设置页可以关掉「碰门锁本体直接开门」，关闭时本 Activity 被动态禁用，系统从
 * 分发层就不再唤起它 —— 碰校园卡 / 公交卡也不会把本 App 叫起来。
 *
 * 通过之后才展示浮层：先是"正在开门…"的旋转环，收到结果后切换成东大绿对勾（逐笔描绘）/
 * 砖红叉号，停留片刻自己收起。
 *
 * 窗口特性：全屏透明、**完全触摸穿透**——用户在任何界面下碰标签，
 * 这张卡片都不会抢走输入，看一眼就消失。
 */
class NfcTagDispatchActivity : Activity() {

    companion object {
        private const val TAG = "NfcTagDispatch"

        /** 写进磁标签的自定义 scheme */
        private const val UNLOCK_SCHEME = "unlockdoor"

        /** 开门超时兜底：超时后按失败收尾，避免卡片一直挂在屏幕上。NFC 三次重试 + 可能的蓝牙回退，给足余量 */
        private const val UNLOCK_TIMEOUT_MS = 18_000L

        /** 成功/失败结果在屏幕上停留的时长 */
        private const val SUCCESS_HOLD_MS = 1200L
        private const val FAILURE_HOLD_MS = 2200L

        /**
         * NFC 命令连续 3 次未获得合法响应时，是否回退蓝牙。
         *
         * 3 次是 [NfcDoorLockManager] 里 [NfcDoorLockManager.TRANSPORT_RETRIES] 的固定行为，
         * 这里只决定「3 次都失败之后」怎么办：
         *   true  → 转蓝牙再试一轮（碰一下总归能开，代价是多花几秒）
         *   false → 直接报错（纯 NFC，失败就失败）
         * 真机确认 NFC 在后台分发的时序下每次都通之后，改成 false 即为纯 NFC。
         */
        private const val BLE_FALLBACK_ON_NFC_FAILURE = true
    }

    private var card: View? = null
    private var glyph: StatusGlyphView? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    /** 卡片是否已经滑入过（避免重复入场动画） */
    private var cardShown = false

    /** 已进入收尾流程（结果已出，正在等待滑出）—— 期间忽略新的碰触 */
    private var concluding = false

    // ─────────────────────────── 生命周期 ───────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SettingsManager.isInitialized()) SettingsManager.init(applicationContext)

        configureWindow()
        setContentView(R.layout.activity_nfc_card)

        card = findViewById(R.id.unlock_card)
        glyph = findViewById(R.id.status_glyph)
        titleView = findViewById(R.id.card_title)
        subtitleView = findViewById(R.id.card_subtitle)

        // 初始把卡片挪到屏幕外，等确认是门锁才让它滑进来
        card?.apply {
            alpha = 0f
            translationY = dp(130f)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (concluding) {
            Log.d(TAG, "上一次开门尚未收尾，忽略本次碰触")
            return
        }
        handleIntent(intent)
    }

    override fun onDestroy() {
        disarmTimeout()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * 窗口配置：
     *  - 锁屏之上可见 + 点亮屏幕 —— **兜底，正常情况下不会生效**。
     *    平台在锁屏时就不会把 NFC 分发投过来（Android 10+ 限制后台启动 Activity），
     *    走到这里时屏幕必定已解锁。留着是为了万一某台设备真的投了分发，
     *    不至于显示不出来、也不至于黑着屏看不清浮层
     *  - FLAG_NOT_FOCUSABLE 不带输入焦点，绝不弹输入法
     *  - FLAG_NOT_TOUCHABLE 整块浮层触摸穿透，用户该刷视频刷视频、该刷卡刷卡
     *    （必须与 NOT_FOCUSABLE 同时设置才生效）
     */
    private fun configureWindow() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        // 系统栏必须显式"透出去"，否则 Theme.Translucent 的默认行为会把状态栏涂黑
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    // ─────────────────────────── 分发判断 ───────────────────────────

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) {
            finish()
            return
        }

        // 功能开关：用户可在设置页关闭，关闭后本 Activity 会被动态禁用（压根收不到分发）
        if (!SettingsManager.nfcLockAsTagEnabled) {
            Log.d(TAG, "NFC 开门已关闭，忽略")
            finish()
            return
        }

        // 入口一：自己写的磁标签 —— URI 就是入场券，直接开门
        if (action == NfcAdapter.ACTION_NDEF_DISCOVERED &&
            UNLOCK_SCHEME.equals(intent.data?.scheme, ignoreCase = true)
        ) {
            Log.d(TAG, "磁标签命中 (${intent.data}) → 直接开门")
            if (!SettingsManager.isConfigured()) {
                showFailure("还没有配置凭证，请先打开 App 填写")
                return
            }
            showLoading("正在连接 ${SettingsManager.bleName()}")
            startUnlockService()
            return
        }

        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag == null) {
            Log.d(TAG, "intent 中无 Tag，忽略")
            finish()
            return
        }

        val uid = tag.id ?: ByteArray(0)
        val uidHex = uid.joinToString("") { "%02X".format(it) }

        val deviceId = NfcDoorLockManager.deviceIdFromUid(uid)
        if (deviceId == null) {
            // 不是住理门锁标签（校园卡 / 公交卡 / 其他门禁…）→ 静默退出，用户无感
            Log.d(TAG, "非门锁标签 (UID=$uidHex)，忽略")
            finish()
            return
        }

        // 以下才是有反馈的分支：已经确认是住理门锁
        val configured = SettingsManager.deviceId
        if (deviceId != configured) {
            Log.d(TAG, "门锁标签 UID=$uidHex → deviceId=$deviceId，与配置 $configured 不一致")
            showFailure("识别到门锁 $deviceId，与你配置的 $configured 不符")
            return
        }
        if (!SettingsManager.isConfigured()) {
            Log.d(TAG, "未配置凭证，忽略")
            showFailure("还没有配置凭证，请先打开 App 填写")
            return
        }

        Log.d(TAG, "命中门锁标签 UID=$uidHex → deviceId=$deviceId，走 NFC 指令开门")
        showLoading("正在向门锁发送开锁指令…")
        unlockByNfc(tag)
    }

    /**
     * 用 NFC 指令开门 —— 手机作为读卡器，对门锁感应区直接收发 0xB1 帧。
     *
     * 与蓝牙的区别：NFC 靠感应区取电，不需要配对、不需要开蓝牙，指令往返就是几次
     * 毫秒级的 transceive。这正是官方 App「碰一下」的原理，也是本变体要走的路。
     *
     * 指令内部已带 3 次重试（`NfcDoorLockManager.TRANSPORT_RETRIES`），所以这里
     * 收到失败意味着「3 次都没拿到合法 0xB1 响应」，才轮到下面的判断：
     *   · 成功     → 浮层转绿对勾（chainKey 轮换已在 [NfcDoorLockManager] 内落盘）
     *   · 其余失败 → 由 [BLE_FALLBACK_ON_NFC_FAILURE] 决定是回退蓝牙还是直接报错
     */
    private fun unlockByNfc(tag: Tag) {
        NfcDoorLockManager.handleTag(tag) { ok, msg ->
            if (isFinishing || isDestroyed || concluding) return@handleTag

            when {
                ok -> showSuccess()

                BLE_FALLBACK_ON_NFC_FAILURE -> {
                    Log.w(TAG, "NFC 开门未成功（$msg），回退蓝牙重试")
                    showLoading("NFC 未成功，转蓝牙重试…")
                    startUnlockService()
                }

                else -> showFailure(msg.ifBlank { "请重试" })
            }
        }
    }

    private fun startUnlockService() {
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val msg = resultData?.getString(UnlockService.RESULT_KEY_MESSAGE).orEmpty()
                if (resultCode == UnlockService.RESULT_SUCCESS) {
                    showSuccess()
                } else {
                    showFailure(msg.ifBlank { "请重试" })
                }
            }
        }

        val svc = Intent(this, UnlockService::class.java).apply {
            putExtra(UnlockService.EXTRA_CALLBACK, receiver)
            // 结果由浮层呈现，通知栏那边静音，不做二次打扰
            putExtra(UnlockService.EXTRA_SILENT, true)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动开门服务失败: ${e.message}")
            showFailure("启动开门服务失败")
        }
    }

    // ─────────────────────────── 浮层三态 ───────────────────────────

    private fun showLoading(message: String) {
        titleView?.text = "正在开门…"
        subtitleView?.text = message
        glyph?.setLoading()
        slideIn()
        armTimeout()
    }

    private fun showSuccess() {
        if (concluding) return
        concluding = true
        disarmTimeout()

        titleView?.text = "门已打开"
        subtitleView?.text = SettingsManager.bleName()
        glyph?.playSuccess {
            handler.postDelayed({ slideOut() }, SUCCESS_HOLD_MS)
        }
    }

    private fun showFailure(message: String) {
        if (concluding) return
        concluding = true
        disarmTimeout()

        titleView?.text = "开门失败"
        subtitleView?.text = message
        glyph?.playFailed()
        slideIn()
        handler.postDelayed({ slideOut() }, FAILURE_HOLD_MS)
    }

    // ─────────────────────────── 出入场动画 ───────────────────────────

    private fun slideIn() {
        val c = card ?: return
        if (cardShown) return
        cardShown = true
        c.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(340)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()
    }

    private fun slideOut() {
        val c = card
        if (c == null) {
            softFinish()
            return
        }
        c.animate()
            .translationY(dp(150f))
            .alpha(0f)
            .setDuration(260)
            .setInterpolator(AccelerateInterpolator(1.6f))
            .withEndAction { softFinish() }
            .start()
    }

    private fun softFinish() {
        if (isFinishing || isDestroyed) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private fun armTimeout() {
        disarmTimeout()
        val r = Runnable {
            Log.w(TAG, "开门超时未收到结果")
            showFailure("开门超时，请重试")
        }
        timeoutRunnable = r
        handler.postDelayed(r, UNLOCK_TIMEOUT_MS)
    }

    private fun disarmTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

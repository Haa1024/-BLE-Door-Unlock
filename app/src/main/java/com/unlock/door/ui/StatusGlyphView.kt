package com.unlock.door.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * 开门状态图标（碰锁浮层左侧那个 46dp 的圆）。
 *
 * 三种形态：
 *  - [Mode.LOADING]  胡桃色缺口圆环持续旋转，表示"正在连接"
 *  - [Mode.SUCCESS]  苔绿圆底弹入 → 白色对勾逐笔描绘 → 一圈涟漪扩散
 *  - [Mode.FAILED]   锈红圆底弹入 → 白色叉号逐笔描绘
 *
 * 对勾/叉号用 [PathMeasure.getSegment] 做"笔画逐步画出"的效果，
 * 也就是 Apple Pay 付款成功那个对勾的观感。
 */
class StatusGlyphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { LOADING, SUCCESS, FAILED }

    // 与 Compose 主题 (ui/theme/Theme.kt) 同一套东大绿配色
    //   LOADING 用中性灰绿 —— 过程不该抢戏，绿色留给「成功了」那一下
    private val loadingTint  = Color.parseColor("#9AA094")
    private val seuGreenDeep = Color.parseColor("#47701F") // 东大绿 #4C7D2C 的深一档，圆底更有分量
    private val seuRed       = Color.parseColor("#C0392B")
    private val white        = Color.WHITE

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = white
    }

    /** 图形最多两笔（对勾 1 笔 / 叉号 2 笔） */
    private val glyphPaths = arrayOf(Path(), Path())
    private val glyphMeasures = arrayOf(PathMeasure(), PathMeasure())
    private val glyphLengths = FloatArray(2)
    private val segPath = Path()

    private var mode = Mode.LOADING

    // ── 动画进度 ──
    private var spin = 0f            // LOADING: 环的起始角
    private var discScale = 0f       // 圆底弹出比例
    private var glyphProgress = 0f   // 0..1 笔画描绘进度
    private var rippleScale = 0.9f
    private var rippleAlpha = 0f
    private var builtBox = -1f

    private var spinAnim: ValueAnimator? = null
    private val pulseAnims = mutableListOf<ValueAnimator>()

    // ─────────────────────────── 对外接口 ───────────────────────────

    fun setLoading() {
        cancelAll()
        mode = Mode.LOADING
        resetResultState()
        invalidate()
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spin = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 播放成功动画；[onFinished] 在最后一笔描完时回调（可空） */
    fun playSuccess(onFinished: (() -> Unit)? = null) = playResult(Mode.SUCCESS, onFinished)

    /** 播放失败动画；[onFinished] 在最后一笔描完时回调（可空） */
    fun playFailed(onFinished: (() -> Unit)? = null) = playResult(Mode.FAILED, onFinished)

    // ─────────────────────────── 状态与动画 ───────────────────────────

    private fun resetResultState() {
        discScale = 0f
        glyphProgress = 0f
        rippleScale = 0.9f
        rippleAlpha = 0f
        builtBox = -1f
    }

    private fun playResult(target: Mode, onFinished: (() -> Unit)?) {
        cancelAll()
        mode = target
        resetResultState()
        invalidate()

        // 1) 圆底带一点回弹地弹出
        val pop = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.7f)
            addUpdateListener {
                discScale = it.animatedValue as Float
                invalidate()
            }
        }

        // 2) 笔画逐步描绘
        val draw = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            startDelay = 130
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                glyphProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished?.invoke()
                }
            })
        }

        // 3) 涟漪扩散
        val ripple = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            startDelay = 150
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                rippleScale = 0.9f + t * 0.9f
                rippleAlpha = (1f - t) * 0.85f
                invalidate()
            }
        }

        listOf(pop, draw, ripple).forEach {
            pulseAnims.add(it)
            it.start()
        }
    }

    private fun cancelAll() {
        spinAnim?.cancel()
        spinAnim = null
        pulseAnims.forEach { it.cancel() }
        pulseAnims.clear()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }

    // ─────────────────────────── 绘制 ───────────────────────────

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        if (mode == Mode.LOADING) {
            arcPaint.color = loadingTint
            arcPaint.strokeWidth = s * 0.088f
            val r = s * 0.35f
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, spin, 100f, false, arcPaint)
            return
        }

        val accent = if (mode == Mode.SUCCESS) seuGreenDeep else seuRed

        // 涟漪
        if (rippleAlpha > 0.01f) {
            ripplePaint.color = accent
            ripplePaint.alpha = (rippleAlpha * 52f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, s * 0.43f * rippleScale, ripplePaint)
        }

        // 圆底
        discPaint.color = accent
        canvas.save()
        canvas.scale(discScale, discScale, cx, cy)
        canvas.drawCircle(cx, cy, s * 0.43f, discPaint)
        canvas.restore()

        // 对勾 / 叉号
        if (glyphProgress > 0.001f) {
            buildGlyphIfNeeded(cx, cy, s * 0.78f)
            glyphPaint.strokeWidth = s * 0.088f
            for (i in glyphPaths.indices) {
                val len = glyphLengths[i]
                if (len <= 0f) continue
                segPath.reset()
                glyphMeasures[i].getSegment(0f, len * glyphProgress, segPath, true)
                canvas.drawPath(segPath, glyphPaint)
            }
        }
    }

    private fun buildGlyphIfNeeded(cx: Float, cy: Float, box: Float) {
        if (builtBox > 0f && abs(builtBox - box) < 0.5f) return
        builtBox = box

        glyphPaths[0].reset()
        glyphPaths[1].reset()

        fun x(n: Float) = cx + (n - 0.5f) * box
        fun y(n: Float) = cy + (n - 0.5f) * box

        if (mode == Mode.SUCCESS) {
            glyphPaths[0].moveTo(x(0.29f), y(0.53f))
            glyphPaths[0].lineTo(x(0.44f), y(0.68f))
            glyphPaths[0].lineTo(x(0.72f), y(0.35f))
        } else {
            glyphPaths[0].moveTo(x(0.35f), y(0.35f))
            glyphPaths[0].lineTo(x(0.65f), y(0.65f))
            glyphPaths[1].moveTo(x(0.65f), y(0.35f))
            glyphPaths[1].lineTo(x(0.35f), y(0.65f))
        }

        for (i in glyphPaths.indices) {
            glyphMeasures[i].setPath(glyphPaths[i], false)
            glyphLengths[i] = glyphMeasures[i].length
        }
    }
}

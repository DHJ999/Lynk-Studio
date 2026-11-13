package com.duan.lynkcluster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 整块仪表由这一个 View 画完。
 *
 * 为什么不做成多个子 View：View 树的 measure/layout 在每帧数据刷新时都会被触发，
 * 而这里所有内容都在固定位置，只有数值在变。单 View + 纯 Canvas 绘制意味着
 * 数据更新只走 invalidate → draw，没有 requestLayout，没有子 View 分发，
 * 这是车机上最省的一条路径。
 *
 * 帧驱动：目标值变化时置脏，onDraw 里对显示值做指数逼近，未收敛就
 * postInvalidateOnAnimation 续帧。数值静止后自动停止重绘，不空转 GPU。
 */
class ClusterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ---------- 数据 ----------

    private var data: Telemetry = Telemetry.IDLE

    private var dSpeed = 0f
    private var dRpm = 0f
    private var dCoolant = 0f
    private var dFuel = 0f
    private var dRange = 0f
    private var dVoltage = 0f

    private var clock: String = ""

    /** onDraw 里画数字用的复用缓冲，避免每帧 Int.toString() 产生短命对象。 */
    private val speedBuffer = NumberBuffer()
    private val rpmBuffer = NumberBuffer()
    private val coolantBuffer = NumberBuffer()

    /** 极简渲染器的帧对象同样复用，避免每帧 new 一个 Frame。 */
    private val minimalFrame = MinimalClusterRenderer.Frame()
    private val tireValues = FloatArray(4)
    private val tireText = arrayOf("--", "--", "--", "--")
    private val tireCached = FloatArray(4) { Float.NaN }

    private var tripValue = Float.NaN
    private var consumptionValue = Float.NaN
    private var odometerValue = Float.NaN
    private var ambientValue = Float.NaN
    private var tripText = "--"
    private var consumptionText = "--"
    private var odometerText = "--"
    private var ambientText = "--"

    var theme: ClusterTheme = ClusterThemes.DEEP_SPACE
        private set

    var transparentBackground: Boolean = false
        private set

    var hideOwnGear: Boolean = false
        private set

    fun submit(t: Telemetry) {
        if (t == data) return
        data = t
        updateStaticText(t)
        postInvalidateOnAnimation()
    }

    fun setClock(text: String) {
        if (clock != text) {
            clock = text
            postInvalidateOnAnimation()
        }
    }

    /**
     * 换肤和显示模式都不重建 View，只改画笔颜色与背景绘制开关。
     * 不遮挡模式下隐藏自己的挡位，把位置留给原厂仪表。
     */
    fun applyDashboard(
        theme: ClusterTheme,
        transparentBackground: Boolean,
        hideOwnGear: Boolean,
    ) {
        this.theme = theme
        this.transparentBackground = transparentBackground
        this.hideOwnGear = hideOwnGear
        applyTheme()
        // 主题可能切换了渲染分支（卡片 ↔ 极简），即使数据没变也要重画一帧
        invalidate()
    }

    private fun applyTheme() {
        val track = if (transparentBackground) withAlpha(theme.track, 0.55f) else theme.track
        trackPaint.color = track
        barTrackPaint.color = track
        dividerPaint.color = track
        segPaint.color = if (transparentBackground) withAlpha(theme.bg, 0.82f) else theme.bg

        glowPaint.color = theme.primary
        digitPaint.color = theme.textPrimary
        unitPaint.color = theme.textSecondary
        gearTextPaint.color = theme.primary
        rpmDigitPaint.color = theme.textSecondary
        rpmLabelPaint.color = theme.textMuted
        statLabelPaint.color = theme.textSecondary
        statUnitPaint.color = theme.textMuted
        footLabelPaint.color = theme.textMuted
        footValuePaint.color = theme.textSecondary
        footTimePaint.color = theme.textPrimary
        tireLabelPaint.color = theme.textMuted
        tireValuePaint.color = theme.textPrimary
        gearBgPaint.color = if (transparentBackground) withAlpha(theme.bg, 0.72f) else theme.bg
        gearBorderPaint.color = withAlpha(theme.primary, 0.28f)

        applyTextShadow(digitPaint)
        applyTextShadow(unitPaint)
        applyTextShadow(gearTextPaint)
        applyTextShadow(rpmDigitPaint)
        applyTextShadow(rpmLabelPaint)
        applyTextShadow(statLabelPaint)
        applyTextShadow(statValuePaint)
        applyTextShadow(statUnitPaint)
        applyTextShadow(footLabelPaint)
        applyTextShadow(footValuePaint)
        applyTextShadow(footTimePaint)
        applyTextShadow(tireLabelPaint)
        applyTextShadow(tireValuePaint)
        applyTextShadow(tickLabelPaint)

        if (vw > 0f && vh > 0f) layoutAll()
        invalidate()
    }

    /** 透明叠加在原厂仪表上时，给文字加一层暗影，避免白字压在白底上看不清。 */
    private fun applyTextShadow(paint: Paint) {
        if (transparentBackground) {
            paint.setShadowLayer(3.5f, 0f, 1f, 0xA6000000.toInt())
        } else {
            paint.clearShadowLayer()
        }
    }

    // ---------- 字体 ----------

    private val tfBold: Typeface by lazy { resources.getFont(R.font.rajdhani_bold) }
    private val tfSemi: Typeface by lazy { resources.getFont(R.font.rajdhani_semibold) }
    private val tfCjk: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    // ---------- 几何（onSizeChanged 一次算好） ----------

    private var vw = 0f
    private var vh = 0f

    private var padH = 0f
    private var bandTop = 0f
    private var bandBottom = 0f
    private var bandHeight = 0f

    private var leftW = 0f
    private var rightW = 0f
    private var rightLeft = 0f
    private var rightEnd = 0f

    /** 物理仪表可见的中央卡片区域（原厂左右面板会盖住两翼）。 */
    private var cardLeft = 0f
    private var cardRight = 0f

    private var ringCx = 0f
    private var ringCy = 0f
    private var ringR = 0f
    private var ringStroke = 0f
    private var side = 0f

    private var dividerY = 0f
    private var footerCy = 0f

    // ---------- 画笔 ----------

    private val bgPaint = Paint()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = theme.track
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = theme.primary
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = theme.textPrimary
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = theme.textSecondary
    }
    private val gearBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gearBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val gearTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = theme.primary
    }
    private val rpmDigitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = theme.textSecondary
    }
    private val rpmLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = theme.textMuted
    }
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.track }
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.bg }
    private val coolantValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val coolantUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = theme.textMuted
    }
    private val statLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.textSecondary }
    private val statValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val statUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        color = theme.textMuted
    }
    private val dividerPaint = Paint().apply { color = theme.track }
    private val footLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.textMuted }
    private val footValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.textSecondary }
    private val footTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.textPrimary }
    private val tireLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.textMuted }
    private val tireValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }

    private val ringOval = RectF()
    private val barPath = Path()

    /** 形态级主题（极简科技）走独立渲染器，与上面这套卡片布局完全解耦。 */
    private val minimalRenderer = MinimalClusterRenderer()

    private var arcShader: Shader? = null

    // 刻度用的三角函数与标签，尺寸一变就重算一次，避免每帧 26 次三角函数调用。
    private val tickCos = FloatArray(TICK_COUNT)
    private val tickSin = FloatArray(TICK_COUNT)
    private val tickText = Array(TICK_COUNT) { "" }

    init {
        isFocusable = false
        isClickable = false
        applyTheme()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vw = w.toFloat()
        vh = h.toFloat()
        layoutAll()
    }

    private fun layoutAll() {
        padH = vw * 0.025f

        val footerH = vh * 0.072f
        val gap = vh * 0.014f
        val padBottom = vh * 0.028f

        dividerY = vh - padBottom - footerH - gap
        footerCy = dividerY + gap + footerH / 2f

        val bandPad = vh * 0.022f
        bandTop = vh * 0.033f + bandPad
        bandBottom = dividerY - bandPad
        bandHeight = bandBottom - bandTop

        // 卡片布局：物理仪表上只有中间卡片区域可见（原厂左右面板不透明，压在我们的
        // 输出之上，见 2026-11-07 实车照片）。全宽三段式会浪费被遮住的两翼，所以
        // 把挡位/车速/转速/水温全部收进中央 50% 区域，保证任意卡片宽度都完整可见。
        cardLeft = vw * 0.25f
        cardRight = vw * 0.75f
        leftW = (cardRight - cardLeft) * 0.16f
        rightW = (cardRight - cardLeft) * 0.16f
        rightEnd = cardRight
        rightLeft = cardRight - rightW

        val centerLeft = cardLeft + leftW
        val centerRight = rightLeft
        side = min(centerRight - centerLeft, bandHeight)

        ringStroke = side * 0.042f
        ringR = side / 2f - ringStroke * 1.72f
        ringCx = (centerLeft + centerRight) / 2f
        ringCy = bandTop + bandHeight / 2f
        ringOval.set(ringCx - ringR, ringCy - ringR, ringCx + ringR, ringCy + ringR)

        // 背景径向渐变：中心略亮，制造纵深
        val glowR = max(vw, vh) * 0.95f
        bgPaint.shader = RadialGradient(
            vw / 2f, vh * 0.32f, glowR,
            theme.bgGlow, theme.bg,
            Shader.TileMode.CLAMP,
        )

        // 弧线渐变：SweepGradient 覆盖整圈，颜色按 270/360 压缩到弧段内
        val sweep = SweepGradient(
            ringCx, ringCy,
            intArrayOf(theme.primaryDeep, theme.primary, theme.primary, theme.primary),
            floatArrayOf(0f, 0.3375f, 0.75f, 1f),
        )
        sweep.setLocalMatrix(Matrix().apply { setRotate(START_ANGLE, ringCx, ringCy) })
        arcShader = sweep

        // 字号全部按屏宽比例，720p / 1080p / 2K 车机都能用
        digitPaint.textSize = side * 0.37f
        digitPaint.typeface = tfBold
        unitPaint.textSize = side * 0.03f
        unitPaint.typeface = tfSemi
        unitPaint.letterSpacing = 0.14f

        tickLabelPaint.textSize = ringStroke * 1.25f
        tickLabelPaint.typeface = tfSemi

        gearTextPaint.textSize = side * 0.105f
        gearTextPaint.typeface = tfSemi
        gearBgPaint.color = if (transparentBackground) withAlpha(theme.bg, 0.72f) else theme.bg
        gearBorderPaint.color = withAlpha(theme.primary, 0.28f)
        gearBorderPaint.strokeWidth = max(1f, side * 0.0012f)

        rpmDigitPaint.textSize = vw * 0.034f
        rpmDigitPaint.typeface = tfBold
        rpmLabelPaint.textSize = vw * 0.0085f
        rpmLabelPaint.typeface = tfSemi
        rpmLabelPaint.letterSpacing = 0.14f

        statLabelPaint.textSize = vw * 0.0115f
        statLabelPaint.typeface = tfCjk
        statValuePaint.textSize = vw * 0.030f
        statValuePaint.typeface = tfBold
        statUnitPaint.textSize = vw * 0.0115f * 0.85f
        statUnitPaint.typeface = tfSemi
        tireLabelPaint.textSize = vw * 0.0085f
        tireLabelPaint.typeface = tfCjk
        tireValuePaint.textSize = vw * 0.0165f
        tireValuePaint.typeface = tfBold
        coolantValuePaint.textSize = vw * 0.036f
        coolantValuePaint.typeface = tfBold
        coolantUnitPaint.textSize = vw * 0.016f
        coolantUnitPaint.typeface = tfSemi

        val fSize = vw * 0.0110f
        footLabelPaint.textSize = fSize * 0.95f
        footLabelPaint.typeface = tfCjk
        footValuePaint.textSize = fSize * 1.25f
        footValuePaint.typeface = tfSemi
        footTimePaint.textSize = fSize * 1.8f
        footTimePaint.typeface = tfSemi

        // 刻度预计算：0 / 20 / ... / 240 共 13 根
        for (i in 0 until TICK_COUNT) {
            val v = i * 20
            val deg = START_ANGLE + SWEEP_ANGLE * (v / MAX_SPEED)
            val rad = Math.toRadians(deg.toDouble())
            tickCos[i] = cos(rad).toFloat()
            tickSin[i] = sin(rad).toFloat()
            tickText[i] = v.toString()
        }

        // 形态级主题：几何由独立渲染器按 1920×720 设计稿等比缩放自己算
        minimalRenderer.layout(vw, vh)
    }

    // ---------- 帧循环 ----------

    override fun onDraw(canvas: Canvas) {
        if (vw <= 0f || vh <= 0f) return

        if (stepAnimation()) {
            postInvalidateOnAnimation()
        }

        // 形态级主题：整块交给独立渲染器，不碰下面这套卡片布局的任何画笔
        if (ClusterThemes.usesDedicatedRenderer(theme)) {
            buildMinimalFrameInto(minimalFrame)
            minimalRenderer.draw(canvas, minimalFrame)
            return
        }

        if (!transparentBackground) {
            canvas.drawRect(0f, 0f, vw, vh, bgPaint)
        }
        drawRing(canvas)
        drawGearColumn(canvas)
        drawRpmRail(canvas)
        drawFooter(canvas)
    }

    /**
     * 把本 View 的平滑显示值与静态文本原地写进复用帧对象。
     *
     * 原先这里是返回新 `Frame` 的 `buildMinimalFrame()`，每帧一次分配；
     * 60 fps × 24/7 是每天 500 万个短命对象，GC 抖动就是这么来的。
     */
    private fun buildMinimalFrameInto(f: MinimalClusterRenderer.Frame) {
        f.speed = if (data.speed.isNaN()) Float.NaN else dSpeed
        f.rpm = if (data.rpm < 0) Float.NaN else dRpm
        f.coolant = if (data.coolant.isNaN()) Float.NaN else dCoolant
        f.fuel = if (data.fuel.isNaN()) Float.NaN else dFuel
        f.range = if (data.range.isNaN()) Float.NaN else dRange
        f.gear = data.gear
        f.clock = clock
        f.ambient = ambientText
        f.odometer = odometerText
        f.isLive = data.source == Telemetry.SOURCE_CAR
        f.transparentBackground = transparentBackground
        f.hideGear = hideOwnGear
    }

    /** 显示值向目标值指数逼近；返回是否还在动。 */
    private fun stepAnimation(): Boolean {
        var moving = false

        fun lerp(cur: Float, target: Float): Float {
            // NaN 表示信号缺失：保持指针不动，绘制层直接画 --。
            if (target.isNaN()) return cur
            val d = target - cur
            if (abs(d) < 0.015f) return target
            moving = true
            return cur + d * 0.22f
        }

        dSpeed = lerp(dSpeed, data.speed)
        dRpm = lerp(dRpm, if (data.rpm < 0) Float.NaN else data.rpm.toFloat())
        dCoolant = lerp(dCoolant, data.coolant)
        dFuel = lerp(dFuel, data.fuel)
        dRange = lerp(dRange, data.range)
        dVoltage = lerp(dVoltage, data.voltage)
        return moving
    }

    private fun updateStaticText(t: Telemetry) {
        if (!sameFloat(t.trip, tripValue)) {
            tripValue = t.trip
            tripText = if (t.trip.isNaN()) "--" else formatOneDecimal(t.trip) + " km"
        }
        if (!sameFloat(t.consumption, consumptionValue)) {
            consumptionValue = t.consumption
            consumptionText =
                if (t.consumption.isNaN()) "--" else formatOneDecimal(t.consumption) + " L/100km"
        }
        if (!sameFloat(t.odo, odometerValue)) {
            odometerValue = t.odo
            odometerText = if (t.odo.isNaN()) "--" else t.odo.roundToInt().toString() + " km"
        }
        if (!sameFloat(t.ambient, ambientValue)) {
            ambientValue = t.ambient
            ambientText = if (t.ambient.isNaN()) "--" else t.ambient.roundToInt().toString() + "°"
        }

        updateTireText(0, t.tires.frontLeft, t.tires)
        updateTireText(1, t.tires.frontRight, t.tires)
        updateTireText(2, t.tires.rearLeft, t.tires)
        updateTireText(3, t.tires.rearRight, t.tires)
    }

    private fun updateTireText(index: Int, value: Float, tires: TirePressures) {
        tireValues[index] = value
        if (sameFloat(value, tireCached[index])) return
        tireCached[index] = value
        tireText[index] = if (tires.isAvailable(value)) {
            formatOneDecimal(value)
        } else {
            "--"
        }
    }

    // ---------- 左列：挡位 + 冷却液 ----------

    /**
     * 挡位方块与水温块纵向排在卡片左列。
     * 挡位是仪表上最重要的状态量，字号按列宽而不是全屏宽取，保证卡片内足够大。
     */
    private fun drawGearColumn(canvas: Canvas) {
        val colCx = cardLeft + leftW / 2f
        val colW = leftW * 0.92f

        if (!hideOwnGear) {
            val gh = side * 0.17f
            val gw = min(colW, gh * 1.45f)
            val gCy = bandTop + bandHeight * 0.26f
            val left = colCx - gw / 2f
            val top = gCy - gh / 2f
            val r = gh * 0.22f

            canvas.drawRoundRect(left, top, left + gw, top + gh, r, r, gearBgPaint)
            canvas.drawRoundRect(left, top, left + gw, top + gh, r, r, gearBorderPaint)

            val fm = gearTextPaint.fontMetrics
            gearTextPaint.color = if (data.gear == "--" || data.gear.isBlank()) {
                theme.textMuted
            } else {
                theme.primary
            }
            canvas.drawText(data.gear, colCx, gCy - (fm.ascent + fm.descent) / 2f, gearTextPaint)
        }

        // 水温：数字 + 单位 + 标签，缺失时保持 --。
        val coolantMissing = data.coolant.isNaN()
        val cCy = bandTop + bandHeight * 0.62f
        val fmv = coolantValuePaint.fontMetrics
        val baseline = cCy - (fmv.ascent + fmv.descent) / 2f

        statLabelPaint.color = theme.textSecondary
        val labelBaseline = cCy - side * 0.115f
        canvas.drawText("冷却液", colCx - statLabelPaint.measureText("冷却液") / 2f, labelBaseline, statLabelPaint)

        coolantValuePaint.color = when {
            coolantMissing -> theme.textMuted
            dCoolant > 105f -> theme.warn
            else -> theme.primary
        }
        val valueText = if (coolantMissing) {
            coolantBuffer.setLiteral(MISSING)
        } else {
            coolantBuffer.setInt(dCoolant.toInt())
        }
        val valueW = coolantValuePaint.measureText(valueText.chars, valueText.start, valueText.length)
        val unitW = coolantUnitPaint.measureText("°C")
        val totalW = valueW + coolantUnitPaint.textSize * 0.28f + unitW
        val startX = colCx - totalW / 2f
        canvas.drawText(
            valueText.chars, valueText.start, valueText.length,
            startX, baseline, coolantValuePaint,
        )
        coolantUnitPaint.color = theme.textMuted
        canvas.drawText("°C", startX + valueW + coolantUnitPaint.textSize * 0.28f, baseline, coolantUnitPaint)
    }

    // ---------- 右列：转速柱 ----------

    private fun drawRpmRail(canvas: Canvas) {
        val cx = rightLeft + rightW / 2f

        val rpmMissing = data.rpm < 0
        val rpmHot = !rpmMissing && dRpm >= 6500f
        rpmDigitPaint.color = when {
            rpmMissing -> theme.textMuted
            rpmHot -> theme.warn
            else -> theme.textSecondary
        }

        var y = bandTop
        val fmd = rpmDigitPaint.fontMetrics
        val rpmText = if (rpmMissing) rpmBuffer.setLiteral(MISSING) else rpmBuffer.setInt(dRpm.toInt())
        canvas.drawText(
            rpmText.chars, rpmText.start, rpmText.length,
            cx, y - fmd.ascent, rpmDigitPaint,
        )
        y += (fmd.descent - fmd.ascent) + rpmDigitPaint.textSize * 0.12f

        val fml = rpmLabelPaint.fontMetrics
        canvas.drawText("RPM", cx, y - fml.ascent, rpmLabelPaint)
        y += rpmLabelPaint.textSize + rpmDigitPaint.textSize * 0.5f

        val barW = vw * 0.019f
        val slot = bandBottom - y
        if (slot <= 0f) return
        val barH = slot * 0.94f
        val barTop = y + (slot - barH) / 2f
        val barLeft = cx - barW / 2f
        val barR = barW / 2f

        canvas.drawRoundRect(barLeft, barTop, barLeft + barW, barTop + barH, barR, barR, barTrackPaint)

        val frac = if (rpmMissing) 0f else (dRpm / MAX_RPM).coerceIn(0f, 1f)
        if (frac > 0.001f) {
            val fillH = barH * frac
            barPath.reset()
            barPath.addRoundRect(
                barLeft, barTop, barLeft + barW, barTop + barH,
                barR, barR, Path.Direction.CW,
            )
            canvas.save()
            canvas.clipPath(barPath)

            barFillPaint.color = if (rpmHot) theme.warn else theme.primary
            canvas.drawRect(barLeft, barTop + barH - fillH, barLeft + barW, barTop + barH, barFillPaint)

            // 分格线：用背景色把柱子切成 8 段
            var i = 1
            while (i < RPM_SLOTS) {
                val ly = barTop + barH * i / RPM_SLOTS
                canvas.drawRect(barLeft, ly - 0.9f, barLeft + barW, ly + 1.0f, segPaint)
                i++
            }
            canvas.restore()
        }
    }

    // ---------- 车速环 ----------

    private fun drawRing(canvas: Canvas) {
        trackPaint.strokeWidth = ringStroke
        canvas.drawArc(ringOval, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val p = if (data.speed.isNaN()) 0f else (dSpeed / MAX_SPEED).coerceIn(0f, 1f)
        if (p > 0.002f) {
            // 硬件加速下 BlurMaskFilter 不生效，用两层加宽低透明度描边模拟辉光。
            glowPaint.strokeWidth = ringStroke * 2.2f
            glowPaint.alpha = 20
            canvas.drawArc(ringOval, START_ANGLE, SWEEP_ANGLE * p, false, glowPaint)

            glowPaint.strokeWidth = ringStroke * 1.45f
            glowPaint.alpha = 30
            canvas.drawArc(ringOval, START_ANGLE, SWEEP_ANGLE * p, false, glowPaint)

            arcPaint.shader = arcShader
            canvas.drawArc(ringOval, START_ANGLE, SWEEP_ANGLE * p, false, arcPaint)
        }

        drawTicks(canvas, p)
        drawCenterNumber(canvas)
    }

    private fun drawTicks(canvas: Canvas, progress: Float) {
        val outer = ringR + ringStroke * 0.72f
        val lit = progress * MAX_SPEED
        val labelR = ringR - ringStroke * 1.2f - tickLabelPaint.textSize * 0.6f
        val labelHalf = (tickLabelPaint.ascent() + tickLabelPaint.descent()) / 2f

        val litColor = withAlpha(theme.primary, 0.58f)
        val dimColor = withAlpha(theme.textMuted, 0.38f)

        for (i in 0 until TICK_COUNT) {
            val v = i * 20f
            val c = tickCos[i]
            val s = tickSin[i]
            val major = i % 2 == 0
            val len = if (major) ringStroke * 1.5f else ringStroke * 0.78f
            val on = lit >= v - 0.01f

            tickPaint.strokeWidth = if (major) ringStroke * 0.075f else ringStroke * 0.042f
            tickPaint.color = if (on) litColor else dimColor
            canvas.drawLine(
                ringCx + c * outer, ringCy + s * outer,
                ringCx + c * (outer + len), ringCy + s * (outer + len),
                tickPaint,
            )

            if (major) {
                tickLabelPaint.color = if (on) theme.textSecondary else theme.textMuted
                canvas.drawText(
                    tickText[i],
                    ringCx + c * labelR,
                    ringCy + s * labelR - labelHalf,
                    tickLabelPaint,
                )
            }
        }
    }

    private fun drawCenterNumber(canvas: Canvas) {
        val cy = ringCy - ringR * 0.16f
        val fm = digitPaint.fontMetrics
        val speedMissing = data.speed.isNaN()
        digitPaint.color = if (speedMissing) theme.textMuted else theme.textPrimary

        val speedText = if (speedMissing) {
            speedBuffer.setLiteral(MISSING)
        } else {
            speedBuffer.setInt(dSpeed.toInt())
        }
        canvas.drawText(
            speedText.chars, speedText.start, speedText.length,
            ringCx,
            cy - (fm.ascent + fm.descent) / 2f,
            digitPaint,
        )

        val ufm = unitPaint.fontMetrics
        canvas.drawText(
            "KM/H",
            ringCx,
            cy + digitPaint.textSize * 0.46f - (ufm.ascent + ufm.descent) / 2f,
            unitPaint,
        )
    }

    // ---------- 底部信息条 ----------

    private fun drawFooter(canvas: Canvas) {
        // 页脚同样收进卡片区域，避免被原厂左右面板盖掉一半。
        canvas.drawRect(cardLeft, dividerY, cardRight, dividerY + 1f, dividerPaint)

        val fm = footLabelPaint.fontMetrics
        val baseline = footerCy - (fm.ascent + fm.descent) / 2f

        var x = cardLeft
        x += drawText(canvas, clock, x, baseline, footTimePaint) + footLabelPaint.textSize * 2.2f

        // 非真实数据必须一眼可见，避免把模拟车速当成实车数据。
        if (data.source != Telemetry.SOURCE_CAR) {
            footLabelPaint.color = theme.amber
            val tag = if (data.source == Telemetry.SOURCE_SIM) "SIM" else data.source
            x += drawText(canvas, tag, x, baseline, footLabelPaint) +
                footLabelPaint.textSize * 2.2f
        }

        x += drawPair(canvas, x, baseline, "行程", tripText)
        x += drawPair(canvas, x, baseline, "油耗", consumptionText)
        x += drawPair(canvas, x, baseline, "总里程", odometerText)

        // 车外温度靠右
        val aw = footValuePaint.measureText(ambientText)
        val ambientValueX = cardRight - aw
        footValuePaint.color = theme.textSecondary
        canvas.drawText(ambientText, ambientValueX, baseline, footValuePaint)
        footLabelPaint.color = theme.textMuted
        canvas.drawText(
            "车外",
            ambientValueX - footLabelPaint.textSize * 0.5f -
                footLabelPaint.measureText("车外"),
            baseline,
            footLabelPaint,
        )
    }

    private fun drawPair(canvas: Canvas, x: Float, baseline: Float, label: String, value: String): Float {
        footLabelPaint.color = theme.textMuted
        val lx = drawText(canvas, label, x, baseline, footLabelPaint)
        val gap = footLabelPaint.textSize * 0.5f
        footValuePaint.color = theme.textSecondary
        val vx = drawText(canvas, value, x + lx + gap, baseline, footValuePaint)
        return lx + gap + vx + footLabelPaint.textSize * 2.6f
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, baseline: Float, paint: Paint): Float {
        canvas.drawText(text, x, baseline, paint)
        return paint.measureText(text)
    }

    private fun formatOneDecimal(value: Float): String {
        val scaled = (value * 10f).roundToInt()
        val whole = scaled / 10
        val fraction = abs(scaled % 10)
        return "$whole.$fraction"
    }

    private fun sameFloat(a: Float, b: Float): Boolean =
        (a.isNaN() && b.isNaN()) || a == b

    private fun withAlpha(color: Int, alpha: Float): Int {
        return Color.argb(
            (alpha.coerceIn(0f, 1f) * 255f).toInt(),
            Color.red(color), Color.green(color), Color.blue(color),
        )
    }

    companion object {
        const val MAX_SPEED = 240f
        const val MAX_RPM = 8000f
        const val START_ANGLE = 135f
        const val SWEEP_ANGLE = 270f
        const val RPM_SLOTS = 8

        /** 0 / 20 / ... / 240 共 13 根刻度 */
        const val TICK_COUNT = 13

        /** 信号缺失的统一占位，不拿 0 冒充真实值。 */
        private const val MISSING = "--"

        private val TIRE_LABELS = arrayOf("左前", "右前", "左后", "右后")
    }
}

package com.duan.lynkcluster

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「极简科技」仪表渲染器。
 *
 * 与 [ClusterView] 现有五套主题的区别：**那五套只是换配色，共用卡片布局**；
 * 这一套是形态级重做 —— 全幅 1920×720 构图、纯线框、无填充块，靠字号层级与留白说话。
 * 设计稿见 `仪表盘样式/新风格_科技极简MINIMAL_1920x720.png`。
 *
 * 为什么单独一个类：绘制逻辑近 300 行，塞进 ClusterView 会让那个文件失控；
 * 而且这套布局将来要做「全屏槽打通后」的默认皮肤，独立出来好单独演进与测试。
 *
 * 坐标策略：设计稿固定在 1920×720，这里用 [scale] 等比缩放并水平居中，
 * 所以 1280×720、1920×720、2560×720 都能用，不会拉变形。
 */
class MinimalClusterRenderer {

    /**
     * 一帧要画的数据。显示值已由 [ClusterView] 做过指数平滑。
     *
     * 刻意做成可变类、由 [ClusterView] 复用同一个实例：`onDraw` 里每帧
     * new 一个 Frame 是每秒 60 个短命对象，24/7 常驻后会变成 GC 抖动源。
     * 这个类的实例只在绘制线程上读写，不需要同步。
     */
    class Frame {
        var speed: Float = Float.NaN
        var rpm: Float = Float.NaN
        var coolant: Float = Float.NaN
        var fuel: Float = Float.NaN
        var range: Float = Float.NaN
        var gear: String = MISSING

        /** 该字段由 ClusterView 侧维护（分钟级变化），这里只持有引用。 */
        var clock: String = ""
        var ambient: String = MISSING
        var odometer: String = MISSING
        var isLive: Boolean = false
        var transparentBackground: Boolean = false
        var hideGear: Boolean = false
    }

    // ---------- 设计稿基准 ----------

    private companion object {
        /** 标定实验开关：true 时整屏铺红/绿/蓝，用于确认槽宽与混合模式。发版必须 false。 */
        const val CALIBRATION = false

        const val DW = 1920f
        const val DH = 720f

        // 主环。注意角度基准：设计稿是 SVG，0° 指向正上方；Canvas.drawArc 的 0° 指向正右方。
        // 换算关系 θ_canvas = θ_svg - 90，所以设计稿的 -140° 到这里是 130°。
        const val MAIN_CX = 960f
        const val MAIN_CY = 372f
        const val MAIN_R = 240f
        const val MAIN_START = 130f
        const val MAIN_SWEEP = 280f

        // 左转速环（设计稿 -135° → Canvas 135°）
        const val RPM_CX = 300f
        const val RPM_CY = 392f
        const val RPM_R = 96f
        const val RPM_START = 135f
        const val RPM_SWEEP = 270f

        // 右侧数据列
        const val STAT_RIGHT = 1620f
        const val STAT_LINE_W = 168f
        const val STAT_TOP = 236f
        const val STAT_GAP = 132f

        const val MAX_SPEED = 240f
        const val MAX_RPM = 8000f
        const val COOLANT_MIN = 60f
        const val COOLANT_MAX = 110f
        const val RANGE_MAX = 650f

        /** 信号缺失时统一显示 `--`，不拿 0 冒充真实读到的 0。 */
        const val MISSING = "--"
    }

    // ---------- 配色（对齐设计稿） ----------

    private val cBg = 0xFF0B0D10.toInt()
    private val cBgGlow = 0xFF11151A.toInt()
    private val cLine = 0xFF242A32.toInt()
    private val cLine2 = 0xFF2F3742.toInt()
    private val cText = 0xFFF2F5F8.toInt()
    private val cSub = 0xFF6B7885.toInt()
    private val cHi = 0xFF7FD8FF.toInt()
    private val cWarn = 0xFFFF6B4A.toInt()

    // ---------- 字体 ----------

    /** 大字用系统细体：Rajdhani 最细也是 semibold，做不出设计稿那种纤细骨架。 */
    private val tfLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val tfRegular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val tfCjk: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    // ---------- 画笔 ----------

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    /** 数字文本的复用缓冲：三处绘制各一个，互不覆盖。 */
    private val bigValueBuffer = NumberBuffer()
    private val rpmValueBuffer = NumberBuffer()
    private val statValueBuffer = NumberBuffer()

    // ---------- 几何 ----------

    private var w = 0f
    private var h = 0f
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    /** 设计稿坐标 → 屏幕坐标 */
    private fun sx(x: Float) = offX + x * scale
    private fun sy(y: Float) = offY + y * scale
    private fun ss(v: Float) = v * scale

    fun layout(width: Float, height: Float) {
        w = width
        h = height
        // 等比：以短边为基准，两个方向都居中，非 2.667:1 的屏也不会拉变形
        scale = minOf(width / DW, height / DH)
        offX = (width - DW * scale) / 2f
        offY = (height - DH * scale) / 2f
    }

    fun draw(canvas: Canvas, f: Frame) {
        if (w <= 0f || h <= 0f) return

        drawBackground(canvas, f)
        drawTopBar(canvas, f)
        drawMainRing(canvas, f)
        drawRpmRing(canvas, f)
        drawStatColumn(canvas, f)
        drawBottom(canvas, f)
    }

    // ---------- 背景 ----------

    private fun drawBackground(canvas: Canvas, f: Frame) {
        // 标定实验：整屏铺纯红/绿/蓝三色块。
        // 用来一次性确认两件事 —— ① QNX 给我们的槽有多宽；② 合成是「不透明替换」还是「透明叠加」。
        // 正常发版时必须保持 false。
        if (CALIBRATION) {
            val third = w / 3f
            bgPaint.color = Color.RED
            canvas.drawRect(0f, 0f, third, h, bgPaint)
            bgPaint.color = Color.GREEN
            canvas.drawRect(third, 0f, third * 2f, h, bgPaint)
            bgPaint.color = Color.BLUE
            canvas.drawRect(third * 2f, 0f, w, h, bgPaint)
            return
        }

        if (f.transparentBackground) return
        bgPaint.color = cBg
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        // 中心一层极淡提亮，避免纯平死黑
        bgPaint.color = cBgGlow
        bgPaint.alpha = 90
        canvas.drawCircle(w / 2f, h * 0.42f, h * 0.92f, bgPaint)
    }

    // ---------- 顶栏 ----------

    private fun drawTopBar(canvas: Canvas, f: Frame) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = tfLight
        textPaint.textSize = ss(28f)
        textPaint.color = cSub
        canvas.drawText(f.ambient, sx(130f), sy(96f), textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(f.clock, sx(1790f), sy(96f), textPaint)

        linePaint.color = cLine
        linePaint.strokeWidth = ss(1f)
        canvas.drawLine(sx(130f), sy(130f), sx(520f), sy(130f), linePaint)
        canvas.drawLine(sx(1400f), sy(130f), sx(1790f), sy(130f), linePaint)
    }

    // ---------- 主环 ----------

    private fun drawMainRing(canvas: Canvas, f: Frame) {
        val cx = sx(MAIN_CX)
        val cy = sy(MAIN_CY)
        val r = ss(MAIN_R)

        // 外细环
        linePaint.color = cLine
        linePaint.strokeWidth = ss(1.5f)
        canvas.drawCircle(cx, cy, r, linePaint)
        // 内层细环
        linePaint.color = cLine2
        linePaint.strokeWidth = ss(1f)
        canvas.drawCircle(cx, cy, r - ss(26f), linePaint)

        // 9 根刻度（长短交替）
        val tickOuter = r
        for (i in 0..8) {
            val deg = MAIN_START + MAIN_SWEEP / 8f * i
            val rad = Math.toRadians(deg.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val major = i % 2 == 0
            val len = if (major) ss(22f) else ss(12f)
            tickPaint.color = if (major) 0xFF42505F.toInt() else cLine2
            tickPaint.strokeWidth = if (major) ss(2f) else ss(1.2f)
            canvas.drawLine(
                cx + c * tickOuter, cy + s * tickOuter,
                cx + c * (tickOuter - len), cy + s * (tickOuter - len),
                tickPaint,
            )
        }

        // 进度弧：从起点画一小段，末端一个发光点（设计稿的「游标」）
        val frac = if (f.speed.isNaN()) 0f else (f.speed / MAX_SPEED).coerceIn(0f, 1f)
        val shownFrac = maxOf(frac, 0.055f)          // 静止时也留一小段，否则圆点悬空
        val shownDeg = MAIN_START + MAIN_SWEEP * shownFrac
        val radShown = Math.toRadians(shownDeg.toDouble())

        glowPaint.color = cHi
        glowPaint.alpha = 40
        glowPaint.strokeWidth = ss(9f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, MAIN_START, MAIN_SWEEP * shownFrac, false, glowPaint)

        arcPaint.color = cHi
        arcPaint.strokeWidth = ss(4f)
        canvas.drawArc(oval, MAIN_START, MAIN_SWEEP * shownFrac, false, arcPaint)

        dotPaint.color = cHi
        canvas.drawCircle(
            cx + cos(radShown).toFloat() * r,
            cy + sin(radShown).toFloat() * r,
            ss(6f),
            dotPaint,
        )

        // 大数字
        bigPaint.typeface = tfLight
        bigPaint.textSize = ss(236f)
        bigPaint.color = if (f.speed.isNaN()) cSub else cText
        // 设计稿 baseline 在 cy+74；细体 ascent 偏大，这里补偿一点
        val speed = if (f.speed.isNaN()) {
            bigValueBuffer.setLiteral(MISSING)
        } else {
            bigValueBuffer.setInt(f.speed.toInt())
        }
        canvas.drawText(
            speed.chars, speed.start, speed.length,
            sx(MAIN_CX), sy(MAIN_CY + 74f), bigPaint,
        )

        // 单位
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = tfRegular
        textPaint.textSize = ss(24f)
        textPaint.color = cSub
        canvas.drawText("km/h", sx(MAIN_CX), sy(MAIN_CY + 130f), textPaint)
    }

    // ---------- 左：转速环 ----------

    private fun drawRpmRing(canvas: Canvas, f: Frame) {
        val cx = sx(RPM_CX)
        val cy = sy(RPM_CY)
        val r = ss(RPM_R)

        linePaint.color = cLine
        linePaint.strokeWidth = ss(1f)
        canvas.drawCircle(cx, cy, r, linePaint)

        val frac = if (f.rpm.isNaN()) 0f else (f.rpm / MAX_RPM).coerceIn(0f, 1f)
        val shownFrac = maxOf(frac, 0.06f)

        glowPaint.color = cHi
        glowPaint.alpha = 36
        glowPaint.strokeWidth = ss(8f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, RPM_START, RPM_SWEEP * shownFrac, false, glowPaint)

        arcPaint.color = cHi
        arcPaint.strokeWidth = ss(3f)
        canvas.drawArc(oval, RPM_START, RPM_SWEEP * shownFrac, false, arcPaint)

        val radEnd = Math.toRadians((RPM_START + RPM_SWEEP * shownFrac).toDouble())
        dotPaint.color = cHi
        canvas.drawCircle(
            cx + cos(radEnd).toFloat() * r,
            cy + sin(radEnd).toFloat() * r,
            ss(5f),
            dotPaint,
        )

        // 读数：0.9（一位小数，转成 ×1000 r/min 口径）
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = tfLight
        textPaint.textSize = ss(46f)
        textPaint.color = if (f.rpm.isNaN()) cSub else cText
        val rpm = if (f.rpm.isNaN()) {
            rpmValueBuffer.setLiteral(MISSING)
        } else {
            rpmValueBuffer.setOneDecimal(f.rpm / 1000f)
        }
        canvas.drawText(rpm.chars, rpm.start, rpm.length, cx, sy(RPM_CY + 10f), textPaint)

        textPaint.textSize = ss(13f)
        textPaint.color = cSub
        canvas.drawText("×1000 r/min", cx, sy(RPM_CY + 40f), textPaint)

        textPaint.textSize = ss(13f)
        textPaint.color = cSub
        canvas.drawText("R P M", cx, sy(RPM_CY + 150f), textPaint)
    }

    // ---------- 右：三组数据 ----------

    /**
     * 右侧三行数据。
     *
     * 原来这里是 `listOf(Triple(...) to ...)` + `forEachIndexed`：每帧要分配
     * 1 个 List、3 个 Triple、3 个 Pair，还有三次字符串拼接。现在拆成三次
     * 直接调用，数值走复用缓冲，`onDraw` 内零分配。
     */
    private fun drawStatColumn(canvas: Canvas, f: Frame) {
        val right = sx(STAT_RIGHT)
        drawStatRow(canvas, 0, "水温", f.coolant, coolantFrac(f.coolant), "°C", right)
        drawStatRow(canvas, 1, "燃油", f.fuel, fuelFrac(f.fuel), "%", right)
        drawStatRow(canvas, 2, "续航", f.range, rangeFrac(f.range), "km", right)
    }

    private fun drawStatRow(
        canvas: Canvas,
        index: Int,
        label: String,
        value: Float,
        frac: Float?,
        unit: String,
        right: Float,
    ) {
        val y = sy(STAT_TOP + STAT_GAP * index)

        // 标签
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface = tfCjk
        textPaint.textSize = ss(14f)
        textPaint.color = cSub
        canvas.drawText(label, right, y, textPaint)

        // 数值
        textPaint.typeface = tfLight
        textPaint.textSize = ss(40f)
        textPaint.color = cText
        val buffer = if (value.isNaN()) {
            statValueBuffer.setLiteral(MISSING)
        } else {
            statValueBuffer.setInt(value.toInt())
        }
        canvas.drawText(
            buffer.chars, buffer.start, buffer.length,
            right, y + ss(42f), textPaint,
        )

        // 单位
        textPaint.typeface = tfRegular
        textPaint.textSize = ss(13f)
        textPaint.color = cSub
        canvas.drawText(unit, right, y + ss(64f), textPaint)

        // 细进度线
        val lineY = y + ss(82f)
        val left = right - ss(STAT_LINE_W)
        linePaint.color = cLine
        linePaint.strokeWidth = ss(1f)
        canvas.drawLine(left, lineY, right, lineY, linePaint)

        if (frac != null && frac > 0.005f) {
            linePaint.color = cHi
            linePaint.strokeWidth = ss(1.5f)
            canvas.drawLine(left, lineY, left + ss(STAT_LINE_W) * frac, lineY, linePaint)
        }
    }

    private fun coolantFrac(v: Float): Float? =
        if (v.isNaN()) null else ((v - COOLANT_MIN) / (COOLANT_MAX - COOLANT_MIN)).coerceIn(0f, 1f)

    private fun fuelFrac(v: Float): Float? =
        if (v.isNaN()) null else (v / 100f).coerceIn(0f, 1f)

    private fun rangeFrac(v: Float): Float? =
        if (v.isNaN()) null else (v / RANGE_MAX).coerceIn(0f, 1f)

    // ---------- 底部 ----------

    private fun drawBottom(canvas: Canvas, f: Frame) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = tfCjk
        textPaint.textSize = ss(15f)
        textPaint.color = cSub
        canvas.drawText("ODO  ${f.odometer}", sx(130f), sy(676f), textPaint)

        // 数据来源角标：非真实数据必须一眼可见，沿用原设计的规则
        if (!f.isLive) {
            textPaint.color = cWarn
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("SIM / 非实车", sx(1790f), sy(676f), textPaint)
        }

        if (f.hideGear) return

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = tfRegular
        textPaint.textSize = ss(30f)
        textPaint.color = cHi
        val gearText = if (f.gear.isBlank()) MISSING else f.gear
        canvas.drawText(gearText, sx(960f), sy(676f), textPaint)
    }
}

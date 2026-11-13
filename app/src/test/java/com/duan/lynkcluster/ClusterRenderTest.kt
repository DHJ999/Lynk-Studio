package com.duan.lynkcluster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 把 ClusterView 真实渲染成 PNG，人工校验 1920×720 上的排版和配色。
 *
 * 不遮挡模式会先铺一层假的原厂仪表背景，用来确认透明区域是真的透，
 * 以及白字在浅色/杂色背景上是否仍然可读。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "land-mdpi")
class ClusterRenderTest {

    @Test
    fun renderThemes() {
        ClusterThemes.ALL.forEach { theme ->
            render("build/render/cluster_${theme.id}.png", theme, transparent = false)
        }
        render("build/render/cluster_noblock.png", ClusterThemes.DEEP_SPACE, transparent = true)
    }

    /** 数据源切换/未就绪时不能显示假的 0，必须走 -- 分支且不崩。 */
    @Test
    fun renderIdleFrame() {
        render("build/render/cluster_idle.png", ClusterThemes.DEEP_SPACE, transparent = false, frame = Telemetry.IDLE)
    }

    private fun render(
        path: String,
        theme: ClusterTheme,
        transparent: Boolean,
        frame: Telemetry = SAMPLE,
    ) {
        val ctx = RuntimeEnvironment.getApplication()
        val view = ClusterView(ctx)
        view.applyDashboard(
            theme = theme,
            transparentBackground = transparent,
            hideOwnGear = false,
        )
        view.submit(frame)
        view.setClock("08:41")

        val w = 1920
        val h = 720
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        if (transparent) {
            // 假的原厂仪表底：中灰 + 白字，方便检查“不遮挡”模式的可读性。
            canvas.drawColor(Color.rgb(92, 96, 100))
            canvas.drawRect(0f, 0f, w.toFloat(), 120f, Paint().apply {
                color = Color.rgb(60, 64, 68)
            })
            canvas.drawText("FACTORY CLUSTER", 80f, 82f, Paint().apply {
                color = Color.WHITE
                textSize = 54f
                isAntiAlias = true
            })
        }

        // 数值从 0 向目标逼近，多跑几帧让补间收敛。
        repeat(60) { view.draw(canvas) }

        val out = File(path)
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("rendered -> ${out.absolutePath}")
    }

    private companion object {
        val SAMPLE = Telemetry(
            speed = 88f,
            rpm = 3120,
            gear = "D4",
            coolant = 92f,
            fuel = 64f,
            range = 472f,
            voltage = 13.8f,
            odo = 28417f,
            trip = 128.4f,
            consumption = 7.2f,
            ambient = 14f,
            tires = TirePressures(2.4f, 2.4f, 2.3f, 2.5f),
            source = Telemetry.SOURCE_CAR,
        )
    }
}

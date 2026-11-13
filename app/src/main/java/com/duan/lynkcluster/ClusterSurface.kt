package com.duan.lynkcluster

import android.app.Activity
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager

/**
 * 一块能承载仪表 View 的副屏表面。
 *
 * 三种实现：
 * - [ClusterOverlay]：TYPE_APPLICATION_OVERLAY + 目标 Display 的 WindowManager。
 *   服务在后台时也能用，主界面退出不熄灭。
 * - [ClusterDisplayActivity]：用 `ActivityOptions.setLaunchDisplayId()` 把 Activity
 *   直接投到仪表屏，把 Activity 真正落到副屏上的做法。
 * - [ClusterPresentation]：Presentation。有 Activity 时可用，作为旧式回退。
 */
interface ClusterSurface {
    val displayId: Int
    val view: ClusterView
    val isOverlay: Boolean

    fun show()
    fun dismiss()
    fun submit(t: Telemetry)
    fun setClock(text: String)
    fun apply(settings: DashboardSettings)
    fun isShowing(): Boolean
}

class ClusterOverlay(
    context: Context,
    display: Display,
    settings: DashboardSettings,
) : ClusterSurface {

    private val displayContext: Context = context.createDisplayContext(display)
    private val windowManager =
        displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    override val displayId: Int = display.displayId
    override val isOverlay: Boolean = true
    override val view: ClusterView = ClusterView(displayContext)

    private val params: WindowManager.LayoutParams = buildParams(settings.mode)
    private var added = false

    init {
        apply(settings)
    }

    override fun show() {
        if (added) return
        windowManager.addView(view, params)
        added = true
    }

    override fun dismiss() {
        if (!added) return
        added = false
        runCatching { windowManager.removeViewImmediate(view) }
    }

    override fun submit(t: Telemetry) {
        view.submit(t)
    }

    override fun setClock(text: String) {
        view.setClock(text)
    }

    override fun apply(settings: DashboardSettings) {
        val noBlock = settings.mode == DashboardMode.NO_BLOCK
        view.applyDashboard(
            theme = ClusterThemes.byId(settings.themeId),
            transparentBackground = noBlock,
            hideOwnGear = noBlock && settings.originalGear,
        )
        // 全屏模式要真正覆盖原厂仪表，窗口格式也必须是不透明的。
        val desiredFormat =
            if (noBlock) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
        if (params.format != desiredFormat) {
            params.format = desiredFormat
            if (added) runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    override fun isShowing(): Boolean = added && view.isAttachedToWindow

    private fun buildParams(mode: DashboardMode): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            if (mode == DashboardMode.NO_BLOCK) {
                PixelFormat.TRANSLUCENT
            } else {
                PixelFormat.OPAQUE
            },
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = "LynkCluster"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }
}

/**
 * 副屏 Activity 路径：把 Activity 直接投到目标 Display。
 *
 * 引擎只负责把 Activity 投到目标 Display；真正的 View 由
 * [ClusterActivityHost] 交给 [ClusterDisplayActivity]，所以数据提交仍然走
 * 同一个 [ClusterView]，不需要另开一条通道。
 */
class ClusterActivitySurface(
    private val context: Context,
    private val display: Display,
    initialSettings: DashboardSettings,
) : ClusterSurface {

    override val displayId: Int = display.displayId
    override val isOverlay: Boolean = false

    private val ownedView: ClusterView = ClusterView(context)
    private var launchedAt: Long = 0L

    override val view: ClusterView
        get() = ClusterActivityHost.activeView() ?: ownedView

    init {
        apply(initialSettings)
    }

    override fun show() {
        val intent = Intent(context, ClusterDisplayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(ClusterDisplayActivity.EXTRA_FROM_ENGINE, true)
            putExtra(ClusterDisplayActivity.EXTRA_DISPLAY_ID, displayId)
        }
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(displayId)
            .toBundle()

        ClusterActivityHost.prepare(ownedView, displayId)
        launchedAt = SystemClock.uptimeMillis()
        try {
            context.startActivity(intent, options)
        } catch (e: Exception) {
            ClusterActivityHost.clear()
            throw e
        }
    }

    override fun dismiss() {
        ClusterActivityHost.clear()
        ClusterDisplayActivity.finishCurrent(displayId)
    }

    override fun submit(t: Telemetry) {
        view.submit(t)
    }

    override fun setClock(text: String) {
        view.setClock(text)
    }

    override fun apply(settings: DashboardSettings) {
        val noBlock = settings.mode == DashboardMode.NO_BLOCK
        view.applyDashboard(
            theme = ClusterThemes.byId(settings.themeId),
            transparentBackground = noBlock,
            hideOwnGear = noBlock && settings.originalGear,
        )
    }

    override fun isShowing(): Boolean {
        if (ClusterActivityHost.isAttached(displayId)) return true
        // Activity 启动需要一点时间；超时还没挂上就让看门狗重试。
        return ClusterActivityHost.isPending(displayId) &&
            SystemClock.uptimeMillis() - launchedAt < START_TIMEOUT_MS
    }

    private companion object {
        const val START_TIMEOUT_MS = 6_000L
    }
}

/**
 * Activity 还在时的投屏路径。
 *
 * Presentation 的 Window 需要 Activity 的窗口令牌，不能在 Service 里直接用；
 * 所以后台常驻的正式路径是 [ClusterOverlay]。
 */
class ClusterPresentation(
    activity: Activity,
    display: Display,
    initialSettings: DashboardSettings,
) : Presentation(activity, display), ClusterSurface {

    private lateinit var cluster: ClusterView
    private var settings: DashboardSettings = initialSettings

    override val displayId: Int
        get() = display.displayId

    override val isOverlay: Boolean = false

    override val view: ClusterView
        get() = cluster

    init {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configureWindow(settings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cluster = ClusterView(context)
        setContentView(cluster)
        apply(settings)
    }

    override fun show() {
        configureWindow(settings)
        super.show()
    }

    override fun dismiss() {
        runCatching { super.dismiss() }
    }

    override fun submit(t: Telemetry) {
        if (::cluster.isInitialized) cluster.submit(t)
    }

    override fun setClock(text: String) {
        if (::cluster.isInitialized) cluster.setClock(text)
    }

    override fun apply(settings: DashboardSettings) {
        this.settings = settings
        configureWindow(settings)
        if (!::cluster.isInitialized) return
        val noBlock = settings.mode == DashboardMode.NO_BLOCK
        cluster.applyDashboard(
            theme = ClusterThemes.byId(settings.themeId),
            transparentBackground = noBlock,
            hideOwnGear = noBlock && settings.originalGear,
        )
    }

    override fun isShowing(): Boolean = super.isShowing()

    /**
     * Presentation 的 Window 同样是一层显示层；格式写死 TRANSLUCENT 时
     * FULLSCREEN 只能盖住底图，原厂仪表仍会透出来，所以按模式分开设置。
     */
    private fun configureWindow(settings: DashboardSettings) {
        val w = window ?: return
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (settings.mode == DashboardMode.NO_BLOCK) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setFormat(PixelFormat.TRANSLUCENT)
            w.setDimAmount(0f)
        } else {
            w.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            w.setFormat(PixelFormat.OPAQUE)
        }
    }
}

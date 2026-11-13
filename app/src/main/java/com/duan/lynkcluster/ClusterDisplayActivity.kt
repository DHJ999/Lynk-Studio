package com.duan.lynkcluster

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * 副屏 Activity 的独立实现。
 *
 * 由 [DashboardEngine] 用 `ActivityOptions.setLaunchDisplayId()` 直接投到仪表屏，
 * 不含任何原厂/系统权限。Activity 的窗口本身就是一层显示层，所以：
 * - 全屏模式：ClusterView 画不透明底图，把原厂仪表盖住；
 * - 不遮挡模式：窗口透明，只画表盘元素。
 *
 * 普通应用在部分车机上会被系统拒绝“把 Activity 放到副屏”，失败会抛异常，
 * 控制台会把异常原样显示出来，方便判断是权限问题还是屏幕问题。
 */
class ClusterDisplayActivity : Activity() {

    private lateinit var cluster: ClusterView

    /**
     * 用启动 Intent 里带的 id 做身份判断，比从 WindowManager 反查可靠。
     * Android 10 及以下在副屏 Activity 里查 defaultDisplay 偶有实现差异。
     */
    private val targetDisplayId: Int by lazy {
        intent?.getIntExtra(EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
            ?: Display.DEFAULT_DISPLAY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这块 Activity 只能由仪表引擎拉起；如果引擎在启动过程中已经放弃，
        // 直接结束，避免在副屏上留下一个没人更新的空窗口。
        val fromEngine = intent?.getBooleanExtra(EXTRA_FROM_ENGINE, false) == true
        if (fromEngine && !ClusterActivityHost.hasPending(targetDisplayId)) {
            finish()
            return
        }

        // 先读配置再配窗口：全屏模式要求窗口本身不透明，否则原厂仪表会透出来。
        val settings = DashboardSettings.load(this)
        configureWindow(settings.mode)

        val noBlock = settings.mode == DashboardMode.NO_BLOCK
        cluster = ClusterActivityHost.takePending(targetDisplayId) ?: ClusterView(this)
        cluster.applyDashboard(
            theme = ClusterThemes.byId(settings.themeId),
            transparentBackground = noBlock,
            hideOwnGear = noBlock && settings.originalGear,
        )
        setContentView(cluster)

        current = WeakReference(this)
    }

    override fun onStart() {
        super.onStart()
        applyImmersive()
        ClusterActivityHost.attach(cluster, targetDisplayId)
        DashboardEngine.onActivityAttached(targetDisplayId)
    }

    /**
     * singleTask 重入时系统不会重建 Activity，只会回调这里；
     * 不在这里接住新的 View，引擎会以为画面还在，实际停在旧 View 上直到看门狗超时。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        if (!ClusterActivityHost.hasPending(targetDisplayId)) return

        val settings = DashboardSettings.load(this)
        configureWindow(settings.mode)
        val noBlock = settings.mode == DashboardMode.NO_BLOCK
        val next = ClusterActivityHost.takePending(targetDisplayId) ?: return

        val previous = cluster
        if (previous !== next) {
            ClusterActivityHost.detach(previous)
        }
        cluster = next
        cluster.applyDashboard(
            theme = ClusterThemes.byId(settings.themeId),
            transparentBackground = noBlock,
            hideOwnGear = noBlock && settings.originalGear,
        )
        setContentView(cluster)
        ClusterActivityHost.attach(cluster, targetDisplayId)
    }

    override fun onStop() {
        ClusterActivityHost.detach(cluster)
        DashboardEngine.onActivityDetached(targetDisplayId)
        super.onStop()
    }

    override fun onDestroy() {
        ClusterActivityHost.detach(cluster)
        if (current?.get() === this) current = null
        DashboardEngine.onActivityDetached(targetDisplayId)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    /**
     * 只有 [DashboardMode.NO_BLOCK] 才让窗口透明。
     *
     * 全屏模式的 View 虽然画了不透明底图，但窗口格式是 TRANSLUCENT 时
     * SurfaceFlinger 仍会按 alpha 合成，原厂仪表会从窗口的透明区域透出来；
     * 因此 FULLSCREEN 必须把窗口本身设成 OPAQUE + 黑色底，NO_BLOCK 保持原样。
     */
    private fun configureWindow(mode: DashboardMode) {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)

        if (mode == DashboardMode.NO_BLOCK) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setFormat(PixelFormat.TRANSLUCENT)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            window.setFormat(PixelFormat.OPAQUE)
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    companion object {
        const val EXTRA_FROM_ENGINE = "from_engine"
        const val EXTRA_DISPLAY_ID = "display_id"

        @Volatile
        private var current: WeakReference<ClusterDisplayActivity>? = null

        /** 引擎撤掉输出时结束副屏 Activity。 */
        fun finishCurrent(displayId: Int) {
            val activity = current?.get() ?: return
            if (activity.targetDisplayId != displayId) return
            activity.runOnUiThread { activity.finish() }
        }
    }
}

/**
 * 引擎和副屏 Activity 之间唯一的桥。
 *
 * Activity 运行在同一个进程里；引擎把要显示的 [ClusterView] 预先放进来，
 * Activity 复用它，于是遥测、时钟、换肤都还是走原来的提交路径。
 */
object ClusterActivityHost {

    @Volatile
    private var pendingView: ClusterView? = null

    @Volatile
    private var pendingDisplayId: Int = -1

    @Volatile
    private var activeView: ClusterView? = null

    @Volatile
    private var activeDisplayId: Int = -1

    fun prepare(view: ClusterView, displayId: Int) {
        pendingView = view
        pendingDisplayId = displayId
    }

    fun hasPending(displayId: Int): Boolean = pendingDisplayId == displayId

    fun takePending(displayId: Int): ClusterView? {
        if (pendingDisplayId != displayId) return null
        val view = pendingView
        pendingView = null
        pendingDisplayId = -1
        return view
    }

    fun attach(view: ClusterView, displayId: Int) {
        activeView = view
        activeDisplayId = displayId
    }

    fun detach(view: ClusterView) {
        if (activeView !== view) return
        activeView = null
        activeDisplayId = -1
    }

    fun clear() {
        pendingView = null
        pendingDisplayId = -1
    }

    fun isAttached(displayId: Int): Boolean =
        activeView != null && activeDisplayId == displayId

    fun isPending(displayId: Int): Boolean = pendingDisplayId == displayId

    fun activeView(): ClusterView? = activeView
}

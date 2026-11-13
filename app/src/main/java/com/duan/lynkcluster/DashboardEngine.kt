package com.duan.lynkcluster

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isDefault: Boolean,
    val isPrivate: Boolean,
    val state: Int,
)

data class DashboardStatus(
    val running: Boolean,
    val enabled: Boolean,
    val targetName: String?,
    val targetId: Int?,
    val displays: List<DisplayInfo>,
    val error: String?,
    val mode: DashboardMode,
    val themeLabel: String,
    val usingOverlay: Boolean,
    val usingActivity: Boolean,
    val surfaceShowing: Boolean,
    val outputMode: OutputMode,
    val dataSource: String,
    val dataReady: Boolean,
    val dataDetail: String?,
)

/**
 * 仪表盘模块的核心状态机。
 *
 * 生命周期与 UI 解耦：服务启动它，主界面只负责改配置和看状态。
 * 输出优先走悬浮层（后台可用），失败时如果有 Activity 再回退到 Presentation。
 */
object DashboardEngine {

    private const val RECLAIM_INTERVAL_MS = 2_000L
    private const val CLOCK_INTERVAL_MS = 10_000L

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val listeners = LinkedHashSet<(DashboardStatus) -> Unit>()

    private lateinit var appContext: Context
    private var displayManager: DisplayManager? = null
    private var initialized = false

    private var settings = DashboardSettings()
    private var hostActivity: Activity? = null

    private var running = false
    private var usingOverlay = false
    private var usingActivity = false
    private var surface: ClusterSurface? = null
    private var source: TelemetrySource? = null
    private var sourceState = SourceState("未启动", ready = false)
    private var lastTelemetry: Telemetry? = null

    /** 混合源是否真的出过实车帧；出过就不再允许静默回退模拟数据。 */
    private var hybridEverLive = false
    private var clockText = ""
    private var lastError: String? = null
    private var displayListenerRegistered = false

    private val clockTick = object : Runnable {
        override fun run() {
            if (!running) return
            updateClock()
            handler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    private val reclaim = object : Runnable {
        override fun run() {
            if (!running || !settings.autoReclaim) return
            ensureAttached(force = false)
            syncProjector()
            handler.postDelayed(this, RECLAIM_INTERVAL_MS)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            ensureAttached(force = true)
            syncProjector()
        }

        override fun onDisplayRemoved(displayId: Int) {
            ensureAttached(force = true)
            syncProjector()
        }

        override fun onDisplayChanged(displayId: Int) {
            ensureAttached(force = false)
            syncProjector()
        }
    }

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        settings = DashboardSettings.load(appContext)
        initialized = true
    }

    /** Presentation 回退路径需要 Activity 的窗口令牌；OnDestroy 时传 null。 */
    fun setHostActivity(activity: Activity?) {
        hostActivity = activity
    }

    fun addListener(listener: (DashboardStatus) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (DashboardStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun start(context: Context, newSettings: DashboardSettings = DashboardSettings.load(context)) {
        init(context)
        settings = newSettings

        if (!settings.enabled) {
            stop()
            return
        }

        if (running) {
            update(newSettings)
            return
        }

        running = true
        lastError = null
        if (source == null) {
            startSource()
        }

        registerDisplayListener()
        handler.removeCallbacks(clockTick)
        updateClock()
        handler.post(clockTick)
        scheduleReclaim()
        ensureAttached(force = true)
        syncProjector()
        notifyListeners()
    }

    fun update(newSettings: DashboardSettings) {
        if (!initialized) return
        val previous = settings
        settings = newSettings

        if (!settings.enabled) {
            stop()
            return
        }
        if (!running) {
            notifyListeners()
            return
        }

        if (!previous.autoReclaim && settings.autoReclaim) {
            scheduleReclaim()
        } else if (previous.autoReclaim && !settings.autoReclaim) {
            handler.removeCallbacks(reclaim)
        }

        if (previous.dataMode != settings.dataMode) {
            startSource()
        }

        val target = pickCluster(currentDisplays())
        if (target == null) {
            detachSurface()
            lastError = "未检测到可用的仪表屏"
            notifyListeners()
            return
        }

        val current = surface
        val modeChanged = previous.mode != settings.mode ||
            previous.topmost != settings.topmost ||
            previous.outputMode != settings.outputMode ||
            previous.targetDisplayId != settings.targetDisplayId
        if (current == null || !current.isShowing() ||
            current.displayId != target.displayId || modeChanged
        ) {
            ensureAttached(force = true)
        } else {
            current.apply(settings)
            lastError = null
        }
        syncProjector()
        notifyListeners()
    }

    fun refresh() {
        if (!running) {
            notifyListeners()
            return
        }
        ensureAttached(force = true)
        syncProjector()
    }

    fun stop() {
        val shouldNotify = running || surface != null
        running = false
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(reclaim)
        unregisterDisplayListener()
        source?.stop()
        source = null
        sourceState = SourceState("未启动", ready = false)
        lastTelemetry = null
        ClusterProjector.disable()
        detachSurface()
        if (shouldNotify) {
            lastError = null
            notifyListeners()
        }
    }

    fun status(): DashboardStatus {
        val dm = displayManager
        val displays = currentDisplays().map { it.toInfo() }
        val target = pickCluster(currentDisplays())
        return DashboardStatus(
            running = running,
            enabled = settings.enabled,
            targetName = target?.name,
            targetId = target?.displayId,
            displays = displays,
            error = lastError,
            mode = settings.mode,
            themeLabel = ClusterThemes.byId(settings.themeId).label,
            usingOverlay = usingOverlay,
            usingActivity = usingActivity,
            surfaceShowing = surface?.isShowing() == true,
            outputMode = settings.outputMode,
            dataSource = sourceState.label,
            dataReady = sourceState.ready,
            dataDetail = sourceState.detail,
        )
    }

    /**
     * 按设置启动数据源。
     *
     * REAL（真实数据）：厂商 CarProxy 与标准 VHAL 同时启动并逐字段合并，
     *       只有一路可用时自然退化成那一路，两路都不可用才回退模拟，
     *       状态里保留失败原因。
     * SIM（模拟数据）：始终模拟，供无车环境预览界面。
     */
    private fun startSource() {
        source?.stop()
        source = null
        hybridEverLive = false
        sourceState = SourceState("未启动", ready = false, connecting = true)

        // 切源期间先显示 IDLE，避免把上一路（尤其是 SIM）的旧帧留在仪表上。
        lastTelemetry = Telemetry.IDLE
        surface?.submit(Telemetry.IDLE)

        when (settings.dataMode) {
            DataMode.SIM -> startMock("已选择模拟数据")
            DataMode.REAL -> startHybrid()
        }
    }

    /**
     * AUTO 模式的混合源：两路同时启动、逐字段合并。
     *
     * 只有一路能起来时 Hybrid 会用它自己的字段继续出帧，只有两路都失败
     * 才会走到这里传进来的回退回调。
     */
    private fun startHybrid() {
        val hybrid = HybridTelemetrySource.create(appContext)
        startTelemetry(hybrid) { detail ->
            if (hybridEverLive) {
                // 实车链路曾经出过真实帧：断连后保持在 -- 等恢复，
                // 不能拿模拟值顶上（验收时看到假数字比 -- 更糟）。
                sourceState = SourceState(
                    HybridTelemetrySource.LABEL,
                    ready = false,
                    connecting = false,
                    detail = "车辆信号已断开（$detail），保持 --，请在控制台重新应用以重连",
                )
                lastTelemetry = Telemetry.IDLE
                surface?.submit(Telemetry.IDLE)
                notifyListeners()
            } else {
                startMock(
                    "厂商 CarProxy 与标准 VHAL 均不可用（$detail），已回退模拟数据",
                )
            }
        }
    }

    private fun startTelemetry(candidate: TelemetrySource, onFail: (String) -> Unit) {
        source = candidate
        candidate.start(
            { telemetry ->
                if (candidate is HybridTelemetrySource &&
                    telemetry.source == Telemetry.SOURCE_CAR
                ) {
                    hybridEverLive = true
                }
                lastTelemetry = telemetry
                surface?.submit(telemetry)
            },
            { state ->
                if (source !== candidate) return@start
                sourceState = state
                if (!state.ready) {
                    // 数据源不再就绪时清掉旧帧，避免画面停在断开前的最后一帧。
                    lastTelemetry = Telemetry.IDLE
                    surface?.submit(Telemetry.IDLE)
                }
                if (!state.ready && !state.connecting) {
                    source = null
                    candidate.stop()
                    onFail(state.detail ?: "原因未知")
                } else {
                    notifyListeners()
                }
            },
        )
    }

    private fun startMock(reason: String?) {
        source?.stop()
        sourceState = SourceState("SIM", ready = true, detail = reason)
        val mock = MockTelemetrySource()
        source = mock
        mock.start(
            { telemetry ->
                lastTelemetry = telemetry
                surface?.submit(telemetry)
            },
            { state ->
                if (source === mock) {
                    sourceState = state
                    notifyListeners()
                }
            },
        )
        notifyListeners()
    }

    // ---------------- 输出表面 ----------------

    private fun ensureAttached(force: Boolean) {
        if (!running) return
        val dm = displayManager ?: return
        val target = pickCluster(currentDisplays())
        if (target == null) {
            detachSurface()
            lastError = "未检测到可用的仪表屏"
            notifyListeners()
            return
        }

        val current = surface
        if (!force && current != null && current.isShowing() &&
            current.displayId == target.displayId
        ) {
            current.apply(settings)
            return
        }
        attach(target)
    }

    /**
     * display 2 只是 Android 侧的渲染目标；物理仪表还由 QNX 控制。
     * 只有目标屏不是主屏时，才通知 DimNaviService 打开 DisplayCluster。
     */
    private fun syncProjector() {
        val target = pickCluster(currentDisplays())
        val shouldTakeover = running &&
            target != null &&
            target.displayId != Display.DEFAULT_DISPLAY
        if (shouldTakeover) {
            if (!ClusterProjector.isActive()) {
                Thread {
                    ClusterProjector.enable()
                }.apply { isDaemon = true }.start()
            }
        } else {
            ClusterProjector.disable()
        }
    }

    private fun attach(target: Display) {
        detachSurface()
        lastError = null

        val canOverlay = Settings.canDrawOverlays(appContext)
        val host = hostActivity?.takeIf { !it.isFinishing }

        // 用户手动指定了输出路径：只走那一条，失败原因直接显示出来。
        when (settings.outputMode) {
            OutputMode.OVERLAY -> {
                if (!canOverlay) {
                    lastError = "悬浮层需要悬浮窗权限：请先点“授予悬浮窗权限”"
                    notifyListeners()
                    return
                }
                try {
                    showOverlay(target)
                } catch (e: Exception) {
                    lastError = "悬浮层输出失败：${describe(e)}"
                    notifyListeners()
                }
                return
            }

            OutputMode.PRESENTATION -> {
                if (host == null) {
                    lastError = "投屏窗口需要主界面处于打开状态；后台请改用悬浮层或副屏 Activity"
                    notifyListeners()
                    return
                }
                try {
                    showPresentation(host, target)
                } catch (e: Exception) {
                    lastError = "投屏窗口失败：${describe(e)}"
                    notifyListeners()
                }
                return
            }

            OutputMode.ACTIVITY -> {
                try {
                    showActivity(target)
                } catch (e: Exception) {
                    lastError = "副屏 Activity 启动失败：${describe(e)}"
                    notifyListeners()
                }
                return
            }

            OutputMode.AUTO -> Unit
        }

        // 自动：按“最可能成功”的顺序逐条尝试。
        // 顶层显示 / 不遮挡模式优先悬浮层；否则先试传统的 Presentation。
        val preferOverlay = settings.topmost ||
            settings.mode == DashboardMode.NO_BLOCK ||
            host == null

        var overlayError: String? = null
        if (preferOverlay && canOverlay) {
            try {
                showOverlay(target)
                return
            } catch (e: Exception) {
                overlayError = "悬浮层输出失败：${describe(e)}"
                lastError = overlayError
            }
        }

        if (host != null) {
            try {
                showPresentation(host, target)
                lastError = when {
                    !canOverlay && settings.topmost ->
                        "未授予悬浮窗权限，已回退到投屏窗口；退出主界面后可能断开"
                    !canOverlay && settings.mode == DashboardMode.NO_BLOCK ->
                        "不遮挡模式需要悬浮窗权限，当前回退到投屏窗口"
                    overlayError != null -> "悬浮层被系统拒绝，已回退到投屏窗口"
                    else -> null
                }
                notifyListeners()
                return
            } catch (e: Exception) {
                lastError = appendError("投屏窗口失败：${describe(e)}")
            }
        }

        // Presentation 不可用或失败时，再试一次悬浮层（例如顶层显示关闭、但
        // Activity 已经退出，host 为空时上面的 preferOverlay 已经覆盖）。
        if (!preferOverlay && canOverlay) {
            try {
                showOverlay(target)
                return
            } catch (e: Exception) {
                lastError = appendError("悬浮层输出失败：${describe(e)}")
            }
        }

        // 最后一条：副屏 Activity。它不需要 Activity 宿主，
        // 也不依赖悬浮窗权限，但能否落到副屏由车机策略决定。
        try {
            showActivity(target)
            return
        } catch (e: Exception) {
            lastError = appendError("副屏 Activity 启动失败：${describe(e)}")
        }

        if (lastError == null) {
            lastError = "三条输出路径都不可用：请检查悬浮窗权限和仪表屏是否暴露给第三方应用"
        } else {
            lastError = appendError("三条输出路径都失败")
        }
        notifyListeners()
    }

    private fun showOverlay(target: Display) {
        val overlay = ClusterOverlay(appContext, target, settings)
        overlay.show()
        surface = overlay
        usingOverlay = true
        usingActivity = false
        lastError = null
        pushCurrentFrame()
        notifyListeners()
    }

    private fun showPresentation(host: Activity, target: Display) {
        val presentation = ClusterPresentation(host, target, settings)
        presentation.show()
        surface = presentation
        usingOverlay = false
        usingActivity = false
        lastError = null
        pushCurrentFrame()
        notifyListeners()
    }

    private fun showActivity(target: Display) {
        val activity = ClusterActivitySurface(appContext, target, settings)
        activity.show()
        surface = activity
        usingOverlay = false
        usingActivity = true
        lastError = null
        pushCurrentFrame()
        notifyListeners()
    }

    private fun detachSurface() {
        val current = surface
        surface = null
        usingOverlay = false
        usingActivity = false
        runCatching { current?.dismiss() }
    }

    private fun pushCurrentFrame() {
        surface?.setClock(clockText)
        lastTelemetry?.let { surface?.submit(it) }
    }

    private fun appendError(message: String): String =
        listOfNotNull(lastError, message).joinToString("\n")

    // ---------------- 副屏 Activity 回调 ----------------

    fun onActivityAttached(displayId: Int) {
        if (!running) return
        if (surface !is ClusterActivitySurface) return
        usingActivity = true
        lastError = null
        surface?.apply(settings)
        pushCurrentFrame()
        notifyListeners()
    }

    fun onActivityDetached(displayId: Int) {
        if (!running) return
        val current = surface
        if (current !is ClusterActivitySurface || current.displayId != displayId) return
        usingActivity = false
        notifyListeners()
    }

    private fun describe(e: Exception): String =
        e.javaClass.simpleName + (e.message?.let { " · $it" } ?: "")

    // ---------------- 定时任务 ----------------

    private fun scheduleReclaim() {
        handler.removeCallbacks(reclaim)
        if (settings.autoReclaim) {
            handler.postDelayed(reclaim, RECLAIM_INTERVAL_MS)
        }
    }

    private fun updateClock() {
        val text = clockFormat.format(Date())
        if (text == clockText) return
        clockText = text
        surface?.setClock(text)
    }

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        displayManager?.registerDisplayListener(displayListener, handler)
        displayListenerRegistered = true
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        displayManager?.unregisterDisplayListener(displayListener)
        displayListenerRegistered = false
    }

    // ---------------- 屏幕识别 ----------------

    /**
     * 部分车机把 1920×720 仪表屏放在 presentation display 组里，普通
     * `DisplayManager.displays` 可能只返回主屏。这里合并三种枚举方式，
     * 避免 OEM 的 display 可见性策略导致应用误判“没有副屏”。
     */
    private fun currentDisplays(): Array<Display> {
        val dm = displayManager ?: return emptyArray()
        val found = LinkedHashMap<Int, Display>()

        fun add(displays: Array<Display>?) {
            displays?.forEach { found[it.displayId] = it }
        }

        add(dm.displays)
        add(
            runCatching {
                dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            }.getOrNull(),
        )
        // 某些车机对 getDisplays() 做了额外过滤，但按 id 查询仍可用。
        for (id in 0..MAX_DISPLAY_ID) {
            runCatching { dm.getDisplay(id) }.getOrNull()?.let { found[it.displayId] = it }
        }
        return found.values.toTypedArray()
    }

    private fun pickCluster(displays: Array<Display>): Display? {
        // 用户在控制台手动指定的目标屏优先；不在线时再退回自动识别。
        val preferred = settings.targetDisplayId
        if (preferred != DashboardSettings.AUTO_DISPLAY_ID &&
            preferred != Display.DEFAULT_DISPLAY
        ) {
            displays.firstOrNull { it.displayId == preferred }?.let { return it }
        }

        val candidates = displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        if (candidates.isEmpty()) return null

        candidates.firstOrNull {
            val m = it.mode ?: return@firstOrNull false
            m.physicalWidth == 1920 && m.physicalHeight == 720
        }?.let { return it }

        candidates.firstOrNull { d ->
            val n = d.name.lowercase(Locale.ROOT)
            n.contains("cluster") || n.contains("instrument") ||
                n.contains("dash") || n.contains("meter")
        }?.let { return it }

        return candidates.firstOrNull { (it.flags and Display.FLAG_PRESENTATION) != 0 }
            ?: candidates.first()
    }

    private fun Display.toInfo(): DisplayInfo {
        val m = mode
        return DisplayInfo(
            id = displayId,
            name = name,
            width = m?.physicalWidth ?: 0,
            height = m?.physicalHeight ?: 0,
            isDefault = displayId == Display.DEFAULT_DISPLAY,
            isPrivate = (flags and Display.FLAG_PRIVATE) != 0,
            state = state,
        )
    }

    private fun notifyListeners() {
        val snapshot = status()
        listeners.toList().forEach { it(snapshot) }
    }

    private const val MAX_DISPLAY_ID = 8
}

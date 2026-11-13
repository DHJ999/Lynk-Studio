package com.duan.lynkcluster

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 仪表盘控制台。
 *
 * 覆盖仪表盘配置的核心项：启用、主题、全屏/不遮挡、
 * 被原车切走自动切回、开机自启、顶层显示、原车挡位。
 * 真正的显示逻辑在 [DashboardEngine]，由 [DashboardService] 保活。
 */
class MainActivity : Activity() {

    private lateinit var settings: DashboardSettings
    private var status: DashboardStatus? = null
    private var updatingUi = false
    private var lastOverlayPermission = false

    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var errorView: TextView
    private lateinit var permissionHint: TextView
    private lateinit var screenList: TextView
    private lateinit var modeHint: TextView
    private lateinit var targetRow: LinearLayout
    private lateinit var targetHint: TextView

    private lateinit var enabledSwitch: Switch
    private lateinit var autoStartSwitch: Switch
    private lateinit var autoReclaimSwitch: Switch
    private lateinit var topmostSwitch: Switch
    private lateinit var originalGearSwitch: Switch

    private val modeButtons = LinkedHashMap<String, TextView>()
    private val themeButtons = LinkedHashMap<String, TextView>()
    private val outputButtons = LinkedHashMap<String, TextView>()
    private val dataModeButtons = LinkedHashMap<String, TextView>()

    private var targetSignature = ""
    private var pendingOneClick = false

    private val engineListener: (DashboardStatus) -> Unit = { s ->
        status = s
        runOnUiThread { updateUi() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = DashboardSettings.load(this)
        lastOverlayPermission = Settings.canDrawOverlays(this)
        DashboardEngine.init(applicationContext)

        buildUi()
        DashboardEngine.setHostActivity(this)
        DashboardEngine.addListener(engineListener)
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        DashboardEngine.setHostActivity(this)
        DashboardEngine.addListener(engineListener)

        val canOverlay = Settings.canDrawOverlays(this)
        if (settings.enabled) {
            if (!DashboardEngine.status().running) {
                DashboardService.start(this)
            } else if (canOverlay != lastOverlayPermission) {
                // 刚从系统设置授权回来：从投屏窗口切到悬浮层。
                DashboardEngine.refresh()
            }
        }
        lastOverlayPermission = canOverlay
        if (pendingOneClick && canOverlay) {
            pendingOneClick = false
            oneClick()
        }
        updateUi()
    }

    override fun onDestroy() {
        DashboardEngine.removeListener(engineListener)
        DashboardEngine.setHostActivity(null)
        super.onDestroy()
    }

    // ---------------- 界面 ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(40), dp(56), dp(48))
            setBackgroundColor(Palette.BG)
        }

        root.addView(TextView(this).apply {
            text = "仪表盘"
            setTextColor(Palette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        root.addView(TextView(this).apply {
            text = "把自定义仪表界面送到副屏；支持换肤、全屏覆盖和不遮挡模式"
            setTextColor(Palette.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(6), 0, dp(24))
        })

        val statusCard = makeCard()
        statusTitle = TextView(this).apply {
            setTextColor(Palette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusCard.addView(statusTitle)

        statusDetail = TextView(this).apply {
            setTextColor(Palette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        }
        statusCard.addView(statusDetail)

        errorView = TextView(this).apply {
            setTextColor(Palette.AMBER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        statusCard.addView(errorView)

        permissionHint = TextView(this).apply {
            text = "未授予悬浮窗权限：后台常驻、不遮挡模式会失败；主界面打开时还能回退到投屏窗口。"
            setTextColor(Palette.AMBER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        statusCard.addView(permissionHint)
        addCard(root, statusCard)

        root.addView(sectionTitle("运行"))

        val runCard = makeCard()
        enabledSwitch = addToggle(
            runCard,
            "启用仪表盘",
            "在副屏上显示自定义仪表；关闭后停止前台服务",
            settings.enabled,
        ) { value ->
            if (value && settings.mode == DashboardMode.FULLSCREEN) {
                withSafetyAck {
                    settings = settings.copy(enabled = true)
                    commit()
                }
            } else {
                settings = settings.copy(enabled = value)
                commit()
            }
        }
        autoStartSwitch = addToggle(
            runCard,
            "开机自启",
            "开机广播后自动拉起仪表盘服务（需同时启用仪表盘）",
            settings.autoStart,
        ) { value ->
            settings = settings.copy(autoStart = value)
            commit()
        }
        autoReclaimSwitch = addToggle(
            runCard,
            "被原车切走自动切回",
            "每 2 秒检查一次，窗口被原车或系统移除后重新挂上",
            settings.autoReclaim,
        ) { value ->
            settings = settings.copy(autoReclaim = value)
            commit()
        }
        topmostSwitch = addToggle(
            runCard,
            "顶层显示",
            "开启后强制用悬浮层输出，能压住普通窗口并在退出主界面后保持；关闭时优先投屏窗口",
            settings.topmost,
        ) { value ->
            settings = settings.copy(topmost = value)
            commit()
        }
        originalGearSwitch = addToggle(
            runCard,
            "原车挡位显示",
            "仅不遮挡模式生效：隐藏本应用的挡位，露出原厂挡位",
            settings.originalGear,
        ) { value ->
            settings = settings.copy(originalGear = value)
            commit()
        }
        addCard(root, runCard)

        root.addView(sectionTitle("车辆数据"))
        val dataCard = makeCard()
        dataModeButtons.putAll(
            addChoiceRow(
                dataCard,
                DataMode.entries.map { it.id to it.label },
                settings.dataMode.id,
            ) { id ->
                settings = settings.copy(dataMode = DataMode.fromId(id))
                commit(restart = true)
            }
        )
        dataCard.addView(TextView(this).apply {
            text = "真实数据自动合并厂商与标准接口的可用信号（只订阅、不写车辆属性）；" +
                "拿不到的字段显示 --。模拟数据仅用于无车环境调试界面。"
            setTextColor(Palette.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, dp(4))
        })
        dataCard.addView(buttonRow(
            makeButton("车辆信号诊断", primary = false) { runSignalProbe() },
        ))
        addCard(root, dataCard)

        root.addView(sectionTitle("显示模式"))
        val modeCard = makeCard()
        modeButtons.putAll(
            addChoiceRow(
                modeCard,
                listOf(
                    DashboardMode.FULLSCREEN.id to DashboardMode.FULLSCREEN.label,
                    DashboardMode.NO_BLOCK.id to DashboardMode.NO_BLOCK.label,
                ),
                settings.mode.id,
            ) { id ->
                val next = DashboardMode.fromId(id)
                if (next == DashboardMode.FULLSCREEN) {
                    withSafetyAck {
                        settings = settings.copy(mode = next)
                        commit()
                    }
                } else {
                    settings = settings.copy(mode = next)
                    commit()
                }
            }
        )
        modeHint = TextView(this).apply {
            setTextColor(Palette.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        }
        modeCard.addView(modeHint)
        addCard(root, modeCard)

        root.addView(sectionTitle("输出方式"))
        val outputCard = makeCard()
        outputButtons.putAll(
            addChoiceRow(
                outputCard,
                OutputMode.entries.map { it.id to it.label },
                settings.outputMode.id,
            ) { id ->
                settings = settings.copy(outputMode = OutputMode.fromId(id))
                commit(restart = true)
            }
        )
        outputCard.addView(TextView(this).apply {
            text = "自动：悬浮层 → 投屏窗口 → 副屏 Activity。车机上哪条能过就用哪条；" +
                "失败原因会显示在状态卡。"
            setTextColor(Palette.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        })
        addCard(root, outputCard)

        root.addView(sectionTitle("主题"))
        val themeCard = makeCard()
        themeButtons.putAll(
            addChoiceRow(
                themeCard,
                ClusterThemes.ALL.map { it.id to it.label },
                settings.themeId,
            ) { id ->
                settings = settings.copy(themeId = ClusterThemes.byId(id).id)
                commit()
            }
        )
        addCard(root, themeCard)

        val actions = makeCard()
        actions.addView(buttonRow(
            makeButton("一键接管仪表屏", primary = true) { oneClick() },
        ))
        actions.addView(buttonRow(
            makeButton("应用并刷新", primary = false) { commit(restart = true) },
            makeButton("停止仪表盘", primary = false) {
                settings = settings.copy(enabled = false)
                commit()
            },
        ))
        actions.addView(buttonRow(
            makeButton("授予悬浮窗权限", primary = false) { requestOverlay() },
            makeButton("重新检测", primary = false) {
                DashboardEngine.refresh()
                updateUi()
            },
        ))
        actions.addView(buttonRow(
            makeButton("复制诊断信息", primary = false) { copyDiagnostics() },
        ))
        addCard(root, actions)

        root.addView(sectionTitle("检测到的屏幕"))
        val screenCard = makeCard()
        targetHint = TextView(this).apply {
            text = "目标屏（自动会优先 1920×720、名称含 cluster/instrument 的副屏）"
            setTextColor(Palette.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(8))
        }
        screenCard.addView(targetHint)

        targetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        screenCard.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(targetRow)
        })

        screenList = TextView(this).apply {
            setTextColor(Palette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        }
        screenCard.addView(screenList)
        addCard(root, screenCard)

        root.addView(TextView(this).apply {
            text = "说明\n" +
                "• 这是显示层覆盖，不是替换原厂仪表程序；原厂仪表仍在运行。\n" +
                "• 普通应用无法停用原厂仪表，也无法保证压过系统级窗口。\n" +
                "• 车辆数据优先走厂商 CarProxy；缺失信号显示 --，回退模拟时标 SIM，不会把模拟值当真车数据。\n" +
                "• 厂商 CarProxy 只订阅车速、转速、水温、挡位等只读属性，不调用 setIntProperty。"
            setTextColor(Palette.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(0, dp(24), 0, dp(8))
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })
    }

    private fun updateUi() {
        updatingUi = true
        try {
            val canOverlay = Settings.canDrawOverlays(this)
            val s = status

            val running = s?.running == true
            val showing = s?.surfaceShowing == true
            statusTitle.text = when {
                running && showing -> "仪表盘运行中"
                running -> "仪表盘已启动，等待副屏"
                settings.enabled -> "正在启动…"
                else -> "已停止"
            }
            statusTitle.setTextColor(
                when {
                    running && showing -> Palette.PRIMARY
                    running || settings.enabled -> Palette.AMBER
                    else -> Palette.TEXT_SECONDARY
                }
            )

            statusDetail.text = buildString {
                append("目标屏  ")
                if (s?.targetId != null) {
                    append("${s.targetName}  (id ${s.targetId})")
                } else {
                    append("未找到副屏")
                }
                append("\n输出方式  ")
                append(
                    when {
                        s?.usingOverlay == true -> "悬浮层（后台可用）"
                        s?.usingActivity == true -> "副屏 Activity"
                        s?.surfaceShowing == true -> "投屏窗口（Presentation）"
                        else -> "未连接"
                    }
                )
                append("\n输出设置  ")
                append((s?.outputMode ?: settings.outputMode).label)
                append("\n主题 / 模式  ")
                append(s?.themeLabel ?: ClusterThemes.byId(settings.themeId).label)
                append(" · ")
                append((s?.mode ?: settings.mode).label)
                append("\n车辆数据  ")
                append(s?.dataSource ?: "未启动")
                append(if (s?.dataReady == true) " · 已就绪" else " · 未就绪")
                s?.dataDetail?.takeIf { it.isNotBlank() }?.let {
                    append("\n  ")
                    append(it)
                }
                append("\n屏幕数量  ${s?.displays?.size ?: 0}")
            }

            errorView.visibility = if (s?.error.isNullOrBlank()) View.GONE else View.VISIBLE
            errorView.text = s?.error.orEmpty()

            permissionHint.visibility = if (canOverlay) View.GONE else View.VISIBLE

            modeHint.text = if (settings.mode == DashboardMode.NO_BLOCK && !canOverlay) {
                "不遮挡模式需要悬浮窗权限；未授权时会回退成整块窗口。"
            } else if (settings.mode == DashboardMode.NO_BLOCK) {
                "只画表盘，原厂仪表从透明区域透出；可隐藏本应用挡位。"
            } else {
                "用 1920×720 不透明底图覆盖整块仪表屏。"
            }

            enabledSwitch.isChecked = settings.enabled
            autoStartSwitch.isChecked = settings.autoStart
            autoReclaimSwitch.isChecked = settings.autoReclaim
            topmostSwitch.isChecked = settings.topmost
            originalGearSwitch.isChecked = settings.originalGear

            refreshChips()
            rebuildTargetChips(s?.displays.orEmpty())

            screenList.text = if (s == null || s.displays.isEmpty()) {
                "尚未读取"
            } else {
                s.displays.joinToString("\n\n") { d ->
                    val tags = buildList {
                        if (d.isDefault) add("主屏")
                        if (d.isPrivate) add("private")
                        if (!d.isDefault && !d.isPrivate) add("public")
                    }
                    "id ${d.id}  ${d.width}×${d.height}  ${d.name}\n" +
                        "  ${tags.joinToString(" · ")}  state=${stateLabel(d.state)}"
                }
            }
        } finally {
            updatingUi = false
        }
    }

    private fun refreshChips() {
        modeButtons.forEach { (id, chip) ->
            val selected = id == settings.mode.id
            chip.setTextColor(if (selected) Palette.BG else Palette.TEXT_SECONDARY)
            chip.background = chipBackground(selected)
        }
        themeButtons.forEach { (id, chip) ->
            val selected = id == settings.themeId
            chip.setTextColor(if (selected) Palette.BG else Palette.TEXT_SECONDARY)
            chip.background = chipBackground(selected)
        }
        outputButtons.forEach { (id, chip) ->
            val selected = id == settings.outputMode.id
            chip.setTextColor(if (selected) Palette.BG else Palette.TEXT_SECONDARY)
            chip.background = chipBackground(selected)
        }
        dataModeButtons.forEach { (id, chip) ->
            val selected = id == settings.dataMode.id
            chip.setTextColor(if (selected) Palette.BG else Palette.TEXT_SECONDARY)
            chip.background = chipBackground(selected)
        }
    }

    /**
     * 目标屏在运行中可能增减，所以按 Display 列表重建一行小芯片。
     * 自动 (-1) 永远排第一，后面是每块副屏。
     */
    private fun rebuildTargetChips(displays: List<DisplayInfo>) {
        val signature = displays.joinToString("|") {
            "${it.id}:${it.name}:${it.width}x${it.height}:${it.state}"
        }
        if (signature == targetSignature && targetRow.childCount > 0) {
            updateTargetChipSelection()
            return
        }
        targetSignature = signature
        targetRow.removeAllViews()

        targetRow.addView(targetChip(
            label = "自动",
            selected = settings.targetDisplayId == DashboardSettings.AUTO_DISPLAY_ID,
            onClick = {
                settings = settings.copy(targetDisplayId = DashboardSettings.AUTO_DISPLAY_ID)
                commit(restart = true)
            },
        ))

        displays.filterNot { it.isDefault }.forEach { display ->
            targetRow.addView(targetChip(
                label = "id ${display.id} · ${display.width}×${display.height}",
                selected = settings.targetDisplayId == display.id,
                onClick = {
                    settings = settings.copy(targetDisplayId = display.id)
                    commit(restart = true)
                },
            ))
        }
        if (displays.none { !it.isDefault }) {
            targetHint.text = "没有检测到副屏。车机可能没有把仪表屏暴露给 Android，或被原厂进程独占。"
            targetHint.setTextColor(Palette.AMBER)
        } else {
            targetHint.text = "目标屏（自动会优先 1920×720、名称含 cluster/instrument 的副屏）"
            targetHint.setTextColor(Palette.TEXT_MUTED)
        }
    }

    private fun updateTargetChipSelection() {
        for (i in 0 until targetRow.childCount) {
            val chip = targetRow.getChildAt(i) as? TextView ?: continue
            val selected = if (i == 0) {
                settings.targetDisplayId == DashboardSettings.AUTO_DISPLAY_ID
            } else {
                targetRow.getChildAt(i).tag == settings.targetDisplayId
            }
            chip.setTextColor(if (selected) Palette.BG else Palette.TEXT_SECONDARY)
            chip.background = chipBackground(selected)
        }
    }

    private fun targetChip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            tag = if (label == "自动") {
                DashboardSettings.AUTO_DISPLAY_ID
            } else {
                label.substringAfter("id ").substringBefore(" ").toIntOrNull()
                    ?: DashboardSettings.AUTO_DISPLAY_ID
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setTextColor(if (selected) Palette.BG else Palette.TEXT_SECONDARY)
            background = chipBackground(selected)
            setOnClickListener { if (!updatingUi) onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        }

    private fun commit(restart: Boolean = false) {
        settings.save(this)
        DashboardEngine.update(settings)

        if (settings.enabled) {
            DashboardService.start(this)
            if (restart) DashboardEngine.refresh()
        } else {
            DashboardService.stop(this)
        }
        updateUi()
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            DashboardEngine.refresh()
            updateUi()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    /** 一键接管：有悬浮窗权限就直接启用；没有就先去授权，回来继续。 */
    private fun oneClick() {
        if (!Settings.canDrawOverlays(this)) {
            pendingOneClick = true
            requestOverlay()
            return
        }
        if (settings.mode == DashboardMode.FULLSCREEN) {
            withSafetyAck { startOneClick() }
        } else {
            startOneClick()
        }
    }

    private fun startOneClick() {
        // 一键接管默认走悬浮层：主界面退出后仍然挂着，最接近“换掉仪表”的体验。
        settings = settings.copy(enabled = true, autoReclaim = true, topmost = true)
        commit(restart = true)

        val s = DashboardEngine.status()
        val message = if (s.targetId == null) {
            "已启用，但没有检测到副屏；请查看“检测到的屏幕”"
        } else {
            "已接管 id ${s.targetId} · ${s.targetName}"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * 全屏覆盖是这套方案里唯一有安全风险的模式：原厂车速、挡位、
     * 警告灯都会被盖住。只提示一次，之后不再打扰。
     */
    private fun withSafetyAck(action: () -> Unit) {
        if (settings.safetyAcknowledged) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("全屏覆盖确认")
            .setMessage(
                "全屏模式会用自定义画面盖住原厂仪表，包括车速、挡位和警告灯。\n\n" +
                    "请确认你了解风险，并只在能随时停车的环境下测试。行驶中建议先用“不遮挡仪表盘”。"
            )
            .setPositiveButton("我已了解，继续") { _, _ ->
                settings = settings.copy(safetyAcknowledged = true)
                action()
            }
            .setNegativeButton("取消") { _, _ -> updateUi() }
            .setOnCancelListener { updateUi() }
            .show()
    }

    private fun copyDiagnostics() {
        val s = status
        val actual = when {
            s?.usingOverlay == true -> "悬浮层"
            s?.usingActivity == true -> "副屏 Activity"
            s?.surfaceShowing == true -> "投屏窗口"
            else -> "未连接"
        }
        val text = buildString {
            appendLine("lynk_cluster 诊断")
            appendLine("包名: $packageName")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("悬浮窗权限: ${Settings.canDrawOverlays(this@MainActivity)}")
            appendLine("启用: ${settings.enabled}  运行: ${s?.running}  显示: ${s?.surfaceShowing}")
            appendLine("输出设置: ${settings.outputMode.label}  实际: $actual")
            appendLine("目标屏: id=${s?.targetId} ${s?.targetName}")
            appendLine("模式: ${settings.mode.label}  主题: ${ClusterThemes.byId(settings.themeId).label}")
            appendLine("数据源: ${settings.dataMode.label} → ${s?.dataSource} ready=${s?.dataReady}")
            appendLine("数据详情: ${s?.dataDetail ?: "无"}")
            appendLine("错误: ${s?.error ?: "无"}")
            appendLine("屏幕:")
            s?.displays?.forEach {
                appendLine(
                    "  id=${it.id} ${it.width}x${it.height} ${it.name} " +
                        "default=${it.isDefault} private=${it.isPrivate} state=${it.state}"
                )
            }
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("lynk_cluster 诊断", text))
        Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_SHORT).show()
    }

    /**
     * 在车机上枚举 CarService 的全部 VehicleProperty。
     * 领克不同车型的属性表不一样，必须以实车结果为准，不能靠猜。
     */
    private fun runSignalProbe() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("车辆信号诊断")
            .setMessage("正在读取 CarService 与厂商 CarProxy…")
            .setPositiveButton("关闭", null)
            .setNegativeButton("复制", null)
            .create()
        dialog.show()

        Thread {
            val report = TelemetrySources.probe(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.setMessage(report)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("车辆信号诊断", report))
                    Toast.makeText(this, "诊断结果已复制", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ---------------- 控件 ----------------

    private fun addToggle(
        parent: LinearLayout,
        title: String,
        description: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(Palette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        textCol.addView(TextView(this).apply {
            text = description
            setTextColor(Palette.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(2), 0, 0)
        })

        val switch = Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(Palette.PRIMARY)
            trackTintList = ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_checked),
                    intArrayOf(android.R.attr.state_checked),
                ),
                intArrayOf(Palette.TRACK, withAlpha(Palette.PRIMARY, 0.58f)),
            )
            setOnCheckedChangeListener { _, value ->
                if (!updatingUi) onChange(value)
            }
        }

        row.addView(
            textCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(switch)
        parent.addView(row)
        return switch
    }

    private fun addChoiceRow(
        parent: LinearLayout,
        options: List<Pair<String, String>>,
        selectedId: String,
        onSelect: (String) -> Unit,
    ): LinkedHashMap<String, TextView> {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(4), 0, 0)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val map = LinkedHashMap<String, TextView>()

        options.forEach { (id, label) ->
            val chip = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setTextColor(if (id == selectedId) Palette.BG else Palette.TEXT_SECONDARY)
                background = chipBackground(id == selectedId)
                setOnClickListener {
                    if (!updatingUi) onSelect(id)
                }
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) },
            )
            map[id] = chip
        }

        scroll.addView(row)
        parent.addView(scroll)
        return map
    }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Palette.CARD)
            setStroke(dp(1), Palette.CARD_STROKE)
        }
    }

    private fun addCard(root: LinearLayout, card: View) {
        root.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) },
        )
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Palette.TEXT_SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(4), dp(18), 0, dp(8))
    }

    private fun makeButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) Palette.BG else Palette.PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (primary) Palette.PRIMARY else Color.TRANSPARENT)
                setStroke(dp(1), if (primary) Palette.PRIMARY else Palette.CARD_STROKE)
            }
            setOnClickListener { onClick() }
        }

    private fun buttonRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            buttons.forEachIndexed { index, button ->
                addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index < buttons.lastIndex) marginEnd = dp(10)
                    },
                )
            }
        }

    private fun chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (selected) Palette.PRIMARY else Palette.CHIP)
        setStroke(dp(1), if (selected) Palette.PRIMARY else Palette.CARD_STROKE)
    }

    private fun stateLabel(state: Int): String = when (state) {
        Display.STATE_ON -> "on"
        Display.STATE_OFF -> "off"
        Display.STATE_DOZE -> "doze"
        Display.STATE_DOZE_SUSPEND -> "doze-suspend"
        Display.STATE_ON_SUSPEND -> "on-suspend"
        Display.STATE_VR -> "vr"
        Display.STATE_UNKNOWN -> "unknown"
        else -> state.toString()
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255f).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}

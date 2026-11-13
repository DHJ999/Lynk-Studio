package com.duan.lynkcluster

import android.content.Context

enum class DashboardMode(val id: String, val label: String) {
    FULLSCREEN("fullscreen", "全屏显示仪表盘"),
    NO_BLOCK("noblock", "不遮挡仪表盘"),
    ;

    companion object {
        fun fromId(id: String?): DashboardMode = entries.firstOrNull { it.id == id } ?: FULLSCREEN
    }
}

/**
 * 把仪表画面送到副屏的三条路。
 *
 * 一种常见做法是「副屏 Activity + 显示层覆盖」；普通应用拿不到那些
 * ADD_TRUSTED_DISPLAY / MANAGE_ACTIVITY_TASKS 等系统权限，所以这里把
 * 能试的路都留出来，让车机上可以逐条排查。
 */
enum class OutputMode(val id: String, val label: String) {
    AUTO("auto", "自动"),
    OVERLAY("overlay", "悬浮层"),
    PRESENTATION("presentation", "投屏窗口"),
    ACTIVITY("activity", "副屏 Activity"),
    ;
    companion object {
        fun fromId(id: String?): OutputMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * 车辆数据来源。
 *
 * AUTO 优先真实 CarService/VHAL，失败才回退模拟；CAR 只读真实信号，
 * 失败时显示未就绪（不会偷偷用假数据）；SIM 用于桌面预览和界面调试。
 */
enum class DataMode(val id: String, val label: String) {
    REAL("real", "真实数据"),
    SIM("sim", "模拟数据"),
    ;

    companion object {
        fun fromId(id: String?): DataMode = when (id) {
            "sim" -> SIM
            else -> REAL   // 旧配置 auto/vendor/car 全部归为真实数据
        }
    }
}

/**
 * 仪表盘模块的持久化配置。
 *
 * 这些开关覆盖仪表盘配置的核心项：
 * 启用、主题、全屏/不遮挡、被原车切走自动切回、开机自启、顶层显示、原车挡位。
 * 不包含导航/歌词/HUD 等其它模块。
 */
data class DashboardSettings(
    val enabled: Boolean = false,
    val themeId: String = ClusterThemes.DEEP_SPACE.id,
    val mode: DashboardMode = DashboardMode.FULLSCREEN,
    /** 被原车切走或窗口被系统移除后，定时检查并重新挂上去。 */
    val autoReclaim: Boolean = true,
    /** 开机后自动启动仪表盘服务。 */
    val autoStart: Boolean = false,
    /** 强制悬浮层输出，保证主界面退出后仍然显示，并尽量压在普通 Activity 之上。 */
    val topmost: Boolean = false,
    /** 不遮挡模式下保留原车挡位，隐藏本应用画的挡位。 */
    val originalGear: Boolean = false,
    /** 副屏输出路径。AUTO 依次尝试悬浮层、投屏窗口、副屏 Activity。 */
    val outputMode: OutputMode = OutputMode.AUTO,
    /** 手动指定仪表屏；-1 表示自动挑选。 */
    val targetDisplayId: Int = AUTO_DISPLAY_ID,
    /** 是否已经确认过“全屏覆盖会挡住原厂警告灯”的风险提示。 */
    val safetyAcknowledged: Boolean = false,
    /** 车辆数据来源：CarService/VHAL、模拟、或自动。 */
    val dataMode: DataMode = DataMode.REAL,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_THEME, ClusterThemes.byId(themeId).id)
            .putString(KEY_MODE, mode.id)
            .putBoolean(KEY_AUTO_RECLAIM, autoReclaim)
            .putBoolean(KEY_AUTO_START, autoStart)
            .putBoolean(KEY_TOPMOST, topmost)
            .putBoolean(KEY_ORIGINAL_GEAR, originalGear)
            .putString(KEY_OUTPUT_MODE, outputMode.id)
            .putInt(KEY_TARGET_DISPLAY, targetDisplayId)
            .putBoolean(KEY_SAFETY_ACK, safetyAcknowledged)
            .putString(KEY_DATA_MODE, dataMode.id)
            .apply()
    }

    companion object {
        private const val PREFS = "dashboard_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_THEME = "theme"
        private const val KEY_MODE = "mode"
        private const val KEY_AUTO_RECLAIM = "auto_reclaim"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_TOPMOST = "topmost"
        private const val KEY_ORIGINAL_GEAR = "original_gear"
        private const val KEY_OUTPUT_MODE = "output_mode"
        private const val KEY_TARGET_DISPLAY = "target_display"
        private const val KEY_SAFETY_ACK = "safety_ack"
        private const val KEY_DATA_MODE = "data_mode"

        const val AUTO_DISPLAY_ID = -1

        fun load(context: Context): DashboardSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DashboardSettings(
                enabled = p.getBoolean(KEY_ENABLED, false),
                themeId = ClusterThemes.byId(p.getString(KEY_THEME, null)).id,
                mode = DashboardMode.fromId(p.getString(KEY_MODE, null)),
                autoReclaim = p.getBoolean(KEY_AUTO_RECLAIM, true),
                autoStart = p.getBoolean(KEY_AUTO_START, false),
                topmost = p.getBoolean(KEY_TOPMOST, false),
                originalGear = p.getBoolean(KEY_ORIGINAL_GEAR, false),
                outputMode = OutputMode.fromId(p.getString(KEY_OUTPUT_MODE, null)),
                targetDisplayId = p.getInt(KEY_TARGET_DISPLAY, AUTO_DISPLAY_ID),
                safetyAcknowledged = p.getBoolean(KEY_SAFETY_ACK, false),
                dataMode = DataMode.fromId(p.getString(KEY_DATA_MODE, null)),
            )
        }
    }
}

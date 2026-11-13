package com.duan.lynkcluster

/**
 * 仪表盘皮肤。
 *
 * 一种常见做法是用整屏位图做底图，换肤等于换图；这里没有设计稿，
 * 换成一套可运行时切换的矢量配色：底图、刻度、数字、告警色一起变。
 * 后续如果接入位图底图，只需要把 [bg] 换成 Bitmap 绘制，数据层不用动。
 */
data class ClusterTheme(
    val id: String,
    val label: String,
    val bg: Int,
    val bgGlow: Int,
    val track: Int,
    val primary: Int,
    val primaryDeep: Int,
    val textPrimary: Int = 0xFFFFFFFF.toInt(),
    val textSecondary: Int = 0xFF9AA9B6.toInt(),
    val textMuted: Int = 0xFF70828F.toInt(),
    val warn: Int = 0xFFFF4D4F.toInt(),
    val amber: Int = 0xFFFFB020.toInt(),
)

object ClusterThemes {
    val DEEP_SPACE = ClusterTheme(
        id = "deep_space",
        label = "深空",
        bg = 0xFF04060A.toInt(),
        bgGlow = 0xFF0B1319.toInt(),
        track = 0xFF1E2A33.toInt(),
        primary = 0xFF00E8C6.toInt(),
        primaryDeep = 0xFF006B5C.toInt(),
    )

    val MINIMAL = ClusterTheme(
        id = "minimal",
        label = "极简",
        bg = 0xFF050505.toInt(),
        bgGlow = 0xFF111111.toInt(),
        track = 0xFF262626.toInt(),
        primary = 0xFFF2F2F2.toInt(),
        primaryDeep = 0xFF777777.toInt(),
        textSecondary = 0xFFB8B8B8.toInt(),
        textMuted = 0xFF7C7C7C.toInt(),
    )

    val NEON = ClusterTheme(
        id = "neon",
        label = "霓虹",
        bg = 0xFF0A020C.toInt(),
        bgGlow = 0xFF1A051E.toInt(),
        track = 0xFF2A0F2B.toInt(),
        primary = 0xFFFF3DDC.toInt(),
        primaryDeep = 0xFF7A005F.toInt(),
        textSecondary = 0xFFD8B6E0.toInt(),
        textMuted = 0xFF8E6F97.toInt(),
        amber = 0xFFFFC857.toInt(),
    )

    val ICE = ClusterTheme(
        id = "ice",
        label = "冰蓝",
        bg = 0xFF02070C.toInt(),
        bgGlow = 0xFF071722.toInt(),
        track = 0xFF13293A.toInt(),
        primary = 0xFF4FC3FF.toInt(),
        primaryDeep = 0xFF0A4C7A.toInt(),
        textSecondary = 0xFFA9C7D9.toInt(),
        textMuted = 0xFF68879A.toInt(),
    )

    val AMBER = ClusterTheme(
        id = "amber",
        label = "琥珀",
        bg = 0xFF0A0602.toInt(),
        bgGlow = 0xFF1A1006.toInt(),
        track = 0xFF2A1C0A.toInt(),
        primary = 0xFFFFB020.toInt(),
        primaryDeep = 0xFF7A4A00.toInt(),
        textSecondary = 0xFFD8C3A5.toInt(),
        textMuted = 0xFF86755C.toInt(),
        warn = 0xFFFF5A3C.toInt(),
        amber = 0xFFFFD166.toInt(),
    )

    /**
     * 极简科技：形态级主题，不是"换色"。
     *
     * 前五套共用 [ClusterView] 的卡片布局（左挡位/中车速环/右转速柱），
     * 这一套由 [MinimalClusterRenderer] 独立绘制：全幅 1920×720、纯线框、
     * 无填充块，左侧转速环 + 中央大数字 + 右侧三组数据。
     * 设计稿见 `仪表盘样式/新风格_科技极简MINIMAL_1920x720.png`。
     */
    val TECH_MINIMAL = ClusterTheme(
        id = "tech_minimal",
        label = "极简科技",
        bg = 0xFF0B0D10.toInt(),
        bgGlow = 0xFF11151A.toInt(),
        track = 0xFF242A32.toInt(),
        primary = 0xFF7FD8FF.toInt(),
        primaryDeep = 0xFF2A4552.toInt(),
        textPrimary = 0xFFF2F5F8.toInt(),
        textSecondary = 0xFF6B7885.toInt(),
        textMuted = 0xFF3A444F.toInt(),
        warn = 0xFFFF6B4A.toInt(),
        amber = 0xFFFFC857.toInt(),
    )

    val ALL = listOf(DEEP_SPACE, MINIMAL, NEON, ICE, AMBER, TECH_MINIMAL)

    fun byId(id: String?): ClusterTheme = ALL.firstOrNull { it.id == id } ?: DEEP_SPACE

    /** 是否走独立渲染器（形态级主题）。目前只有极简科技一套。 */
    fun usesDedicatedRenderer(theme: ClusterTheme): Boolean = theme.id == TECH_MINIMAL.id
}

package com.duan.lynkcluster

/**
 * 深空仪表配色。
 *
 * 原则：近黑底（夜间不刺眼）、单一冷色主色、纯白数字、低饱和辅助色。
 * 全部硬编码，不跟随系统深色模式，避免车机厂商主题色污染。
 */
object Palette {
    /** 主背景，比纯黑略偏蓝。 */
    const val BG = 0xFF04060A.toInt()

    /** 中心提亮色，用于极淡的径向渐变。 */
    const val BG_GLOW = 0xFF0B1319.toInt()

    /** 进度弧与条形的空槽。 */
    const val TRACK = 0xFF1E2A33.toInt()

    /** 主色：青绿。 */
    const val PRIMARY = 0xFF00E8C6.toInt()

    /** 主色暗部，弧线渐变起点。 */
    const val PRIMARY_DEEP = 0xFF006B5C.toInt()

    const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0xFF9AA9B6.toInt()
    const val TEXT_MUTED = 0xFF70828F.toInt()

    /** 超转、水温过高、故障。 */
    const val WARN = 0xFFFF4D4F.toInt()

    /** 中间态提醒。 */
    const val AMBER = 0xFFFFB020.toInt()

    /** 设置页卡片。 */
    const val CARD = 0xFF0A1117.toInt()
    const val CARD_STROKE = 0xFF1B2A34.toInt()
    const val CHIP = 0xFF101A21.toInt()
}

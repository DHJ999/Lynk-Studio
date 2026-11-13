package com.duan.lynkcluster

/**
 * 四轮胎压，单位 bar。负值表示该轮没有读到数据。
 *
 * 车机 VHAL 的 TIRE_PRESSURE 按 AOSP 规范是 kPa，数据源层会统一换算成 bar；
 * 绘制层只认 bar，不需要关心底层单位。
 */
data class TirePressures(
    val frontLeft: Float = UNAVAILABLE,
    val frontRight: Float = UNAVAILABLE,
    val rearLeft: Float = UNAVAILABLE,
    val rearRight: Float = UNAVAILABLE,
) {
    fun isAvailable(value: Float): Boolean = value in MIN_BAR..MAX_BAR

    val anyAvailable: Boolean
        get() = isAvailable(frontLeft) || isAvailable(frontRight) ||
            isAvailable(rearLeft) || isAvailable(rearRight)

    companion object {
        const val UNAVAILABLE = -1f
        const val MIN_BAR = 0.5f
        const val MAX_BAR = 6.0f

        val NONE = TirePressures()
    }
}

/**
 * 一帧车辆状态快照。
 *
 * 字段只保留仪表上真正要显示的量。真实数据源（CarService/VHAL）和模拟数据源
 * 都实现同一份数据结构，绘制层不需要知道数据从哪里来。
 *
 * 不可用约定：Float 用 `NaN`，`rpm` 用负数，挡位用 `--`。绘制层会显示 `--`，
 * 不用 0 冒充“真实读到的 0”，尤其是车速和转速。
 */
data class Telemetry(
    /** km/h；NaN = 未读到 */
    val speed: Float,
    /** r/min；负数 = 未读到 */
    val rpm: Int,
    /** 挡位标签：P / R / N / D1..D9；`--` = 未读到 */
    val gear: String,
    /** 冷却液温度 °C；NaN = 未读到 */
    val coolant: Float,
    /** 燃油剩余百分比 0..100；NaN = 未读到 */
    val fuel: Float,
    /** 剩余续航 km；NaN = 未读到 */
    val range: Float,
    /** 电瓶电压 V；0 = 未读到（VHAL 没有标准属性） */
    val voltage: Float,
    /** 总里程 km；NaN = 未读到 */
    val odo: Float,
    /** 小计里程 km；NaN = 未读到 */
    val trip: Float,
    /** 油耗 L/100km；NaN = 未读到 */
    val consumption: Float,
    /** 车外温度 °C；NaN = 未读到 */
    val ambient: Float,
    /** 四轮胎压 bar */
    val tires: TirePressures = TirePressures.NONE,
    /** 数据来源标记：CAR / SIM / IDLE。仪表上会显示 SIM 角标，避免把模拟值当真车数据。 */
    val source: String = SOURCE_SIM,
) {
    companion object {
        const val SOURCE_CAR = "CAR"
        const val SOURCE_SIM = "SIM"
        const val SOURCE_IDLE = "IDLE"

        val IDLE = Telemetry(
            speed = Float.NaN,
            rpm = -1,
            gear = "--",
            coolant = Float.NaN,
            fuel = Float.NaN,
            range = Float.NaN,
            voltage = 0f,
            odo = Float.NaN,
            trip = Float.NaN,
            consumption = Float.NaN,
            ambient = Float.NaN,
            tires = TirePressures.NONE,
            source = SOURCE_IDLE,
        )
    }
}

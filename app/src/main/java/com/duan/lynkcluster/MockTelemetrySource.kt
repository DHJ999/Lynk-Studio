package com.duan.lynkcluster

import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * 模拟行车数据源：没有 OBD 硬件时用来预览界面。
 *
 * 内部跑一个 90 秒循环的驾驶剧本（起步 → 市区巡航 → 上快速路 → 减速停车），
 * 车速用一阶滞后跟随目标值，转速由「挡内比例 + 加速负载」推导，
 * 所以能看到换挡时转速自然回落，而不是随机跳数。
 *
 * 接入 ELM327 时替换成 ObdTelemetrySource，回调签名不变，绘制层零改动。
 */
class MockTelemetrySource(
    private val intervalMs: Long = 100L,
) : TelemetrySource {
    private val handler = Handler(Looper.getMainLooper())

    private var onUpdate: ((Telemetry) -> Unit)? = null

    private var t = 0.0
    private var speed = 0.0
    private var coolant = 58.0
    private var fuel = 68.4
    private var odo = 28417.3
    private var trip = 128.4

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun start(onUpdate: (Telemetry) -> Unit, onState: (SourceState) -> Unit) {
        this.onUpdate = onUpdate
        onState(SourceState("SIM", ready = true))
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        onUpdate = null
    }

    private fun step() {
        val dt = intervalMs / 1000.0
        t += dt

        val target = targetSpeed(t % CYCLE)

        // 一阶滞后：加速时给 0.055，收油给 0.045，推背感和滑行感都靠这个差。
        val pull = if (target > speed) 0.055 else 0.045
        speed += (target - speed) * pull
        if (abs(speed) < 0.05) speed = 0.0

        val load = ((target - speed) / 22.0).coerceIn(0.0, 1.0)
        val consumption = 6.8 + sin(t * 0.05) * 0.4

        coolant += (91 - coolant) * 0.006
        fuel = max(0.0, fuel - dt * 0.0009)
        odo += speed * dt / 3600
        trip += speed * dt / 3600

        // 油箱按 50 L 计，用当前平均油耗折算剩余里程。
        val rangeKm = fuel / 100 * 50 / consumption * 100

        onUpdate?.invoke(
            Telemetry(
                speed = speed.toFloat(),
                rpm = rpmFor(speed, load),
                gear = gearFor(speed),
                coolant = (coolant + sin(t * 1.7) * 0.35).toFloat(),
                fuel = fuel.toFloat(),
                range = rangeKm.toFloat(),
                voltage = (13.9 + sin(t * 0.9) * 0.2).toFloat(),
                odo = odo.toFloat(),
                trip = trip.toFloat(),
                consumption = consumption.toFloat(),
                ambient = 14f,
                tires = TirePressures(
                    frontLeft = (2.42 + sin(t * 0.11) * 0.03).toFloat(),
                    frontRight = (2.40 + sin(t * 0.13) * 0.03).toFloat(),
                    rearLeft = (2.34 + sin(t * 0.09) * 0.03).toFloat(),
                    rearRight = (2.36 + sin(t * 0.12) * 0.03).toFloat(),
                ),
                source = Telemetry.SOURCE_SIM,
            )
        )
    }

    private fun targetSpeed(p: Double): Double = when {
        p < 3 -> 0.0
        p < 14 -> ramp(3.0, 14.0, p, 0.0, 52.0)
        p < 26 -> 52.0 + sin(p * 0.7) * 3
        p < 38 -> ramp(26.0, 38.0, p, 52.0, 90.0)
        p < 52 -> 90.0 + sin(p * 0.5) * 2
        p < 62 -> ramp(52.0, 62.0, p, 90.0, 30.0)
        p < 76 -> 30.0 + sin(p * 0.9) * 4
        p < 86 -> ramp(76.0, 86.0, p, 30.0, 0.0)
        else -> 0.0
    }

    /** smoothstep 插值，起步和收油都不会有折现感。 */
    private fun ramp(t0: Double, t1: Double, t: Double, v0: Double, v1: Double): Double {
        val k = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
        return v0 + (v1 - v0) * (k * k * (3 - 2 * k))
    }

    private fun rpmFor(spd: Double, load: Double): Int {
        if (spd < 1.2) return (760 + load * 260).toInt()
        val i = gearIndex(spd)
        val lo = GEAR_TOP[i - 1]
        val hi = GEAR_TOP[i]
        val k = ((spd - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return (1320 + k * 4680 + load * 640).toInt()
    }

    private fun gearIndex(spd: Double): Int {
        for (i in 1 until GEAR_TOP.size) {
            if (spd <= GEAR_TOP[i]) return i
        }
        return GEAR_TOP.size - 1
    }

    private fun gearFor(spd: Double): String =
        if (spd < 1.2) "N" else "D${gearIndex(spd)}"

    private companion object {
        const val CYCLE = 90.0

        /** 每个挡位的上限车速，用于反推当前挡位与挡内转速比例。 */
        val GEAR_TOP = doubleArrayOf(0.0, 26.0, 46.0, 68.0, 92.0, 122.0, 160.0)
    }
}

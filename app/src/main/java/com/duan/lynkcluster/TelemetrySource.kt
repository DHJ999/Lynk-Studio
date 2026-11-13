package com.duan.lynkcluster

import android.content.Context
import android.util.Log

/**
 * 数据源状态。UI 直接展示这三个字段，避免把「模拟数据」误当成「真实数据」。
 *
 *  - [ready]      已经拿到可用信号
 *  - [connecting] 正在连车机服务，AUTO 模式此时不能急着回退模拟
 *  - [detail]     已订阅/缺失/被拒绝的信号说明，或失败原因
 */
data class SourceState(
    val label: String,
    val ready: Boolean,
    val connecting: Boolean = false,
    val detail: String? = null,
)

interface TelemetrySource {
    fun start(onUpdate: (Telemetry) -> Unit, onState: (SourceState) -> Unit)
    fun stop()
}

object TelemetrySources {
    private const val TAG = "LynkCluster"

    /**
     * 创建厂商 CarProxyService 数据源。
     *
     * 厂商接口只在领克/ECARX 车机上存在；普通手机没有对应 Binder 或
     * android.car 类时，构造阶段必须安静失败，由 AUTO 模式继续回退。
     */
    fun createVendor(context: Context): TelemetrySource? = try {
        CarProxyTelemetrySource(context)
    } catch (t: Throwable) {
        Log.w(TAG, "CarProxyService 不可用: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /**
     * 创建 CarService/VHAL 数据源。
     *
     * 这里刻意用 try/catch 包住构造：普通手机上根本没有 android.car 类，
     * 直接加载 [CarTelemetrySource] 会抛 NoClassDefFoundError。捕获后返回 null，
     * 由引擎决定是否回退模拟数据。
     */
    fun createCar(context: Context): TelemetrySource? = try {
        CarTelemetrySource(context)
    } catch (t: Throwable) {
        Log.w(TAG, "android.car 不可用: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** 车辆信号诊断：标准 VHAL + 厂商 CarProxy 两套链路。 */
    fun probe(context: Context): String = buildString {
        appendLine("=== 标准 CarService / VHAL ===")
        appendLine(
            try {
                CarSignalProbe.probe(context)
            } catch (t: Throwable) {
                "诊断失败：${t.javaClass.simpleName}: ${t.message}"
            },
        )
        appendLine()
        appendLine(
            try {
                CarProxyProbe.probe()
            } catch (t: Throwable) {
                "CarProxy 诊断失败：${t.javaClass.simpleName}: ${t.message}"
            },
        )
    }
}

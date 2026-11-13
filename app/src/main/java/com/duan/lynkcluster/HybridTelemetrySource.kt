package com.duan.lynkcluster

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 厂商 CarProxy + 标准 VHAL 的逐字段混合数据源。
 *
 * 背景：CarProxy 只要接受两三个信号就会 `ready`，会把后面更完整的 VHAL 挡在
 * AUTO 回退链之外；反过来普通应用走 VHAL 时又经常只有车速。两路各缺一半，
 * 所以这里两路同时启动、按字段取值：
 *
 *  - 某字段只有一路有有效值 → 用那一路；
 *  - 两路都有有效值 → 厂商 CarProxy 优先（覆盖面更全，单位也已在各自源里归一）；
 *  - 两路都没有 → 保持不可用标记（NaN / -1 / `--`），不拿 0 冒充真实值。
 *
 * [SourceState.detail] 会写清每个字段最终来自哪一路，装车时可以据此判断
 * 是 CarProxy 没给，还是 VHAL 没权限。
 *
 * 调度模型（2026-11 重构）：
 * ```
 * vendor 回调 ─┐
 * car 回调    ─┼─→ MutableSharedFlow<Event> ─→ scan(Merged) ─→ StateFlow
 * 两路状态    ─┘                                     │
 *                                        ┌───────────┴───────────┐
 *                                   frames(Telemetry)       states(SourceState)
 * ```
 * - 单一 `SupervisorJob` 作用域：一路 Binder 挂掉不会连坐另一路，也不会取消整个 scope。
 * - 合并状态集中在一份 `Merged` 里，`scan` 累积，不再有第二套可变字段 + publish 标志。
 * - `onStart { emit(Merged()) }` 保证子源静止（熄火 / 刚开机）时也有首帧，
 *   500 ms 轮询从「首帧来源」降级为「子源内部的兜底快照」。
 * - `WhileSubscribed(5 s)`：仪表停止收集 5 秒后上游自动停采，回到前台重新拉起。
 */
class HybridTelemetrySource(
    private val vendor: TelemetrySource?,
    private val car: TelemetrySource?,
    private val vendorGap: String? = null,
    private val carGap: String? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    bridgeDispatcher: CoroutineDispatcher? = null,
) : TelemetrySource {

    /** SupervisorJob：子源的任何一路抛异常都只影响它自己那条协程。 */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * 下游回调一律回主线程。
     *
     * `DashboardEngine` 的监听者会直接改 UI（状态栏文案、控制台列表），
     * 原来子源都在主线程回调所以没问题；合并挪到后台之后必须显式搬回来，
     * 否则会变成「后台线程碰 View」。
     *
     * 纯 JVM 单测里没有 Looper，[mainThreadDispatcher] 会回退到 Unconfined，
     * 这样测试仍能同步断言，不必为此把整个测试类拽进 Robolectric。
     */
    private val bridgeScope = CoroutineScope(
        SupervisorJob() + (bridgeDispatcher ?: mainThreadDispatcher()),
    )

    /**
     * 子源回调统一变成事件流。
     *
     * 子源都在主线程回调，事件率已被各自的 50 ms 节流压到 20 Hz 以内；
     * 缓冲 32 条足够吸收一次突发，满了丢最旧的 —— 仪表只关心最新值。
     */
    private val events = MutableSharedFlow<Event>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var bridgeJob: Job? = null
    private var started = false

    /** 两路最近帧与状态的累积值，`scan` 的载体。 */
    private val merged: StateFlow<Merged> = events
        .scan(Merged()) { acc, event -> acc.reduce(event) }
        // 订阅开始先落一帧空的：子源静止时也要有首帧，不能等它主动推变化。
        .onStart { emit(Merged()) }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
            Merged(),
        )

    /**
     * 合并后的仪表帧。
     *
     * 这是本类对外的唯一帧来源：`DashboardEngine` 只收集它，
     * 不再需要 `onUpdate` 回调里的隐式时序。
     */
    val frames: StateFlow<Telemetry> = merged
        .map { mergeFrames(it.vendorFrame, it.carFrame).telemetry }
        .distinctUntilChanged()
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
            Telemetry.IDLE,
        )

    /** 控制台展示用的数据源状态。 */
    val states: StateFlow<SourceState> = merged
        .map { describe(it) }
        .distinctUntilChanged()
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
            describe(Merged()),
        )

    override fun start(onUpdate: (Telemetry) -> Unit, onState: (SourceState) -> Unit) {
        if (started) return
        started = true

        if (vendor == null && car == null) {
            onState(describe(Merged()))
            return
        }

        // UNDISPATCHED：先把两个 collect 推到挂起点，StateFlow 的订阅计数立刻 +1，
        // 子源随后同步发出的「正在连接」状态才不会落在「上游还没起来」的空窗里。
        bridgeJob = bridgeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                frames.collect { onUpdate(it) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                states.collect { onState(it) }
            }
        }

        vendor?.start(
            { frame -> events.tryEmit(Event.VendorFrame(frame)) },
            { state -> events.tryEmit(Event.VendorState(state)) },
        )
        car?.start(
            { frame -> events.tryEmit(Event.CarFrame(frame)) },
            { state -> events.tryEmit(Event.CarState(state)) },
        )
    }

    override fun stop() {
        started = false
        bridgeJob?.cancel()
        bridgeJob = null
        // 子源必须显式停：它们持有 Binder 死亡通知与 Handler 回调。
        runCatching { vendor?.stop() }
        runCatching { car?.stop() }
    }

    // ---------------- 逐字段合并 ----------------

    private fun mergeFrames(vendor: Telemetry?, car: Telemetry?): MergedTelemetry {
        val origins = LinkedHashMap<String, String>()

        val speed = pick("车速", validSpeed(vendor?.speed), validSpeed(car?.speed), origins)
        val rpm = pick("转速", validRpm(vendor?.rpm), validRpm(car?.rpm), origins)
        val gear = pick("挡位", validGear(vendor?.gear), validGear(car?.gear), origins)
        val coolant = pick(
            "水温",
            validTemperature(vendor?.coolant),
            validTemperature(car?.coolant),
            origins,
        )
        val fuel = pick("油量", validPercent(vendor?.fuel), validPercent(car?.fuel), origins)
        val range = pick("续航", validNonNegative(vendor?.range), validNonNegative(car?.range), origins)
        val voltage = pick("电压", validVoltage(vendor?.voltage), validVoltage(car?.voltage), origins)
        val odo = pick("总里程", validNonNegative(vendor?.odo), validNonNegative(car?.odo), origins)
        val trip = pick("小计里程", validNonNegative(vendor?.trip), validNonNegative(car?.trip), origins)
        val consumption = pick(
            "油耗",
            validNonNegative(vendor?.consumption),
            validNonNegative(car?.consumption),
            origins,
        )
        val ambient = pick(
            "车外温度",
            validTemperature(vendor?.ambient),
            validTemperature(car?.ambient),
            origins,
        )

        val tires = mergeTires(vendor?.tires, car?.tires, origins)

        val telemetry = Telemetry(
            speed = speed ?: Float.NaN,
            rpm = rpm ?: -1,
            gear = gear ?: UNAVAILABLE_GEAR,
            coolant = coolant ?: Float.NaN,
            fuel = fuel ?: Float.NaN,
            range = range ?: Float.NaN,
            voltage = voltage ?: 0f,
            odo = odo ?: Float.NaN,
            trip = trip ?: Float.NaN,
            consumption = consumption ?: Float.NaN,
            ambient = ambient ?: Float.NaN,
            tires = tires,
            // 合并后至少有一个真实字段才算 CAR；IDLE 只在两路都空时出现。
            source = if (origins.isEmpty()) Telemetry.SOURCE_IDLE else Telemetry.SOURCE_CAR,
        )
        return MergedTelemetry(telemetry, origins)
    }

    /** CarProxy 优先，其次 VHAL；都没有就返回 null。 */
    private fun <T : Any> pick(
        field: String,
        vendorValue: T?,
        carValue: T?,
        origins: MutableMap<String, String>,
    ): T? = when {
        vendorValue != null -> {
            origins[field] = ORIGIN_VENDOR
            vendorValue
        }

        carValue != null -> {
            origins[field] = ORIGIN_CAR
            carValue
        }

        else -> null
    }

    /** 四轮独立合并：某个轮子只有一路有值也保留，不整组丢弃。 */
    private fun mergeTires(
        vendor: TirePressures?,
        car: TirePressures?,
        origins: MutableMap<String, String>,
    ): TirePressures {
        val vendorTires = vendor ?: TirePressures.NONE
        val carTires = car ?: TirePressures.NONE
        val used = LinkedHashSet<String>()

        fun wheel(vendorValue: Float, carValue: Float): Float = when {
            TirePressures.NONE.isAvailable(vendorValue) -> {
                used += ORIGIN_VENDOR
                vendorValue
            }

            TirePressures.NONE.isAvailable(carValue) -> {
                used += ORIGIN_CAR
                carValue
            }

            else -> TirePressures.UNAVAILABLE
        }

        val merged = TirePressures(
            frontLeft = wheel(vendorTires.frontLeft, carTires.frontLeft),
            frontRight = wheel(vendorTires.frontRight, carTires.frontRight),
            rearLeft = wheel(vendorTires.rearLeft, carTires.rearLeft),
            rearRight = wheel(vendorTires.rearRight, carTires.rearRight),
        )
        if (used.isNotEmpty()) origins["胎压"] = used.joinToString("+")
        return merged
    }

    private fun validSpeed(value: Float?): Float? =
        value?.takeIf { it.isFinite() && it >= 0f }

    private fun validRpm(value: Int?): Int? = value?.takeIf { it >= 0 }

    private fun validGear(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it != UNAVAILABLE_GEAR }

    private fun validTemperature(value: Float?): Float? =
        value?.takeIf { it.isFinite() && it > TEMPERATURE_FLOOR }

    private fun validPercent(value: Float?): Float? =
        value?.takeIf { it.isFinite() && it in 0f..100f }

    private fun validNonNegative(value: Float?): Float? =
        value?.takeIf { it.isFinite() && it >= 0f }

    /** 0 是「VHAL 没有该属性」的约定值，不是真实电压。 */
    private fun validVoltage(value: Float?): Float? =
        value?.takeIf { it.isFinite() && it > 0f }

    // ---------------- 状态推导 ----------------

    private fun describe(m: Merged): SourceState {
        val combined = mergeFrames(m.vendorFrame, m.carFrame)
        if (!combined.hasValue) {
            return if (isTerminalFailure(m)) {
                SourceState(LABEL, ready = false, connecting = false, detail = failureDetail(m))
            } else {
                SourceState(
                    LABEL,
                    ready = false,
                    connecting = true,
                    detail = pendingDetail(combined, m),
                )
            }
        }
        return SourceState(LABEL, ready = true, detail = readyDetail(combined, m))
    }

    private fun readyDetail(combined: MergedTelemetry, m: Merged): String = buildString {
        append("字段来源：")
        append(combined.origins.entries.joinToString("、") { "${it.key}=${it.value}" })
        append("；")
        append(childSummary(m))
    }

    private fun pendingDetail(combined: MergedTelemetry, m: Merged): String = buildString {
        append("两路都还没读到有效车辆信号")
        if (combined.origins.isNotEmpty()) {
            append("；已知字段来源：")
            append(combined.origins.entries.joinToString("、") { "${it.key}=${it.value}" })
        }
        append("；")
        append(childSummary(m))
    }

    private fun childSummary(m: Merged): String = listOf(
        childText(CARPROXY_NAME, vendor, m.vendorState, vendorGap),
        childText(VHAL_NAME, car, m.carState, carGap),
    ).joinToString("；")

    private fun childText(
        name: String,
        source: TelemetrySource?,
        state: SourceState?,
        gap: String?,
    ): String = when {
        source == null -> "$name 不可用（${gap ?: "此设备没有该接口"}）"
        state == null -> "$name 连接中"
        state.ready -> "$name 已连接"
        state.connecting -> "$name 连接中"
        else -> "$name 不可用（${state.detail ?: "原因未知"}）"
    }

    private fun failureDetail(m: Merged): String = listOf(
        "$CARPROXY_NAME: ${failureText(vendor, m.vendorState, vendorGap)}",
        "$VHAL_NAME: ${failureText(car, m.carState, carGap)}",
    ).joinToString("；")

    private fun failureText(
        source: TelemetrySource?,
        state: SourceState?,
        gap: String?,
    ): String = if (source == null) gap ?: "不可用" else state?.detail ?: "未连接"

    /** 所有实际存在的那一路都已经宣告失败，才能回退模拟。 */
    private fun isTerminalFailure(m: Merged): Boolean {
        if (vendor == null && car == null) return true
        if (vendor != null && !m.vendorState.isTerminal()) return false
        if (car != null && !m.carState.isTerminal()) return false
        return true
    }

    // ---------------- 内部类型 ----------------

    /** 子源回调 → 流的统一事件。 */
    private sealed interface Event {
        data class VendorFrame(val telemetry: Telemetry) : Event
        data class CarFrame(val telemetry: Telemetry) : Event
        data class VendorState(val state: SourceState) : Event
        data class CarState(val state: SourceState) : Event
    }

    /**
     * 两路的最近帧与状态。
     *
     * 不可变，`copy()` 产生新实例，正好配合 `scan` 的累积语义；
     * 也不再需要 `started` 检查之后散落在各处的可变字段。
     */
    private data class Merged(
        val vendorFrame: Telemetry? = null,
        val carFrame: Telemetry? = null,
        val vendorState: SourceState? = null,
        val carState: SourceState? = null,
    ) {
        fun reduce(event: Event): Merged = when (event) {
            is Event.VendorFrame -> copy(vendorFrame = event.telemetry)
            is Event.CarFrame -> copy(carFrame = event.telemetry)
            // 子源判定断开后，它断开前的最后一帧不能再参与合并，
            // 否则仪表会冻在旧车速上。
            is Event.VendorState ->
                if (event.state.isTerminal()) {
                    copy(vendorState = event.state, vendorFrame = null)
                } else {
                    copy(vendorState = event.state)
                }

            is Event.CarState ->
                if (event.state.isTerminal()) {
                    copy(carState = event.state, carFrame = null)
                } else {
                    copy(carState = event.state)
                }
        }
    }

    /** 合并结果：一帧数据 + 每个字段的来源。 */
    private data class MergedTelemetry(
        val telemetry: Telemetry,
        val origins: Map<String, String>,
    ) {
        val hasValue: Boolean get() = origins.isNotEmpty()
    }

    companion object {
        const val LABEL = "HYBRID"

        private const val CARPROXY_NAME = "CarProxy"
        private const val VHAL_NAME = "VHAL"
        private const val ORIGIN_VENDOR = "CarProxy"
        private const val ORIGIN_CAR = "VHAL"

        private const val UNAVAILABLE_GEAR = "--"

        /** 温度类字段的合理下限：低于 -50℃ 视为没读到。 */
        private const val TEMPERATURE_FLOOR = -50f

        /** 仪表停止收集后多久停上游采集。 */
        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L

        private const val EVENT_BUFFER = 32

        private const val VENDOR_UNAVAILABLE = "此设备没有 CarProxyService / 厂商车辆接口"
        private const val CAR_UNAVAILABLE = "此设备没有 android.car / CarService"

        /**
         * 按当前设备的接口情况创建混合源。
         *
         * 构造失败与非车机环境都由 [TelemetrySources] 吞掉并返回 null，
         * 对应的一路记为「不可用」，另一路仍可单独工作。
         */
        fun create(context: Context): HybridTelemetrySource {
            val vendor = TelemetrySources.createVendor(context)
            val car = TelemetrySources.createCar(context)
            return HybridTelemetrySource(
                vendor = vendor,
                car = car,
                vendorGap = if (vendor == null) VENDOR_UNAVAILABLE else null,
                carGap = if (car == null) CAR_UNAVAILABLE else null,
            )
        }
    }
}

/**
 * 子源已经彻底失败：既不 ready，也不在连接中。
 *
 * 定义在文件级是因为 [HybridTelemetrySource.Merged] 是静态嵌套类，
 * 访问不到外部类的实例方法。
 */
private fun SourceState?.isTerminal(): Boolean =
    this != null && !ready && !connecting

/**
 * 把主线程 Handler 包成 [CoroutineDispatcher]。
 *
 * 只用到 dispatch 一个能力，为此单独引 `kotlinx-coroutines-android`
 * （约 30 KB dex）不划算，这里二十行等价实现。
 */
private class MainThreadDispatcher(private val handler: Handler) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }
}

/**
 * 取主线程派发器。
 *
 * 纯 JVM 单测里 `Looper.getMainLooper()` 会抛 "not mocked"，
 * 这时退回 [Dispatchers.Unconfined]：测试线程即回调线程，断言仍是同步的。
 * 生产环境永远走得到 Handler 这一支。
 */
private fun mainThreadDispatcher(): CoroutineDispatcher = runCatching {
    MainThreadDispatcher(Handler(Looper.getMainLooper()))
}.getOrElse { Dispatchers.Unconfined }

package com.duan.lynkcluster

import android.car.Car
import android.car.VehicleAreaWheel
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 真实车辆数据源：车机 CarService / VHAL。
 *
 * 这是系统级应用能拿到的「车身网络」标准入口。Android 车机把 CAN/车身信号
 * 抽象成 VehicleProperty，由 CarService 统一鉴权后分发给系统应用；本项目
 * 不直接碰 CAN，也不依赖任何私有 FDBus/QNX 链路。
 *
 * 读取的信号：
 *  - PERF_VEHICLE_SPEED(_DISPLAY) → 车速
 *  - ENGINE_RPM                   → 转速
 *  - ENGINE_COOLANT_TEMP          → 水温
 *  - CURRENT_GEAR / GEAR_SELECTION→ 挡位
 *  - TIRE_PRESSURE（四轮 area）   → 胎压
 *  另附：油量、续航、总里程、车外温度、瞬时油耗（有就读，没有不影响仪表）。
 *
 * 所有属性都通过 CarPropertyManager 的变更回调驱动；属性不存在或无权限时
 * 只记录缺失，不会让整个数据源失败。只有「一个核心信号都订阅不到」才判定失败。
 */
class CarTelemetrySource(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : TelemetrySource {

    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var onUpdate: ((Telemetry) -> Unit)? = null
    private var onState: ((SourceState) -> Unit)? = null

    private var started = false
    private var ready = false
    private var disposed = false

    private val configs = LinkedHashMap<Int, CarPropertyConfig<*>>()
    private val subscribed = LinkedHashSet<Int>()
    private val errors = LinkedHashMap<String, String>()
    private val latest = HashMap<Int, Float>()

    private var currentGear: String? = null
    private var selectedGear: String? = null
    private val tires = floatArrayOf(
        TirePressures.UNAVAILABLE,
        TirePressures.UNAVAILABLE,
        TirePressures.UNAVAILABLE,
        TirePressures.UNAVAILABLE,
    )

    private var lastPublishAt = 0L
    private var publishPending = false
    private var connectTimeout: Runnable? = null

    /** 断线重连计数：连上后清零；退避 1s→2s→4s→8s→16s→30s 封顶。 */
    private var reconnectAttempt = 0

    private val publishRunnable = Runnable { publishNow() }

    private val reconnectRunnable = Runnable {
        if (disposed || ready || !started) return@Runnable
        Log.w(TAG, "开始第 $reconnectAttempt 次重连 CarService")
        emit(
            SourceState(
                LABEL,
                ready = false,
                connecting = true,
                detail = "正在重连 CarService（第 $reconnectAttempt 次）…",
            ),
        )
        teardown()
        openCar()
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            consume(value)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            errors[signalName(propertyId)] = "信号错误（area=$areaId）"
        }
    }

    override fun start(onUpdate: (Telemetry) -> Unit, onState: (SourceState) -> Unit) {
        if (started) return
        started = true
        disposed = false
        reconnectAttempt = 0
        this.onUpdate = onUpdate
        this.onState = onState

        emit(SourceState(LABEL, ready = false, connecting = true, detail = "正在连接 CarService…"))
        openCar()
    }

    /**
     * 连接 CarService 并订阅信号。
     *
     * 抽成独立方法是为了让 [reconnectRunnable] 能复用同一条连接路径，
     * 不再出现「首次连接有超时保护、重连没有」的两套逻辑。
     */
    private fun openCar() {
        if (disposed) return

        val timeout = Runnable {
            if (!ready && !disposed) {
                fail("连接 CarService 超时")
            }
        }
        connectTimeout = timeout
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 不阻塞主线程：立即返回，连接结果走 lifecycle 回调。
                car = Car.createCar(
                    context,
                    handler,
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                ) { connectedCar, isReady ->
                    if (disposed) return@createCar
                    handler.removeCallbacks(timeout)
                    if (isReady && connectedCar != null) {
                        onConnected(connectedCar)
                    } else if (ready) {
                        // 连上后又断开：停掉真实数据，让引擎决定回退或保持未就绪。
                        onDisconnected()
                    } else {
                        fail("CarService 未连接")
                    }
                }
            } else {
                // Android 10 及以下没有异步 createCar 重载，放到工作线程同步连接。
                Thread {
                    val c = runCatching { Car.createCar(context) }.getOrNull()
                    handler.post {
                        if (disposed) return@post
                        handler.removeCallbacks(timeout)
                        if (c != null && c.isConnected) {
                            onConnected(c)
                        } else {
                            fail("CarService 不可用")
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        } catch (t: Throwable) {
            handler.removeCallbacks(timeout)
            fail("CarService 不可用：${describe(t)}")
        }
    }

    override fun stop() {
        disposed = true
        started = false
        ready = false
        reconnectAttempt = 0
        connectTimeout?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(publishRunnable)
        handler.removeCallbacks(reconnectRunnable)
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        manager = null
        car = null
        onUpdate = null
        onState = null
        latest.clear()
        subscribed.clear()
        configs.clear()
        errors.clear()
    }

    /** 重连前彻底清干净：旧 manager / 旧订阅 / 旧数值都不能带进新一轮。 */
    private fun teardown() {
        ready = false
        publishPending = false
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
        handler.removeCallbacks(publishRunnable)
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        manager = null
        car = null
        subscribed.clear()
        configs.clear()
        errors.clear()
        latest.clear()
        currentGear = null
        selectedGear = null
        for (i in tires.indices) tires[i] = TirePressures.UNAVAILABLE
    }

    /**
     * 断线重连：CarService 可能在系统休眠/唤醒、车机重启车辆服务后换掉 binder。
     *
     * 原先这里是「失败就躺平」，只有熄火重开才能恢复；改成指数退避，
     * 参数与 CarProxy 那条链路保持一致（1s→2s→4s→8s→16s→30s 封顶）。
     */
    private fun scheduleReconnect(reason: String) {
        if (disposed) return
        val attempt = ++reconnectAttempt
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "重连 $MAX_RECONNECT_ATTEMPTS 次仍未成功，停止重试（$reason）")
            return
        }
        val delay = minOf(1_000L shl (attempt - 1).coerceAtMost(5), MAX_RECONNECT_DELAY_MS)
        Log.w(TAG, "CarService 第 $attempt 次重连将在 ${delay}ms 后开始（$reason）")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    /** CarService 断开后不再发布任何帧，避免把最后一帧当成实时值。 */
    private fun onDisconnected() {
        if (disposed) return
        ready = false
        handler.removeCallbacks(publishRunnable)
        publishPending = false
        runCatching { manager?.unregisterCallback(callback) }
        manager = null
        emit(
            SourceState(
                LABEL,
                ready = false,
                connecting = false,
                detail = "CarService 连接已断开，准备重连",
            ),
        )
        // 断开后必须显式下发一帧全缺失值，否则绘制层会停在最后一帧。
        onUpdate?.invoke(Telemetry.IDLE)
        scheduleReconnect("连接已断开")
    }

    private fun onConnected(connectedCar: Car) {
        if (disposed || ready) return
        // 只要连上就清零退避计数：后面哪怕订阅失败，也按「新的一轮」重新退避。
        reconnectAttempt = 0
        car = connectedCar

        // 注意：getCarManager(Class) 这个重载是 API 33 才加的，Android 11 上会抛
        // NoSuchMethodError 被吞成 null，表现为「车机没有 CarPropertyManager」。
        // 必须用字符串 API（API 24+ 就有）。
        val mgr = runCatching {
            connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }.getOrNull()
        if (mgr == null) {
            fail("车机没有 CarPropertyManager")
            return
        }
        manager = mgr

        val list = runCatching { mgr.propertyList }.getOrElse {
            fail("读取车辆属性列表失败：${describe(it)}")
            return
        }
        list.forEach { configs[it.propertyId] = it }

        if (list.isEmpty()) {
            fail("车辆属性列表为空：车机没有接入 VHAL，或应用没有 android.car.permission.*")
            return
        }

        bindCoreSignals()
        bindOptionalSignals()

        if (subscribed.isEmpty()) {
            fail("没有可用的车辆信号：${errorSummary()}")
            return
        }

        ready = true
        emit(SourceState(LABEL, ready = true, detail = bindingSummary()))
        publishNow()
    }

    // ---------------- 订阅 ----------------

    private fun bindCoreSignals() {
        firstSupported(
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY,
            VehiclePropertyIds.PERF_VEHICLE_SPEED,
        )?.let { subscribe(it, "车速") }

        subscribeIfPresent(VehiclePropertyIds.ENGINE_RPM, "转速")
        subscribeIfPresent(VehiclePropertyIds.ENGINE_COOLANT_TEMP, "水温")

        firstSupported(
            VehiclePropertyIds.CURRENT_GEAR,
            VehiclePropertyIds.GEAR_SELECTION,
        )?.let { subscribe(it, "挡位") }

        subscribeIfPresent(VehiclePropertyIds.TIRE_PRESSURE, "胎压")
    }

    private fun bindOptionalSignals() {
        subscribeIfPresent(VehiclePropertyIds.FUEL_LEVEL, "油量")
        subscribeIfPresent(VehiclePropertyIds.RANGE_REMAINING, "续航")
        subscribeIfPresent(VehiclePropertyIds.PERF_ODOMETER, "总里程")
        subscribeIfPresent(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, "车外温度")
        subscribeIfPresent(VehiclePropertyIds.INSTANTANEOUS_FUEL_ECONOMY, "瞬时油耗")
    }

    private fun firstSupported(vararg ids: Int): Int? = ids.firstOrNull { configs.containsKey(it) }

    private fun subscribeIfPresent(propertyId: Int, label: String) {
        if (!configs.containsKey(propertyId)) {
            errors[label] = "车机未提供"
            return
        }
        subscribe(propertyId, label)
    }

    private fun subscribe(propertyId: Int, label: String) {
        val mgr = manager ?: return
        try {
            // rate=0：有变化才回调，避免高速信号把主线程刷爆。
            val registered = mgr.registerCallback(callback, propertyId, 0f)
            if (!registered) {
                errors[label] = "注册回调失败"
                return
            }
            subscribed += propertyId
            errors.remove(label)

            val config = configs[propertyId]
            val areas = if (propertyId == VehiclePropertyIds.TIRE_PRESSURE) {
                config?.areaIds ?: intArrayOf()
            } else {
                intArrayOf(0)
            }
            areas.forEach { area ->
                val value: CarPropertyValue<Any?>? = runCatching {
                    mgr.getProperty<Any?>(propertyId, area)
                }.getOrNull()
                value?.let { consume(it) }
            }
        } catch (e: SecurityException) {
            errors[label] = "权限被拒绝"
        } catch (t: Throwable) {
            errors[label] = describe(t)
        }
    }

    // ---------------- 信号解析 ----------------

    private fun consume(value: CarPropertyValue<*>) {
        val raw = value.value ?: return
        when (value.propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED,
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY,
            -> latest[KEY_SPEED] = normalizeSpeed(number(raw), value.propertyId)

            VehiclePropertyIds.ENGINE_RPM ->
                latest[KEY_RPM] = number(raw)

            VehiclePropertyIds.ENGINE_COOLANT_TEMP ->
                latest[KEY_COOLANT] = number(raw)

            VehiclePropertyIds.CURRENT_GEAR ->
                currentGear = gearLabel(raw).takeUnless { it == "--" }

            VehiclePropertyIds.GEAR_SELECTION ->
                selectedGear = gearLabel(raw).takeUnless { it == "--" }

            VehiclePropertyIds.TIRE_PRESSURE -> {
                val index = wheelIndex(value.areaId)
                if (index >= 0) tires[index] = normalizeTirePressure(number(raw))
            }

            VehiclePropertyIds.FUEL_LEVEL ->
                latest[KEY_FUEL] = normalizeFuel(number(raw))

            VehiclePropertyIds.RANGE_REMAINING ->
                latest[KEY_RANGE] = normalizeRange(number(raw))

            VehiclePropertyIds.PERF_ODOMETER ->
                latest[KEY_ODO] = number(raw)

            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE ->
                latest[KEY_AMBIENT] = number(raw)

            VehiclePropertyIds.INSTANTANEOUS_FUEL_ECONOMY ->
                latest[KEY_CONSUMPTION] = number(raw)
        }
        schedulePublish()
    }

    private fun number(raw: Any): Float = when (raw) {
        is Number -> raw.toFloat()
        else -> raw.toString().toFloatOrNull() ?: 0f
    }

    private fun gearLabel(raw: Any): String {
        val gear = (raw as? Number)?.toInt() ?: return "--"
        return when (gear) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_DRIVE -> "D"
            VehicleGear.GEAR_FIRST -> "D1"
            VehicleGear.GEAR_SECOND -> "D2"
            VehicleGear.GEAR_THIRD -> "D3"
            VehicleGear.GEAR_FOURTH -> "D4"
            VehicleGear.GEAR_FIFTH -> "D5"
            VehicleGear.GEAR_SIXTH -> "D6"
            VehicleGear.GEAR_SEVENTH -> "D7"
            VehicleGear.GEAR_EIGHTH -> "D8"
            VehicleGear.GEAR_NINTH -> "D9"
            else -> "--"
        }
    }

    private fun wheelIndex(areaId: Int): Int = when (areaId) {
        VehicleAreaWheel.WHEEL_LEFT_FRONT -> 0
        VehicleAreaWheel.WHEEL_RIGHT_FRONT -> 1
        VehicleAreaWheel.WHEEL_LEFT_REAR -> 2
        VehicleAreaWheel.WHEEL_RIGHT_REAR -> 3
        else -> -1
    }

    /**
     * AOSP 规范车速是 m/s。个别厂商的 SPEED_DISPLAY 直接给 km/h：
     * 配置上限落在 100~400 或读数大于 100 时按 km/h 处理，避免 3.6 倍误差。
     */
    private fun normalizeSpeed(raw: Float, propertyId: Int): Float {
        val max = (configs[propertyId]?.maxValue as? Number)?.toFloat() ?: 0f
        val looksKmh = max in 100f..400f || raw > 100f
        return if (looksKmh) raw else raw * 3.6f
    }

    /** AOSP 规范是 kPa；有的厂商给 bar 或 psi。按数量级兼容。 */
    private fun normalizeTirePressure(raw: Float): Float {
        val bar = when {
            raw > 100f -> raw / 100f          // kPa
            raw > 10f -> raw / 14.5038f       // psi
            else -> raw                       // bar
        }
        return bar.coerceIn(TirePressures.UNAVAILABLE + 1f, TirePressures.MAX_BAR)
    }

    /** AOSP 的 RANGE_REMAINING 是米；部分厂商给 km。用配置上限和数值一起判断。 */
    private fun normalizeRange(raw: Float): Float {
        val max = (configs[VehiclePropertyIds.RANGE_REMAINING]?.maxValue as? Number)
            ?.toFloat() ?: 0f
        val meters = max > 2_000f || raw > 2_000f
        return if (meters) raw / 1_000f else raw
    }

    /**
     * AOSP 的 FUEL_LEVEL 单位在不同厂商实现里可能是百分比、升或毫升。
     * 先用属性上限判断量纲，再按 60 L 油箱兜底。
     */
    private fun normalizeFuel(raw: Float): Float {
        val max = (configs[VehiclePropertyIds.FUEL_LEVEL]?.maxValue as? Number)
            ?.toFloat() ?: 0f
        val percent = when {
            max in 0.1f..100f -> raw / max * 100f
            raw <= 100f -> raw
            else -> raw / 60_000f * 100f
        }
        return percent.coerceIn(0f, 100f)
    }

    // ---------------- 发布 ----------------

    private fun schedulePublish() {
        val now = SystemClock.uptimeMillis()
        val wait = lastPublishAt + MIN_PUBLISH_INTERVAL_MS - now
        if (wait <= 0L) {
            publishNow()
        } else if (!publishPending) {
            publishPending = true
            handler.postDelayed(publishRunnable, wait)
        }
    }

    private fun publishNow() {
        if (disposed || !ready) return
        publishPending = false
        lastPublishAt = SystemClock.uptimeMillis()

        // 没读到的字段用 NaN / 负数标记，绘制层显示 --，不用 0 冒充真实值。
        val telemetry = Telemetry(
            speed = latest[KEY_SPEED] ?: Float.NaN,
            rpm = (latest[KEY_RPM] ?: -1f).toInt(),
            gear = currentGear ?: selectedGear ?: "--",
            coolant = latest[KEY_COOLANT] ?: Float.NaN,
            fuel = latest[KEY_FUEL] ?: Float.NaN,
            range = latest[KEY_RANGE] ?: Float.NaN,
            // VHAL 没有标准的 12V 电瓶电压属性，留 0，UI 显示为不可用。
            voltage = 0f,
            odo = latest[KEY_ODO] ?: Float.NaN,
            trip = Float.NaN,
            consumption = latest[KEY_CONSUMPTION] ?: Float.NaN,
            ambient = latest[KEY_AMBIENT] ?: Float.NaN,
            tires = TirePressures(tires[0], tires[1], tires[2], tires[3]),
            source = Telemetry.SOURCE_CAR,
        )
        onUpdate?.invoke(telemetry)
    }

    // ---------------- 状态 ----------------

    private fun emit(state: SourceState) {
        onState?.invoke(state)
    }

    private fun fail(message: String) {
        if (disposed || ready) return
        Log.w(TAG, message)
        emit(SourceState(LABEL, ready = false, connecting = false, detail = message))
        scheduleReconnect(message)
    }

    private fun bindingSummary(): String = buildString {
        append("已订阅 ")
        append(subscribed.size)
        append(" 个信号")
        val missing = errors.filterValues { it == "车机未提供" }.keys
        val problems = errors.filterValues { it != "车机未提供" }
        if (missing.isNotEmpty()) {
            append("；车机未提供：")
            append(missing.joinToString("、"))
        }
        if (problems.isNotEmpty()) {
            append("；")
            append(problems.entries.joinToString("；") { "${it.key}(${it.value})" })
        }
    }

    private fun errorSummary(): String =
        errors.entries.joinToString("；") { "${it.key}=${it.value}" }

    private fun signalName(propertyId: Int): String = when (propertyId) {
        VehiclePropertyIds.PERF_VEHICLE_SPEED,
        VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY,
        -> "车速"
        VehiclePropertyIds.ENGINE_RPM -> "转速"
        VehiclePropertyIds.ENGINE_COOLANT_TEMP -> "水温"
        VehiclePropertyIds.CURRENT_GEAR,
        VehiclePropertyIds.GEAR_SELECTION,
        -> "挡位"
        VehiclePropertyIds.TIRE_PRESSURE -> "胎压"
        VehiclePropertyIds.FUEL_LEVEL -> "油量"
        VehiclePropertyIds.RANGE_REMAINING -> "续航"
        VehiclePropertyIds.PERF_ODOMETER -> "总里程"
        VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> "车外温度"
        VehiclePropertyIds.INSTANTANEOUS_FUEL_ECONOMY -> "瞬时油耗"
        else -> "0x${Integer.toHexString(propertyId)}"
    }

    private fun describe(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")

    companion object {
        private const val TAG = "LynkCluster/Car"
        private const val LABEL = "CAR"
        private const val CONNECT_TIMEOUT_MS = 6_000L

        /** 指数退避上限。车机休眠唤醒后 CarService 可能几十秒才就绪。 */
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        /** 高速信号最多 20 Hz 更新；仪表 60 fps 重绘，没必要把每个 VHAL 事件都送上去。 */
        private const val MIN_PUBLISH_INTERVAL_MS = 50L

        private const val KEY_SPEED = -101
        private const val KEY_RPM = -102
        private const val KEY_COOLANT = -103
        private const val KEY_FUEL = -104
        private const val KEY_RANGE = -105
        private const val KEY_ODO = -106
        private const val KEY_AMBIENT = -107
        private const val KEY_CONSUMPTION = -108
    }
}

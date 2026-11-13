package com.duan.lynkcluster

import android.car.VehicleGear
import android.car.hardware.CarPropertyValue
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.Collections
import kotlin.math.roundToInt

/**
 * 厂商 CarProxyService 数据源。
 *
 * 这条链路来自车机系统适配层 `com.autolink.adapterbinder.ICarProxyService`，
 * 属于厂商适配层提供的车辆服务能力。普通第三方应用实测可以拿到
 * Binder；这里只做只读订阅，不调用 setIntProperty。
 *
 * 之所以直接走原始 Binder transaction，而不是依赖厂商 AIDL jar：
 * - AIDL 类只在车机 boot classpath 中存在，无法打包进普通 APK；
 * - ICarProxyCallback 必须是一个 Binder 对象，Java Proxy 无法替代；
 * - transaction 编号和 Parcel 布局已从系统适配层静态确认。
 */
class CarProxyTelemetrySource(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : TelemetrySource {

    private val stateLock = Any()
    private val latest = HashMap<Int, Float>()

    private var client: CarProxyClient? = null
    private var onUpdate: ((Telemetry) -> Unit)? = null
    private var onState: ((SourceState) -> Unit)? = null

    private var started = false
    @Volatile private var ready = false
    @Volatile private var disposed = false
    private var lastPublishAt = 0L

    /** 断线重连计数：成功 ready 后清零；退避 1s→2s→4s→8s→15s 封顶。 */
    private var reconnectAttempt = 0

    @Volatile private var currentGear: String? = null
    @Volatile private var selectedGear: String? = null

    private val publishRunnable = Runnable { publishNow() }

    private val reconnectRunnable = Runnable {
        if (disposed || ready) return@Runnable
        val attempt = reconnectAttempt
        emit(
            SourceState(
                LABEL,
                ready = false,
                connecting = true,
                detail = "正在重连 CarProxyService（第 $attempt 次）…",
            ),
        )
        Thread({ connectAndSubscribe() }, "CarProxyReconnect").apply {
            isDaemon = true
            start()
        }
    }

    private val connectTimeout = Runnable {
        if (!ready && !disposed) {
            fail("连接 CarProxyService 超时")
        }
    }

    override fun start(onUpdate: (Telemetry) -> Unit, onState: (SourceState) -> Unit) {
        if (started) return
        started = true
        disposed = false
        this.onUpdate = onUpdate
        this.onState = onState

        emit(SourceState(LABEL, ready = false, connecting = true, detail = "正在连接厂商 CarProxyService…"))
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)

        Thread({ connectAndSubscribe() }, "CarProxyTelemetry").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        disposed = true
        started = false
        ready = false
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(publishRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(reconnectRunnable)

        val oldClient = synchronized(stateLock) {
            val current = client
            client = null
            current
        }
        if (oldClient != null) {
            Thread({ oldClient.close() }, "CarProxyTelemetryClose").apply {
                isDaemon = true
                start()
            }
        }

        synchronized(stateLock) { latest.clear() }
        currentGear = null
        selectedGear = null
        onUpdate = null
        onState = null
    }

    private fun connectAndSubscribe() {
        val newClient = CarProxyClient(
            onServiceDied = { onServiceLost() },
            onEvent = { propertyId, value -> consume(propertyId, value) },
        )

        if (!newClient.open()) {
            fail(newClient.lastError ?: "CarProxyService 不可用")
            return
        }
        if (disposed) {
            newClient.close()
            return
        }

        synchronized(stateLock) { client = newClient }

        if (!newClient.registerCallback()) {
            newClient.close()
            fail(newClient.lastError ?: "注册 CarProxy 回调失败")
            return
        }

        val subscribedHere = LinkedHashMap<Int, String>()
        val errorsHere = LinkedHashMap<String, String>()
        SIGNALS.forEach { signal ->
            if (newClient.addEventProId(signal.id)) {
                subscribedHere[signal.id] = signal.label
            } else {
                // false 多数情况是「proIds already contains」：service 全局集合里已存在
                // （含开机默认转发列表），事件照样推给所有回调 —— 不能当失败处理。
                errorsHere[signal.label] = "已注册/未新增"
            }
        }

        // 事件推送不依赖 addEventProId 的返回值：registerCallback 成功后 service 就会把
        // 它的固定转发列表推给所有回调（实测含未订阅的厂商 ID）。所以这里不再设
        // 「核心属性必须订阅成功」的门禁 —— 那个门禁曾把好好的数据流拦死。

        // 挡位快照也不读了：CarProxy 挡位是上电快照（见 consume 里的说明），走 VHAL。

        handler.removeCallbacks(connectTimeout)
        handler.post {
            if (disposed) return@post
            ready = true
            reconnectAttempt = 0
            emit(
                SourceState(
                    LABEL,
                    ready = true,
                    detail = bindingSummary(subscribedHere, errorsHere),
                ),
            )
            publishNow()
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }
    }

    /**
     * 快照轮询：CarProxy 回调只在值变化时推送，静止/熄火时永远不会来第一帧；
     * 轮询保证挡位、车速、转速在启动后立刻有快照可用。
     */
    private val pollRunnable: Runnable = Runnable {
        if (disposed || !ready) return@Runnable
        val c = synchronized(stateLock) { client } ?: return@Runnable
        POLL_IDS.forEach { id ->
            val value = c.getIntProperty(id)
            if (value != INVALID_INT) consumeRaw(id, value)
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun consume(propertyId: Int, value: CarPropertyValue<*>?) {
        if (disposed) return
        consumeRaw(propertyId, value?.value ?: return)
    }

    private fun consumeRaw(propertyId: Int, raw: Any) {
        if (disposed) return

        // 标定期全量记录：单位/编码等装车实测后回收进字段映射。
        Log.i(TAG, "cb 0x${Integer.toHexString(propertyId).uppercase()} = $raw")

        if (raw is ByteArray) {
            Log.i(TAG, "cb 0x${Integer.toHexString(propertyId).uppercase()} bytes=${raw.toHex()}")
            return
        }
        val number = number(raw)

        synchronized(stateLock) {
            when (propertyId) {
                PROPERTY_PERF_VEHICLE_SPEED -> {
                    if (number.isFinite() && number >= 0f) latest[KEY_SPEED_MPS] = number
                }

                PROPERTY_PERF_VEHICLE_SPEED_DISPLAY -> {
                    if (number.isFinite() && number >= 0f) latest[KEY_SPEED_DISPLAY_MPS] = number
                }

                PROPERTY_CB_SPEED_CB,
                PROPERTY_CB_SPEED_XMA,
                -> if (number.isFinite() && number >= 0f) {
                    latest[KEY_SPEED_KMH] = number.coerceAtMost(MAX_KMH)
                }

                // CLUSTER 固定显示区车速：仪表自身消费的 km/h 信号（待实车标定）。
                PROPERTY_CLUSTER_VEHSPD_A,
                PROPERTY_CLUSTER_VEHSPD_QF,
                -> if (number.isFinite() && number in 0f..MAX_KMH) {
                    latest[KEY_SPEED_KMH] = number
                }

                PROPERTY_ENGINE_RPM -> {
                    if (number.isFinite() && number in 0f..MAX_RPM) latest[KEY_RPM] = number
                }

                // CLUSTER 功率流转速 + CAN EngN：int 型，0..12000 有效。
                PROPERTY_CLUSTER_ENGSPD,
                PROPERTY_ENGN_RX,
                PROPERTY_ENGN_XMA,
                -> if (number.isFinite() && number in 0f..MAX_RPM) latest[KEY_RPM] = number

                PROPERTY_ENGINE_COOLANT_TEMP -> {
                    if (number.isFinite() && number in MIN_COOLANT..MAX_COOLANT) {
                        latest[KEY_COOLANT] = number
                    }
                }

                PROPERTY_ENV_OUTSIDE_TEMPERATURE -> {
                    if (number.isFinite() && number in MIN_AMBIENT..MAX_AMBIENT) {
                        latest[KEY_AMBIENT] = number
                    }
                }

                // 挡位不再从 CarProxy 取：实车验证（2026-11-08）它的挡位是上电快照，
                // 挂挡后不更新，会把 VHAL 的实时挡位盖住（Hybrid 按 CarProxy 优先合并）。
                // VHAL 的 CURRENT_GEAR 是活的，挡位统一走 VHAL。
                PROPERTY_CURRENT_GEAR,
                PROPERTY_GEAR_SELECTION,
                PROPERTY_CLUSTER_GEAR,
                PROPERTY_GEAR_RX,
                PROPERTY_GEAR_XMA,
                -> {
                    // 只记日志供标定，不进字段。
                    val gearInt = (raw as? Number)?.toInt()
                    Log.d(TAG, "挡位信号(不采用) 0x${Integer.toHexString(propertyId).uppercase()} = $gearInt")
                }
            }
        }
        schedulePublish()
    }

    private fun gearLabelOrNull(gear: Int): String? = when (gear) {
        VehicleGear.GEAR_PARK, VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_NEUTRAL,
        VehicleGear.GEAR_DRIVE,
        -> gearLabel(gear)

        else -> null
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun schedulePublish() {
        val now = SystemClock.uptimeMillis()
        synchronized(stateLock) {
            if (now - lastPublishAt < MIN_PUBLISH_INTERVAL_MS) return
            lastPublishAt = now
        }
        handler.post(publishRunnable)
    }

    private fun publishNow() {
        if (disposed || !ready) return

        val snapshot = synchronized(stateLock) {
            Snapshot(
                speedMps = latest[KEY_SPEED_MPS],
                speedDisplayMps = latest[KEY_SPEED_DISPLAY_MPS],
                speedKmh = latest[KEY_SPEED_KMH],
                rpm = latest[KEY_RPM],
                coolant = latest[KEY_COOLANT],
                ambient = latest[KEY_AMBIENT],
                gear = currentGear ?: selectedGear,
            )
        }

        val speed = when {
            snapshot.speedMps != null -> speedFromStandardMps(snapshot.speedMps)
            snapshot.speedDisplayMps != null -> speedFromStandardMps(snapshot.speedDisplayMps)
            snapshot.speedKmh != null -> speedFromDisplayKmh(snapshot.speedKmh)
            else -> Float.NaN
        }

        onUpdate?.invoke(
            Telemetry(
                speed = speed,
                rpm = snapshot.rpm?.roundToInt() ?: -1,
                gear = snapshot.gear ?: "--",
                coolant = snapshot.coolant ?: Float.NaN,
                fuel = Float.NaN,
                range = Float.NaN,
                voltage = 0f,
                odo = Float.NaN,
                trip = Float.NaN,
                consumption = Float.NaN,
                ambient = snapshot.ambient ?: Float.NaN,
                tires = TirePressures.NONE,
                source = Telemetry.SOURCE_CAR,
            ),
        )
    }

    private fun emit(state: SourceState) {
        onState?.invoke(state)
    }

    private fun fail(message: String) {
        if (disposed || ready) return
        Log.w(TAG, message)
        handler.post {
            if (!ready && !disposed) {
                emit(SourceState(LABEL, ready = false, connecting = false, detail = message))
                scheduleReconnect()
            }
        }
    }

    /**
     * 断线重连：电源状态切换（AP_POWER_MODE_REQUEST）会让 CarProxyService 重置
     * 事件路由但保留 binder，或直接换掉 binder 对象。两种情况都不重启进程，
     * 所以 deathRecipient / 事件流停止是唯一信号 —— 必须自己退避重连。
     */
    private fun scheduleReconnect() {
        if (disposed) return
        val attempt = ++reconnectAttempt
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "CarProxy 重连 $MAX_RECONNECT_ATTEMPTS 次未成功，停止重试")
            return
        }
        // 1s → 2s → 4s → 8s → 16s → 30s 封顶，与 CarService 那条链路保持一致。
        val delay = minOf(1_000L shl (attempt - 1).coerceAtMost(5), MAX_RECONNECT_DELAY_MS)
        Log.w(TAG, "CarProxy 连接中断，第 $attempt 次重连将在 ${delay}ms 后开始")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun onServiceLost() {
        if (disposed) return
        ready = false
        handler.post {
            if (disposed) return@post
            Log.w(TAG, "CarProxyService binder 已断开（服务侧重置或重启）")
            emit(
                SourceState(
                    LABEL,
                    ready = false,
                    connecting = false,
                    detail = "CarProxyService 已断开，停止显示最后一帧",
                ),
            )
            onUpdate?.invoke(Telemetry.IDLE)
            scheduleReconnect()
        }
    }

    private fun bindingSummary(
        subscribedHere: Map<Int, String>,
        errorsHere: Map<String, String>,
    ): String = buildString {
        append("已订阅 CarProxy ${subscribedHere.size} 项：")
        append(subscribedHere.values.joinToString("、"))
        if (errorsHere.isNotEmpty()) {
            append("；未接受：")
            append(errorsHere.entries.joinToString("、") { "${it.key}(${it.value})" })
        }
    }

    private fun number(raw: Any): Float = when (raw) {
        is Number -> raw.toFloat()
        else -> raw.toString().toFloatOrNull() ?: Float.NaN
    }

    private fun gearLabel(raw: Any): String {
        val gear = (raw as? Number)?.toInt() ?: return "--"
        return gearLabel(gear)
    }

    private fun gearLabel(gear: Int): String = when (gear) {
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

    private data class Snapshot(
        val speedMps: Float?,
        val speedDisplayMps: Float?,
        val speedKmh: Float?,
        val rpm: Float?,
        val coolant: Float?,
        val ambient: Float?,
        val gear: String?,
    )

    private data class Signal(val id: Int, val label: String)

    companion object {
        private const val TAG = "LynkCluster/CarProxy"
        private const val LABEL = "CARPROXY"
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private const val MIN_PUBLISH_INTERVAL_MS = 50L
        private const val POLL_INTERVAL_MS = 500L
        private const val INVALID_INT = -1
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        /** 轮询的 int 型信号：转速（三种来源）+ CLUSTER 车速。挡位已改走 VHAL，不再轮询。 */
        private val POLL_IDS = intArrayOf(
            PROPERTY_CLUSTER_ENGSPD,
            PROPERTY_ENGN_RX,
            PROPERTY_ENGN_XMA,
            PROPERTY_CLUSTER_VEHSPD_A,
            PROPERTY_CLUSTER_VEHSPD_QF,
        )

        private const val PROPERTY_PERF_VEHICLE_SPEED = 0x11600207
        private const val PROPERTY_PERF_VEHICLE_SPEED_DISPLAY = 0x11600208
        private const val PROPERTY_ENGINE_COOLANT_TEMP = 0x11600301
        private const val PROPERTY_ENGINE_RPM = 0x11600305
        private const val PROPERTY_GEAR_SELECTION = 0x11400400
        private const val PROPERTY_CURRENT_GEAR = 0x11400401
        private const val PROPERTY_ENV_OUTSIDE_TEMPERATURE = 0x11600703

        // android.hardware.automotive.vehicle.V2_0.VendorVehiclePropertyCB
        private const val PROPERTY_CB_SPEED_CB = 0x2170881c

        // vendor.ecarx.xma.automotive.vehicle.V1_0.VendorVehicleProperty
        private const val PROPERTY_CB_SPEED_XMA = 0x21708805

        // ---- 2026-11-07 实车 service call 实测：int 型、addEventProId 接受的厂商信号 ----
        // VehicleProperty 厂商扩展段（CLUSTER_*）：仪表自身消费的信号
        private const val PROPERTY_CLUSTER_VEHSPD_A = 0x21401B54
        private const val PROPERTY_CLUSTER_VEHSPD_QF = 0x21401B55
        private const val PROPERTY_CLUSTER_ENGSPD = 0x21401DA3
        private const val PROPERTY_CLUSTER_GEAR = 0x21401A02

        // VendorSignalPropertyRX：CAN 信号直通（EngN 带 E2E 校验组）
        private const val PROPERTY_ENGN_RX = 0x21607CCB
        private const val PROPERTY_ENGN_XMA = 0x21607C9F
        private const val PROPERTY_GEAR_RX = 0x21407CCF
        private const val PROPERTY_GEAR_XMA = 0x21407CA3

        // VendorVehiclePropertyPA：byte[] 型（CAN 原始帧），只记日志不进字段
        private const val PROPERTY_TRIP1_PA = 0x21708FBE
        private const val PROPERTY_TRIP1_XMA = 0x21708EFA

        private const val MAX_RPM = 12_000f
        private const val MAX_KMH = 350f
        private const val MIN_COOLANT = -40f
        private const val MAX_COOLANT = 150f
        private const val MIN_AMBIENT = -50f
        private const val MAX_AMBIENT = 80f

        private const val KEY_SPEED_MPS = 1
        private const val KEY_SPEED_DISPLAY_MPS = 2
        private const val KEY_SPEED_KMH = 3
        private const val KEY_RPM = 4
        private const val KEY_COOLANT = 5
        private const val KEY_AMBIENT = 6

        private val SIGNALS = listOf(
            Signal(PROPERTY_PERF_VEHICLE_SPEED, "标准车速"),
            Signal(PROPERTY_PERF_VEHICLE_SPEED_DISPLAY, "显示车速"),
            Signal(PROPERTY_CB_SPEED_CB, "CB 显示车速"),
            Signal(PROPERTY_CB_SPEED_XMA, "XMA 显示车速"),
            Signal(PROPERTY_CLUSTER_VEHSPD_A, "CLUSTER 车速A"),
            Signal(PROPERTY_CLUSTER_VEHSPD_QF, "CLUSTER 车速QF"),
            Signal(PROPERTY_ENGINE_RPM, "发动机转速"),
            Signal(PROPERTY_ENGINE_COOLANT_TEMP, "冷却液温度"),
            Signal(PROPERTY_CLUSTER_ENGSPD, "CLUSTER 转速"),
            Signal(PROPERTY_ENGN_RX, "EngN 转速RX"),
            Signal(PROPERTY_ENGN_XMA, "EngN 转速XMA"),
            Signal(PROPERTY_GEAR_SELECTION, "挡位选择"),
            Signal(PROPERTY_CURRENT_GEAR, "当前挡位"),
            Signal(PROPERTY_CLUSTER_GEAR, "CLUSTER 挡位"),
            Signal(PROPERTY_GEAR_RX, "挡位RX"),
            Signal(PROPERTY_GEAR_XMA, "挡位XMA"),
            Signal(PROPERTY_ENV_OUTSIDE_TEMPERATURE, "车外温度"),
            Signal(PROPERTY_TRIP1_PA, "里程Trip1 PA"),
            Signal(PROPERTY_TRIP1_XMA, "里程Trip1 XMA"),
        )

        internal fun knownSignals(): List<Pair<Int, String>> = SIGNALS.map { it.id to it.label }

        internal fun speedFromStandardMps(raw: Float): Float = raw * 3.6f

        internal fun speedFromDisplayKmh(raw: Float): Float = raw.coerceIn(0f, MAX_KMH)
    }
}

/**
 * 只读的 CarProxy Binder 客户端。
 *
 * transaction 编号来自反编译：
 *   1 register, 2 unregister, 3 addEventProId, 4 setIntProperty, 5 getIntProperty
 * 回调 transaction 编号为 1。
 */
internal class CarProxyClient(
    private val onServiceDied: () -> Unit = {},
    private val onEvent: (Int, CarPropertyValue<*>?) -> Unit,
) {
    var lastError: String? = null
        private set

    private var remote: IBinder? = null
    private var callbackRegistered = false
    private var deathLinked = false

    private val deathRecipient = IBinder.DeathRecipient {
        remote = null
        callbackRegistered = false
        deathLinked = false
        onServiceDied()
    }

    private val callbackBinder = object : android.os.Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_ON_CHANGE_EVENT) {
                return super.onTransact(code, data, reply, flags)
            }
            return try {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                val propertyId = data.readInt()
                val present = data.readInt() != 0
                val value = if (present) {
                    CarPropertyValue.CREATOR.createFromParcel(data)
                } else {
                    null
                }
                onEvent(propertyId, value)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "CarProxy 回调解析失败", t)
                true
            }
        }
    }

    fun open(): Boolean {
        lastError = null
        if (remote?.isBinderAlive == true) return true

        return try {
            HiddenApi.allow()
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
            val binder = getService.invoke(null, SERVICE_NAME) as? IBinder
            if (binder == null || !binder.isBinderAlive) {
                lastError = "ServiceManager 中没有 $SERVICE_NAME"
                remote = null
                false
            } else {
                val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
                if (descriptor != null && descriptor != DESCRIPTOR) {
                    lastError = "服务接口不匹配：$descriptor"
                    remote = null
                    return false
                }
                deathLinked = runCatching {
                    binder.linkToDeath(deathRecipient, 0)
                    true
                }.getOrDefault(false)
                remote = binder
                true
            }
        } catch (t: Throwable) {
            lastError = "读取厂商服务失败：${t.javaClass.simpleName}: ${t.message}"
            remote = null
            false
        }
    }

    fun registerCallback(): Boolean {
        val binder = remote ?: run {
            lastError = "CarProxyService 未连接"
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            if (!binder.transact(TRANSACTION_REGISTER, data, reply, 0)) {
                lastError = "registerCarProxyService transaction 被拒绝"
                false
            } else {
                reply.readException()
                callbackRegistered = true
                true
            }
        } catch (t: Throwable) {
            lastError = "注册回调失败：${t.javaClass.simpleName}: ${t.message}"
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun addEventProId(propertyId: Int): Boolean {
        val binder = remote ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(propertyId)
            if (!binder.transact(TRANSACTION_ADD_EVENT_ID, data, reply, 0)) {
                false
            } else {
                reply.readException()
                reply.readInt() != 0
            }
        } catch (t: Throwable) {
            Log.d(TAG, "addEventProId(0x${Integer.toHexString(propertyId)}) 失败：${t.message}")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getIntProperty(propertyId: Int): Int {
        val binder = remote ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(propertyId)
            if (!binder.transact(TRANSACTION_GET_INT, data, reply, 0)) {
                -1
            } else {
                reply.readException()
                reply.readInt()
            }
        } catch (t: Throwable) {
            -1
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun close() {
        val binder = remote
        if (binder != null && deathLinked) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        deathLinked = false
        if (binder != null && callbackRegistered) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeStrongBinder(callbackBinder)
                binder.transact(TRANSACTION_UNREGISTER, data, reply, 0)
                reply.readException()
            } catch (_: Throwable) {
                // 服务已退出时无需再报告。
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        callbackRegistered = false
        remote = null
    }

    companion object {
        private const val TAG = "LynkCluster/CarProxyClient"
        private const val SERVICE_NAME = "CarProxyService"
        private const val DESCRIPTOR = "com.autolink.adapterbinder.ICarProxyService"
        private const val CALLBACK_DESCRIPTOR = "com.autolink.adapterbinder.ICarProxyCallback"

        private const val TRANSACTION_REGISTER = 1
        private const val TRANSACTION_UNREGISTER = 2
        private const val TRANSACTION_ADD_EVENT_ID = 3
        private const val TRANSACTION_GET_INT = 5
        private const val TRANSACTION_ON_CHANGE_EVENT = 1
    }
}

internal object HiddenApi {
    @Volatile
    private var attempted = false

    fun allow() {
        if (attempted) return
        synchronized(this) {
            if (attempted) return
            attempted = true
            try {
                val runtime = Class.forName("dalvik.system.VMRuntime")
                val getRuntime = runtime.getDeclaredMethod("getRuntime")
                    .apply { isAccessible = true }
                val setExemptions = runtime.getDeclaredMethod(
                    "setHiddenApiExemptions",
                    Array<String>::class.java,
                ).apply { isAccessible = true }
                setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
            } catch (t: Throwable) {
                Log.w("LynkCluster/CarProxy", "隐藏 API 豁免失败：${t.javaClass.simpleName}")
            }
        }
    }
}

/**
 * 只读诊断：列厂商服务、检查 CarProxy 属性订阅和短时回调。
 * 运行在调用方的工作线程上，不修改任何车辆属性。
 */
internal object CarProxyProbe {
    fun probe(): String = buildString {
        appendLine("=== 厂商车辆服务 ===")
        val services = listOf(
            "CarProxyService",
            "DimNaviService",
            "DimWholeService",
            "ecarxcar_service",
            "ecarx_multidisplay",
            "car_service",
        )
        services.forEach { name ->
            val binder = serviceBinder(name)
            if (binder == null) {
                appendLine("✗ $name")
            } else {
                val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull() ?: "?"
                appendLine("✓ $name  $descriptor")
            }
        }

        appendLine()
        appendLine("=== CarProxy 属性 ===")
        val events = Collections.synchronizedMap(LinkedHashMap<Int, String>())
        val client = CarProxyClient(
            onEvent = { propertyId, value ->
                events[propertyId] = describeValue(value)
            },
        )
        try {
            if (!client.open()) {
                appendLine("CarProxyService 不可用：${client.lastError}")
                return@buildString
            }
            if (!client.registerCallback()) {
                appendLine("注册回调失败：${client.lastError}")
                return@buildString
            }

            val accepted = mutableListOf<Int>()
            CarProxyTelemetrySource.knownSignals().forEach { (id, label) ->
                val ok = client.addEventProId(id)
                val current = client.getIntProperty(id)
                val text = if (ok) "✓" else "✗"
                appendLine(
                    "$text ${hex(id)}  $label  " +
                        "getInt=${if (current == -1) "-1/unavailable" else current.toString()}",
                )
                if (ok) accepted += id
            }

            if (accepted.isNotEmpty()) {
                appendLine()
                appendLine("监听 1.5 秒，等待初始/变化回调…")
                Thread.sleep(1_500)
                if (events.isEmpty()) {
                    appendLine("未收到回调；可能车辆静止且服务只在变化时推送。")
                } else {
                    events.toSortedMap().forEach { (id, value) ->
                        appendLine("callback ${hex(id)} = $value")
                    }
                }
            }
        } catch (t: Throwable) {
            appendLine("CarProxy 诊断中断：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            client.close()
        }
    }

    private fun serviceBinder(name: String): IBinder? = runCatching {
        HiddenApi.allow()
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
        getService.invoke(null, name) as? IBinder
    }.getOrNull()

    private fun describeValue(value: CarPropertyValue<*>?): String {
        if (value == null) return "null"
        return "${value.value} (${value.value?.javaClass?.simpleName ?: "?"}) " +
            "area=${value.areaId} ts=${value.timestamp}"
    }

    private fun hex(id: Int): String = "0x" + Integer.toHexString(id).uppercase()
}

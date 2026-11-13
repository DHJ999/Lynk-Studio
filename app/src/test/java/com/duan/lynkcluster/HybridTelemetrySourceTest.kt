package com.duan.lynkcluster

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混合数据源的逐字段合并规则。
 *
 * 全部用假的 [TelemetrySource] 注入，不碰 CarService / CarProxy，也不依赖
 * Android 运行时，所以是纯 JVM 单测。
 *
 * 数据源内部改成了协程 + StateFlow：生产环境跑 `Dispatchers.Default`，
 * 这里统一注入 [Dispatchers.Unconfined]，让 `tryEmit → scan → StateFlow → 回调`
 * 整条链路在测试线程上同步完成，断言不用加 sleep。
 */
class HybridTelemetrySourceTest {

    @Test
    fun vendorRpmIsUsedWhenOnlyVendorHasIt() {
        val vendor = FakeSource()
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(vendor, car, dispatcher = Dispatchers.Unconfined)
        start(hybrid, recorder, vendor, car)

        vendor.push(frame(rpm = 2_500))
        car.push(frame())

        assertEquals(2_500, recorder.frame!!.rpm)
        assertTrue(recorder.state!!.ready)
        assertTrue(
            recorder.state!!.detail!!,
            recorder.state!!.detail!!.contains("转速=CarProxy"),
        )
    }

    @Test
    fun carCoolantIsUsedWhenOnlyCarHasIt() {
        val vendor = FakeSource()
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(vendor, car, dispatcher = Dispatchers.Unconfined)
        start(hybrid, recorder, vendor, car)

        vendor.push(frame(speed = 30f))
        car.push(frame(coolant = 88f))

        assertEquals(88f, recorder.frame!!.coolant, 0.01f)
        val detail = recorder.state!!.detail!!
        assertTrue(detail, detail.contains("水温=VHAL"))
        assertTrue(detail, detail.contains("车速=CarProxy"))
    }

    @Test
    fun fieldsAreMergedIndependentlyAcrossBothSources() {
        val vendor = FakeSource()
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(vendor, car, dispatcher = Dispatchers.Unconfined)
        start(hybrid, recorder, vendor, car)

        // CarProxy 这边只有车速和车外温度；VHAL 这边只有转速、水温、挡位、油量。
        vendor.push(frame(speed = 52f, ambient = 21f))
        car.push(frame(rpm = 1_800, coolant = 91f, gear = "D4", fuel = 63f))

        val merged = recorder.frame!!
        assertEquals(52f, merged.speed, 0.01f)
        assertEquals(21f, merged.ambient, 0.01f)
        assertEquals(1_800, merged.rpm)
        assertEquals(91f, merged.coolant, 0.01f)
        assertEquals("D4", merged.gear)
        assertEquals(63f, merged.fuel, 0.01f)
        assertEquals(Telemetry.SOURCE_CAR, merged.source)
    }

    @Test
    fun vendorWinsPerFieldWhenBothSourcesConflict() {
        val vendor = FakeSource()
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(vendor, car, dispatcher = Dispatchers.Unconfined)
        start(hybrid, recorder, vendor, car)

        vendor.push(frame(speed = 60f, rpm = 2_000, coolant = 80f, gear = "D3"))
        car.push(frame(speed = 88f, rpm = 3_000, coolant = 95f, gear = "D5"))

        val merged = recorder.frame!!
        assertEquals(60f, merged.speed, 0.01f)
        assertEquals(2_000, merged.rpm)
        assertEquals(80f, merged.coolant, 0.01f)
        assertEquals("D3", merged.gear)

        val detail = recorder.state!!.detail!!
        assertTrue(detail, detail.contains("车速=CarProxy"))
        assertTrue(detail, detail.contains("转速=CarProxy"))
        assertTrue(detail, detail.contains("水温=CarProxy"))
        assertTrue(detail, detail.contains("挡位=CarProxy"))
    }

    @Test
    fun notReadyWhenNeitherSourceHasValues() {
        val vendor = FakeSource()
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(vendor, car, dispatcher = Dispatchers.Unconfined)
        start(hybrid, recorder, vendor, car)

        vendor.push(frame())
        car.push(frame())

        assertNotNull(recorder.state)
        assertFalse(recorder.state!!.ready)
        // 首帧语义变了：StateFlow 恒有值，没有有效字段时给的是 IDLE（全缺失），
        // 不再是「什么都不发」。这样静止/熄火时仪表也不会停在上一帧。
        assertEquals(Telemetry.IDLE, recorder.frame)
        assertTrue(
            recorder.state!!.detail!!,
            recorder.state!!.detail!!.contains("还没读到有效车辆信号"),
        )
    }

    @Test
    fun tiresAreMergedPerWheel() {
        val vendor = FakeSource()
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(vendor, car, dispatcher = Dispatchers.Unconfined)
        start(hybrid, recorder, vendor, car)

        vendor.push(frame(tires = TirePressures(frontLeft = 2.4f)))
        car.push(
            frame(
                tires = TirePressures(
                    frontRight = 2.3f,
                    rearLeft = 2.2f,
                    rearRight = 2.1f,
                ),
            ),
        )

        val merged = recorder.frame!!.tires
        assertEquals(2.4f, merged.frontLeft, 0.01f)
        assertEquals(2.3f, merged.frontRight, 0.01f)
        assertEquals(2.2f, merged.rearLeft, 0.01f)
        assertEquals(2.1f, merged.rearRight, 0.01f)
        assertTrue(recorder.state!!.detail!!.contains("胎压=CarProxy+VHAL"))
    }

    @Test
    fun degradesToCarWhenVendorCannotStart() {
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(
            vendor = null,
            car = car,
            vendorGap = "此设备没有 CarProxyService / 厂商车辆接口",
            dispatcher = Dispatchers.Unconfined,
        )
        hybrid.start({ recorder.frame = it }, { recorder.state = it })
        car.state(SourceState("CAR", ready = true))
        car.push(frame(speed = 42f, coolant = 86f))

        assertEquals(42f, recorder.frame!!.speed, 0.01f)
        assertTrue(recorder.state!!.ready)
        assertTrue(recorder.state!!.detail!!.contains("车速=VHAL"))
    }

    @Test
    fun reportsBothFailuresWhenNoSourceCanStart() {
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(
            vendor = null,
            car = null,
            vendorGap = "没有 CarProxyService",
            carGap = "没有 android.car",
            dispatcher = Dispatchers.Unconfined,
        )
        hybrid.start({ recorder.frame = it }, { recorder.state = it })

        assertNotNull(recorder.state)
        assertFalse(recorder.state!!.ready)
        assertFalse(recorder.state!!.connecting)
        val detail = recorder.state!!.detail!!
        assertTrue(detail, detail.contains("CarProxy: 没有 CarProxyService"))
        assertTrue(detail, detail.contains("VHAL: 没有 android.car"))
    }

    @Test
    fun fallsBackToCarAfterVendorFails() {
        val vendor = FakeSource()
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(vendor, car, dispatcher = Dispatchers.Unconfined)
        start(hybrid, recorder, vendor, car)

        vendor.state(SourceState("CARPROXY", ready = false, connecting = false, detail = "服务未安装"))
        car.push(frame(rpm = 1_200))

        assertEquals(1_200, recorder.frame!!.rpm)
        assertTrue(recorder.state!!.ready)
        assertTrue(recorder.state!!.detail!!.contains("转速=VHAL"))
        assertTrue(recorder.state!!.detail!!.contains("CarProxy 不可用（服务未安装）"))
    }

    @Test
    fun terminalChildFrameIsDroppedInsteadOfFrozen() {
        val vendor = FakeSource()
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(vendor, car, dispatcher = Dispatchers.Unconfined)
        start(hybrid, recorder, vendor, car)

        vendor.push(frame(speed = 60f, rpm = 2_000))
        assertEquals(60f, recorder.frame!!.speed, 0.01f)

        // CarProxy 断开，另一路也判定不可用：必须变成"未就绪"，不能继续用旧帧冒充实时值。
        vendor.state(SourceState("CARPROXY", ready = false, connecting = false, detail = "服务已断开"))
        car.state(SourceState("CAR", ready = false, connecting = false, detail = "无权限"))
        car.push(frame())

        assertFalse(recorder.state!!.ready)
        assertFalse(recorder.state!!.connecting)
        assertTrue(
            recorder.state!!.detail!!,
            recorder.state!!.detail!!.contains("CarProxy: 服务已断开"),
        )

        // 恢复时只能出新的真实字段，断开前的旧车速不能被带回来。
        car.push(frame(coolant = 88f))
        assertTrue(recorder.frame!!.speed.isNaN())
        assertEquals(-1, recorder.frame!!.rpm)
        assertEquals(88f, recorder.frame!!.coolant, 0.01f)
    }

    @Test
    fun stopStopsBothChildren() {
        val vendor = FakeSource()
        val car = FakeSource()
        val recorder = Recorder()
        val hybrid = HybridTelemetrySource(vendor, car, dispatcher = Dispatchers.Unconfined)
        start(hybrid, recorder, vendor, car)

        hybrid.stop()

        assertTrue(vendor.stopped)
        assertTrue(car.stopped)
    }

    // ---------------- 测试脚手架 ----------------

    private fun start(
        hybrid: HybridTelemetrySource,
        recorder: Recorder,
        vendor: FakeSource,
        car: FakeSource,
    ) {
        hybrid.start({ recorder.frame = it }, { recorder.state = it })
        vendor.state(SourceState("CARPROXY", ready = true))
        car.state(SourceState("CAR", ready = true))
    }

    private fun frame(
        speed: Float = Float.NaN,
        rpm: Int = -1,
        gear: String = "--",
        coolant: Float = Float.NaN,
        fuel: Float = Float.NaN,
        ambient: Float = Float.NaN,
        tires: TirePressures = TirePressures.NONE,
    ): Telemetry = Telemetry(
        speed = speed,
        rpm = rpm,
        gear = gear,
        coolant = coolant,
        fuel = fuel,
        range = Float.NaN,
        voltage = 0f,
        odo = Float.NaN,
        trip = Float.NaN,
        consumption = Float.NaN,
        ambient = ambient,
        tires = tires,
        source = Telemetry.SOURCE_CAR,
    )

    private class Recorder {
        var frame: Telemetry? = null
        var state: SourceState? = null
    }

    private class FakeSource : TelemetrySource {
        var stopped = false
        private var onUpdate: ((Telemetry) -> Unit)? = null
        private var onState: ((SourceState) -> Unit)? = null

        override fun start(onUpdate: (Telemetry) -> Unit, onState: (SourceState) -> Unit) {
            this.onUpdate = onUpdate
            this.onState = onState
        }

        override fun stop() {
            stopped = true
            onUpdate = null
            onState = null
        }

        fun push(frame: Telemetry) {
            onUpdate?.invoke(frame)
        }

        fun state(state: SourceState) {
            onState?.invoke(state)
        }
    }
}

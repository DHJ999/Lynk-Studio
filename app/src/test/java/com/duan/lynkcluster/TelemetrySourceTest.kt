package com.duan.lynkcluster

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 普通 Android 环境没有 android.car。数据源工厂必须安静地返回 null，
 * 而不是在启动仪表盘时抛 NoClassDefFoundError。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelemetrySourceTest {

    @Test
    fun carSourceCreationDoesNotThrow() {
        // Robolectric 的 android-all 自带 android.car 桩；普通手机没有。
        // 工厂两条路径都必须安静返回，不能把异常抛到仪表盘启动流程里。
        val source = TelemetrySources.createCar(RuntimeEnvironment.getApplication())
        assertTrue(source == null || source is CarTelemetrySource)
    }

    @Test
    fun probeDegradesGracefully() {
        val report = TelemetrySources.probe(RuntimeEnvironment.getApplication())
        assertTrue(report, report.isNotBlank())
    }
}

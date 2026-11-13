package com.duan.lynkcluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarProxyTelemetrySourceTest {

    @Test
    fun vendorSourceCreationDoesNotThrow() {
        val source = TelemetrySources.createVendor(RuntimeEnvironment.getApplication())
        assertTrue(source == null || source is CarProxyTelemetrySource)
    }

    @Test
    fun standardSpeedUsesMetersPerSecond() {
        assertEquals(36f, CarProxyTelemetrySource.speedFromStandardMps(10f), 0.01f)
        assertEquals(0f, CarProxyTelemetrySource.speedFromStandardMps(0f), 0.01f)
    }

    @Test
    fun displaySpeedIsClampedToVehicleRange() {
        assertEquals(88f, CarProxyTelemetrySource.speedFromDisplayKmh(88f), 0.01f)
        assertEquals(0f, CarProxyTelemetrySource.speedFromDisplayKmh(-8f), 0.01f)
        assertEquals(350f, CarProxyTelemetrySource.speedFromDisplayKmh(999f), 0.01f)
    }

    @Test
    fun knownSignalsContainCoreVehicleProperties() {
        val ids = CarProxyTelemetrySource.knownSignals().map { it.first }.toSet()
        assertTrue(0x11600207 in ids)
        assertTrue(0x11600305 in ids)
        assertTrue(0x11600301 in ids)
        assertTrue(0x11400400 in ids)
    }
}

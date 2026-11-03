package com.openobd.session

import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.CustomSensors
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.obd.ObdClient
import com.openobd.core.transport.DemoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDataManagerTest {
    @Test
    fun pollsStandardCalculatedAndCustomSensors() = runBlocking {
        val t = DemoTransport(latencyMs = 1).also { it.open() }
        val elm = Elm327(t)
        elm.initialize(ElmConfig())
        val client = ObdClient(elm)
        val supported = client.discover()
        CustomSensors.update(listOf(CustomPid("t", "Trans temp", "TFT", "221E1C", "((A*256)+B)/16", -40.0, 150.0, "°C", "7E1")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val errors = mutableListOf<Throwable>()
        val live = LiveDataManager(scope)
        live.onAdapterLost = { errors += IllegalStateException("adapter lost") }
        live.subscribe("test", listOf("010C", "0105", "CALC_FUEL", "X_t"))
        live.start(client, supported)
        try {
            withTimeout(10_000) {
                while (!live.readings.value.keys.containsAll(listOf("010C", "0105", "CALC_FUEL", "X_t"))) delay(50)
            }
        } finally {
            println("readings=" + live.readings.value.keys + " errors=" + errors)
            live.stop()
            scope.cancel()
            CustomSensors.update(emptyList())
        }
        assertTrue(errors.isEmpty())
        assertTrue(live.readings.value.getValue("X_t").value in 50.0..90.0)
    }
}

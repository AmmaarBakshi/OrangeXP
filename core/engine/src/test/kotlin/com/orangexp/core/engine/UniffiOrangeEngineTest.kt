package com.orangexp.core.engine

import com.orangexp.core.engine.ffi.DayInput
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.engine.ffi.DeviceEvent
import com.orangexp.core.engine.ffi.DeviceEventKind
import com.orangexp.core.engine.ffi.EngineException
import com.orangexp.core.engine.ffi.Measurement
import com.orangexp.core.engine.ffi.TimetableSlot
import com.orangexp.core.engine.ffi.Weekday
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the real Rust library (built for the host) through the generated
 * bindings: verifies the FFI contract, not the algorithms (tested in Rust).
 */
class UniffiOrangeEngineTest {

    private val engine = UniffiOrangeEngine()

    @Test
    fun `default configuration round-trips through JSON`() {
        val config = engine.defaultConfig()
        assertEquals(emptyList(), engine.validate(config))
        assertEquals(config, engine.parseConfig(engine.serializeConfig(config)))
    }

    @Test
    fun `malformed configuration raises a typed exception`() {
        assertFailsWith<EngineException.InvalidConfig> { engine.parseConfig("{") }
    }

    @Test
    fun `metric keys match the engine catalog`() {
        assertEquals(MetricKeys.ALL.toSet(), engine.metricCatalog().map { it.key }.toSet())
    }

    @Test
    fun `evaluates a day with an explained breakdown`() {
        val evaluation = engine.evaluateDay(
            DayInput(
                epochDay = 20_720,
                measurements = listOf(
                    Measurement(MetricKeys.SLEEP_MINUTES, 381.0),
                    Measurement(MetricKeys.STEPS, 6_267.0),
                ),
            ),
            engine.defaultConfig(),
        )
        assertEquals(2610L + 2507L, evaluation.totalPoints)
        assertEquals(evaluation.totalPoints, evaluation.contributions.sumOf { it.points })
        assertEquals(DayState.GREEN, evaluation.state.state)
    }

    @Test
    fun `detects sleep from device events`() {
        val day = 20_720L * 86_400_000L
        val hour = 3_600_000L
        val events = listOf(
            DeviceEvent(day, DeviceEventKind.UNLOCK),
            DeviceEvent(day + 60_000, DeviceEventKind.INTERACTION),
            DeviceEvent(day + 10 * 60_000, DeviceEventKind.SCREEN_OFF),
            DeviceEvent(day + 7 * hour, DeviceEventKind.UNLOCK),
            DeviceEvent(day + 7 * hour + 60_000, DeviceEventKind.INTERACTION),
        )
        val report = engine.analyzeSleepDay(
            events, day, day + 24 * hour, day + 8 * hour, 0, engine.defaultConfig().sleep,
        )
        assertEquals(1, report.analysis.sessions.size)
        assertTrue(report.summary.sleepMinutes > 5u * 60u)
    }

    @Test
    fun `plans departure for the first travelling class`() {
        val slot = TimetableSlot(
            id = "dsa", title = "Data Structures", weekday = Weekday.MONDAY,
            startMinute = 495u, endMinute = 555u, requiresTravel = true,
            travelMinutes = 60u, preparationMinutes = null,
        )
        val plan = assertNotNull(
            engine.planFirstDeparture(listOf(slot), Weekday.MONDAY, emptyList(), engine.defaultConfig().travel),
        )
        assertEquals(405, plan.leaveMinute)
    }
}

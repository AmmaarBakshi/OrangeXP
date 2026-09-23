package com.orangexp.core.data

import com.orangexp.core.data.tracking.StepAccumulator
import com.orangexp.core.data.tracking.StepAccumulator.Baseline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StepAccumulatorTest {

    @Test
    fun `first sample only sets the baseline`() {
        assertEquals(0, StepAccumulator.delta(null, 12_000))
    }

    @Test
    fun `counts steps since the previous sample`() {
        assertEquals(350, StepAccumulator.delta(Baseline(12_000, 0), 12_350))
    }

    @Test
    fun `reboot resets the counter`() {
        assertEquals(40, StepAccumulator.delta(Baseline(12_000, 0), 40))
    }

    @Test
    fun `baseline encoding round-trips and rejects garbage`() {
        val baseline = Baseline(123, 456)
        assertEquals(baseline, Baseline.decode(baseline.encode()))
        assertNull(Baseline.decode("nope"))
        assertNull(Baseline.decode(null))
    }
}

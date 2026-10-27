package com.EDanaII.pillreminder

import org.junit.Assert.assertEquals
import org.junit.Test

class DoseScheduleTest {
    @Test fun futureStartIsFirstDose() {
        assertEquals(10_000, DoseSchedule.next(10_000, 8, 1_000))
    }

    @Test fun exactBoundaryAdvancesOneInterval() {
        assertEquals(28_800_000, DoseSchedule.next(0, 8, 0))
    }

    @Test fun lateDismissalDoesNotShiftAnchor() {
        val hour = 3_600_000L
        assertEquals(16 * hour, DoseSchedule.next(0, 8, 8 * hour + 37 * 60_000))
    }

    @Test fun skipsMissedIntervalsWithoutStormOfCatchUpAlarms() {
        assertEquals(96 * 3_600_000L, DoseSchedule.next(0, 8, 91 * 3_600_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidIntervals() { DoseSchedule.next(0, 0, 0) }

    @Test(expected = ArithmeticException::class)
    fun overflowCannotWrapToPastDate() { DoseSchedule.next(0, 1, Long.MAX_VALUE) }
}

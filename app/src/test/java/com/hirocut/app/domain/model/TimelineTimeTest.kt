package com.hirocut.app.domain.model

import org.junit.Assert.*
import org.junit.Test

class TimelineTimeTest {
    @Test fun parsesMillisecondsAndRejectsInvalidTimes() {
        assertEquals(3_723_004L, TimelineTime.parse("01:02:03.004"))
        assertEquals(360_000_001L, TimelineTime.parse("100:00:00.001"))
        listOf("-01:00:00.000", "00:60:00.000", "00:00:60.000", "00:00:00.1", "hello", "9999999999999:00:00.000").forEach {
            assertNull(it, TimelineTime.parse(it))
        }
    }
    @Test fun formatsWithoutLosingMillisecondsOrWrappingHours() {
        assertEquals("00:00:00.000", TimelineTime.format(-1))
        assertEquals("01:02:03.004", TimelineTime.format(3_723_004))
        assertEquals("100:00:00.001", TimelineTime.format(360_000_001))
    }
    @Test fun seekingIsBoundedAndHandlesUnknownDuration() {
        assertEquals(0L, TimelineTime.position(-0.2f, 600_000))
        assertEquals(600_000L, TimelineTime.position(1.2f, 600_000))
        assertEquals(0L, TimelineTime.position(Float.NaN, 600_000))
        assertEquals(0L, TimelineTime.position(.5f, 0))
        assertEquals(300_000L, TimelineTime.position(.5f, 600_000))
        assertEquals(0f, TimelineTime.fraction(5, 0))
    }
    @Test fun longVideoCoordinatesStayMonotonicAndPreserveEnds() {
        val duration = 100L * 60 * 60 * 1000
        val positions = (0..1000).map { TimelineTime.position(it / 1000f, duration) }
        assertEquals(0L, positions.first())
        assertEquals(duration, positions.last())
        assertTrue(positions.zipWithNext().all { (left, right) -> left <= right })
        assertEquals(1f, TimelineTime.fraction(duration + 1, duration))
    }
}

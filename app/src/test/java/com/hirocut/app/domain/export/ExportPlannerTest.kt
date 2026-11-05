package com.hirocut.app.domain.export

import com.hirocut.app.domain.editor.*
import com.hirocut.app.domain.model.SourceVideo
import org.junit.Assert.*
import org.junit.Test

class ExportPlannerTest {
    private val source = SourceVideo("content://test/video", "test", 6000, 640, 360, 0, true, true)
    private val spec = MediaSpec("video/avc", "audio/mp4a-latm", 6_000_000, 640, 360, 0, 1_000_000, 128_000, 48_000, 1,
        (0..5).map { SyncPoint(it * 1_000_000L, true) })
    private fun project(vararg ranges: Pair<Long, Long>) = EditProject(6000, ranges.mapIndexed { i, (start, end) -> ClipRange("clip-$i", start, end) })
    @Test fun fastModeContractsOnlyInwardAndReportsActualRange() {
        val plan = ExportPlanner.build(source, project(125L to 2900L), ExportMode.Fast, spec)
        assertEquals(1_000_000L, plan.segments.single().inUs)
        assertEquals(2_000_000L, plan.segments.single().outUs)
        assertTrue(plan.adjusted)
    }
    @Test fun preciseModePreservesNonKeyframeRequests() {
        val plan = ExportPlanner.build(source, project(125L to 875L), ExportMode.Precise, spec)
        assertEquals(125_000L, plan.segments.single().inUs)
        assertEquals(875_000L, plan.segments.single().outUs)
        assertFalse(plan.adjusted)
    }
    @Test fun orderAndDuplicateSelectionsAreNeverNormalized() {
        val plan = ExportPlanner.build(source, project(4000L to 5000L, 0L to 1000L, 0L to 1000L), ExportMode.Fast, spec)
        assertEquals(listOf(4_000_000L, 0L, 0L), plan.segments.map { it.inUs })
        assertEquals(3_000_000L, plan.durationUs)
    }
    @Test fun fastCannotInventAKeyframeForShortSelection() {
        val error = assertThrows(ExportFailure::class.java) { ExportPlanner.build(source, project(125L to 875L), ExportMode.Fast, spec) }
        assertEquals(ExportProblem.FastUnavailable, error.problem)
    }
    @Test fun unverifiedSyncPointsAreNotUsed() {
        val nonIdr = spec.copy(syncPoints = listOf(SyncPoint(0, true), SyncPoint(1_000_000, false)))
        assertThrows(ExportFailure::class.java) { ExportPlanner.build(source, project(1000L to 2000L), ExportMode.Fast, nonIdr) }
    }
    @Test fun microsecondKeyframesAreNotRoundedBackToMilliseconds() {
        val preciseKey = spec.copy(syncPoints = listOf(SyncPoint(0, true), SyncPoint(1_000_499, true), SyncPoint(2_000_999, true)))
        val plan = ExportPlanner.build(source, project(1000L to 3000L), ExportMode.Fast, preciseKey)
        assertEquals(1_000_499L, plan.segments.single().inUs)
        assertEquals(2_000_999L, plan.segments.single().outUs)
    }
    @Test fun sourceMismatchAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ExportPlanner.build(source.copy(durationMs = 6001), project(0L to 1000L), ExportMode.Fast, spec) }
        val huge = SourceVideo("content://test/huge", "test", Long.MAX_VALUE, 640, 360, 0, false, true)
        assertThrows(ArithmeticException::class.java) { ExportPlanner.build(huge, EditorRules.create(Long.MAX_VALUE, "a"), ExportMode.Precise, spec) }
    }
}

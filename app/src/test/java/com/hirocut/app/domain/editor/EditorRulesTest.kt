package com.hirocut.app.domain.editor

import org.junit.Assert.*
import org.junit.Test

class EditorRulesTest {
    @Test fun everyNewTrackUsesFullSourceEvenAfterEarlierTrims() {
        var project = EditorRules.create(600_000, "a")
        project = EditorRules.range(project, "a", 60_000, 120_000)
        project = EditorRules.add(project, "b")
        assertEquals(ClipRange("b", 0, 600_000), project.clips.last())
        project = EditorRules.range(project, "b", 300_000, 420_000)
        project = EditorRules.add(project, "c")
        assertEquals(ClipRange("c", 0, 600_000), project.clips.last())
        project = EditorRules.range(project, "c", 480_000, 540_000)
        assertEquals(240_000L, project.totalDurationMs)
        assertEquals(600_000L, project.durationMs)
    }
    @Test fun explicitPlayheadStartsNewRangeAndRejectsEof() {
        val initial = EditorRules.create(6000, "a")
        assertEquals(ClipRange("b", 1500, 6000), EditorRules.add(initial, "b", 1500).clips.last())
        assertThrows(IllegalArgumentException::class.java) { EditorRules.add(initial, "b", 6000) }
        assertThrows(IllegalArgumentException::class.java) { EditorRules.add(initial, "b", -1) }
    }
    @Test fun movePreservesRangesAndAllowsReverseSourceOrderAndDuplicates() {
        val project = EditProject(600_000, listOf(ClipRange("a", 60_000, 120_000),
            ClipRange("b", 60_000, 120_000), ClipRange("c", 480_000, 540_000)))
        val moved = EditorRules.move(project, "c", -2)
        assertEquals(listOf("c", "a", "b"), moved.clips.map { it.id })
        assertEquals(project.clips.last(), moved.clips.first())
        assertEquals(180_000L, moved.totalDurationMs)
    }
    @Test fun cannotDeleteLastTrackAndOrderMovesAreBounded() {
        val project = EditorRules.create(1, "a")
        assertEquals(project, EditorRules.remove(project, "a"))
        assertEquals(project, EditorRules.move(project, "a", Int.MIN_VALUE))
        assertEquals(project, EditorRules.move(project, "missing", 1))
    }
    @Test fun invalidRangesSourcesAndDuplicateIdsAreRejected() {
        listOf<() -> Any>(
            { ClipRange("a", -1, 5) }, { ClipRange("a", 5, 5) }, { ClipRange("a", 6, 5) },
            { EditorRules.create(0, "a") }, { EditProject(10, listOf(ClipRange("a", 0, 11))) },
            { EditorRules.add(EditorRules.create(10, "a"), "a") }, { EditProject(10, emptyList()) },
        ).forEach { operation -> assertThrows(IllegalArgumentException::class.java) { operation() } }
    }
    @Test fun oneMillisecondRangesStayIntegerExactOnLongSources() {
        val duration = 360_000_000L
        val project = EditorRules.range(EditorRules.create(duration, "a"), "a", duration - 1, duration)
        assertEquals(1L, project.totalDurationMs)
        assertEquals(duration - 1, project.clips.single().inMs)
    }
    @Test fun outputDurationOverflowIsRejected() {
        val clip = ClipRange("a", 0, Long.MAX_VALUE)
        assertThrows(ArithmeticException::class.java) { EditProject(Long.MAX_VALUE, listOf(clip, clip.copy(id = "b"))) }
    }
}

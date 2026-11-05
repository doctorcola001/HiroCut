package com.hirocut.app.domain.editor

import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    private val initial = EditorRules.create(600_000, "a")
    @Test fun aLongGestureIsExactlyOneUndoEntry() {
        val h = EditHistory(initial)
        h.beginGesture()
        repeat(100) { h.update(EditorRules.range(h.current, "a", (it + 1) * 100L, 600_000)) }
        assertFalse(h.canUndo)
        h.finishGesture()
        val after = h.current
        h.undo(); assertEquals(initial, h.current); assertFalse(h.canUndo)
        h.redo(); assertEquals(after, h.current)
    }
    @Test fun cancelAndNoOpGesturePreserveRedo() {
        val h = EditHistory(initial)
        h.update(EditorRules.add(initial, "b")); h.undo()
        h.beginGesture(); h.update(EditorRules.range(h.current, "a", 10, 20)); h.cancelGesture()
        assertEquals(initial, h.current); assertTrue(h.canRedo); assertFalse(h.canUndo)
        h.beginGesture(); h.finishGesture(); assertTrue(h.canRedo)
    }
    @Test fun aNewEditAfterUndoClearsRedo() {
        val h = EditHistory(initial)
        h.update(EditorRules.add(initial, "b")); h.undo()
        h.update(EditorRules.range(initial, "a", 10, 20))
        assertFalse(h.canRedo)
    }
    @Test fun addDeleteMoveAndRangeCanAllBeUndoneAndRedone() {
        val h = EditHistory(initial)
        val states = mutableListOf(initial)
        h.update(EditorRules.add(h.current, "b")); states.add(h.current)
        h.update(EditorRules.range(h.current, "a", 10, 20)); states.add(h.current)
        h.update(EditorRules.move(h.current, "b", -1)); states.add(h.current)
        h.update(EditorRules.remove(h.current, "a")); states.add(h.current)
        states.dropLast(1).reversed().forEach { expected -> h.undo(); assertEquals(expected, h.current) }
        states.drop(1).forEach { expected -> h.redo(); assertEquals(expected, h.current) }
    }
    @Test fun boundedHistoryAndNoOpCommandsDoNotGrowForever() {
        val h = EditHistory(initial, capacity = 2)
        h.update(initial); assertFalse(h.canUndo)
        for (i in 1..3) h.update(EditorRules.range(h.current, "a", i.toLong(), 600_000))
        h.undo(); h.undo(); h.undo()
        assertEquals(1L, h.current.clips.single().inMs)
    }
    @Test fun undoDuringGestureCancelsTransientValuesFirst() {
        val h = EditHistory(initial)
        h.update(EditorRules.add(initial, "b"))
        h.beginGesture(); h.update(EditorRules.range(h.current, "a", 10, 20)); h.undo()
        assertEquals(initial, h.current)
        assertFalse(h.inGesture)
    }
}

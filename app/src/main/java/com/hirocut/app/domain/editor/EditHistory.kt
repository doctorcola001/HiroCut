package com.hirocut.app.domain.editor

/** A gesture has one baseline, regardless of how many pointer updates it produces. */
class EditHistory(initial: EditProject, private val capacity: Int = 100) {
    init { require(capacity > 0) }
    var current: EditProject = initial
        private set
    private val past = ArrayDeque<EditProject>()
    private val future = ArrayDeque<EditProject>()
    private var baseline: EditProject? = null
    val inGesture: Boolean get() = baseline != null
    val canUndo: Boolean get() = past.isNotEmpty() && !inGesture
    val canRedo: Boolean get() = future.isNotEmpty() && !inGesture

    fun update(next: EditProject) {
        require(next.durationMs == current.durationMs)
        if (next == current) return
        if (!inGesture) { remember(current); future.clear() }
        current = next
    }
    fun beginGesture() { if (!inGesture) baseline = current }
    fun finishGesture() {
        val before = baseline ?: return
        baseline = null
        if (current != before) { remember(before); future.clear() }
    }
    fun cancelGesture() { baseline?.let { current = it }; baseline = null }
    fun undo() {
        cancelGesture()
        if (past.isNotEmpty()) { future.addLast(current); current = past.removeLast() }
    }
    fun redo() {
        cancelGesture()
        if (future.isNotEmpty()) { remember(current); current = future.removeLast() }
    }
    private fun remember(project: EditProject) {
        if (past.size == capacity) past.removeFirst()
        past.addLast(project)
    }
}

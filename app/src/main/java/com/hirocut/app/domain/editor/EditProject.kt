package com.hirocut.app.domain.editor

data class ClipRange(val id: String, val inMs: Long, val outMs: Long) {
    init { require(id.isNotBlank() && inMs >= 0 && inMs < outMs) }
    val durationMs: Long get() = outMs - inMs
}

data class EditProject(val durationMs: Long, val clips: List<ClipRange>) {
    init {
        require(durationMs > 0 && clips.isNotEmpty())
        require(clips.all { it.outMs <= durationMs })
        require(clips.map { it.id }.distinct().size == clips.size)
        clips.fold(0L) { sum, clip -> Math.addExact(sum, clip.durationMs) }
    }
    val totalDurationMs: Long get() = clips.sumOf { it.durationMs }
    val hasEdits: Boolean get() = clips.size > 1 || clips.first().inMs != 0L || clips.first().outMs != durationMs
}

enum class Boundary { In, Out }
enum class AddClipResult { Added, AtEnd, Unavailable }
enum class MarkBoundaryResult { Applied, InvalidRange, FrameUnavailable, Unavailable }

object EditorRules {
    fun create(durationMs: Long, id: String) = EditProject(durationMs, listOf(ClipRange(id, 0, durationMs)))
    fun add(project: EditProject, id: String, startMs: Long = 0): EditProject {
        require(startMs in 0 until project.durationMs)
        return project.copy(clips = project.clips + ClipRange(id, startMs, project.durationMs))
    }
    fun remove(project: EditProject, id: String): EditProject =
        if (project.clips.size == 1) project else project.copy(clips = project.clips.filterNot { it.id == id })
    fun range(project: EditProject, id: String, start: Long, end: Long): EditProject =
        project.copy(clips = project.clips.map { if (it.id == id) ClipRange(id, start, end) else it })
    fun move(project: EditProject, id: String, delta: Int): EditProject {
        val index = project.clips.indexOfFirst { it.id == id }
        if (index < 0) return project
        val target = (index.toLong() + delta).coerceIn(0, project.clips.lastIndex.toLong()).toInt()
        if (target == index) return project
        val clips = project.clips.toMutableList()
        clips.add(target, clips.removeAt(index))
        return project.copy(clips = clips)
    }
}

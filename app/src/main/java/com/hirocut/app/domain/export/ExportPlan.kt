package com.hirocut.app.domain.export

import com.hirocut.app.domain.editor.ClipRange
import com.hirocut.app.domain.editor.EditProject
import com.hirocut.app.domain.model.SourceVideo

enum class ExportMode { Fast, Precise }
enum class ExportProblem { Unsupported, HdrUnsupported, FastUnavailable, EmptyRange, InsufficientSpace, SourceUnreadable, Processing, UnexpectedFallback, NotStreamCopy, Verification, Destination, Interrupted }
class ExportFailure(val problem: ExportProblem) : Exception()
data class SyncPoint(val timeUs: Long, val isIdr: Boolean)
data class MediaSpec(val videoMime: String, val audioMime: String?, val durationUs: Long,
    val width: Int, val height: Int, val rotation: Int, val videoBitrate: Int, val audioBitrate: Int,
    val sampleRate: Int, val channels: Int, val syncPoints: List<SyncPoint> = emptyList(),
    val colorStandard: Int = 0, val colorTransfer: Int = 0, val colorRange: Int = 0)
data class ExportSegment(val id: String, val requestedInMs: Long, val requestedOutMs: Long,
    val inUs: Long, val outUs: Long) {
    init { require(inUs >= 0 && outUs > inUs) }
    val durationUs get() = outUs - inUs
    val adjusted get() = inUs != Math.multiplyExact(requestedInMs, 1000L) || outUs != Math.multiplyExact(requestedOutMs, 1000L)
}
data class ExportPlan(val source: SourceVideo, val mode: ExportMode, val spec: MediaSpec,
    val segments: List<ExportSegment>) {
    val durationUs: Long get() = segments.fold(0L) { sum, segment -> Math.addExact(sum, segment.durationUs) }
    val adjusted: Boolean get() = segments.any { it.adjusted }
}

object ExportPlanner {
    fun build(source: SourceVideo, project: EditProject, mode: ExportMode, spec: MediaSpec): ExportPlan {
        require(source.durationMs == project.durationMs)
        val endOfVideo = minOf(Math.multiplyExact(source.durationMs, 1000L), spec.durationUs)
        val keys = spec.syncPoints.filter { it.isIdr && it.timeUs in 0..endOfVideo }.map { it.timeUs }.distinct().sorted()
        val segments = project.clips.map { clip ->
            val requestedStart = Math.multiplyExact(clip.inMs, 1000L)
            val requestedEnd = minOf(Math.multiplyExact(clip.outMs, 1000L), endOfVideo)
            val start: Long
            val end: Long
            if (mode == ExportMode.Fast) {
                start = keys.firstOrNull { it >= requestedStart } ?: throw ExportFailure(ExportProblem.FastUnavailable)
                end = (keys + endOfVideo).lastOrNull { it <= requestedEnd } ?: throw ExportFailure(ExportProblem.FastUnavailable)
            } else { start = requestedStart; end = requestedEnd }
            if (end <= start) throw ExportFailure(if (mode == ExportMode.Fast) ExportProblem.FastUnavailable else ExportProblem.EmptyRange)
            ExportSegment(clip.id, clip.inMs, clip.outMs, start, end)
        }
        return ExportPlan(source, mode, spec, segments).also { it.durationUs }
    }
}

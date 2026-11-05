package com.hirocut.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hirocut.app.R
import com.hirocut.app.domain.editor.Boundary
import com.hirocut.app.domain.editor.ClipRange
import com.hirocut.app.domain.model.TimelineTime
import kotlin.math.roundToInt
import kotlin.math.abs

/** Each row has the same global 0..duration mapping and a 48dp hit target per handle. */
@Composable
fun RangeTimeline(
    clip: ClipRange, durationMs: Long, positionMs: Long, selected: Boolean,
    onSelect: () -> Unit, onSeek: (Long) -> Unit, onSeekFinished: (Long) -> Unit,
    onBegin: (Boundary) -> Unit, onDrag: (Boundary, Long) -> Unit,
    onFinish: () -> Unit, onCancel: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceVariant
    val pointerColor = MaterialTheme.colorScheme.secondary
    val latestClip by rememberUpdatedState(clip)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestSeekFinished by rememberUpdatedState(onSeekFinished)
    val latestBegin by rememberUpdatedState(onBegin)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestFinish by rememberUpdatedState(onFinish)
    val latestCancel by rememberUpdatedState(onCancel)
    val seekDescription = stringResource(R.string.track_seek)
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).testTag("timeline-${clip.id}")) {
        val density = LocalDensity.current
        val inset = with(density) { 24.dp.toPx() }
        val trackWidth = with(density) { maxWidth.toPx() - 48.dp.toPx() }.coerceAtLeast(1f)
        fun timeAt(x: Float) = TimelineTime.position((x - inset) / trackWidth, durationMs)
        var dragEdge: Boundary? by remember(clip.id) { mutableStateOf(null) }
        var dragX by remember(clip.id) { mutableFloatStateOf(0f) }
        var lastSeekX by remember(clip.id) { mutableFloatStateOf(0f) }
        // Resolve overlap from the actual pointer position, rather than letting one handle cover the other.
        Box(Modifier.fillMaxSize()
            .pointerInput(durationMs, trackWidth) {
                detectTapGestures { point -> latestSelect(); latestSeekFinished(timeAt(point.x)) }
            }
            .pointerInput(durationMs, trackWidth) {
                detectHorizontalDragGestures(
                    onDragStart = { point ->
                        latestSelect()
                        val current = latestClip
                        val startX = inset + TimelineTime.fraction(current.inMs, durationMs) * trackWidth
                        val endX = inset + TimelineTime.fraction(current.outMs, durationMs) * trackWidth
                        val nearest = if (abs(point.x - startX) < abs(point.x - endX)) Boundary.In else Boundary.Out
                        val nearestX = if (nearest == Boundary.In) startX else endX
                        dragEdge = nearest.takeIf { abs(point.x - nearestX) <= inset }
                        dragX = nearestX - inset
                        lastSeekX = point.x
                        if (dragEdge != null) latestBegin(nearest) else latestSeek(timeAt(point.x))
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        val boundary = dragEdge
                        if (boundary != null) {
                            dragX += delta
                            latestDrag(boundary, TimelineTime.position(dragX / trackWidth, durationMs))
                        } else { lastSeekX = change.position.x; latestSeek(timeAt(lastSeekX)) }
                    },
                    onDragEnd = { if (dragEdge != null) latestFinish() else latestSeekFinished(timeAt(lastSeekX)); dragEdge = null },
                    onDragCancel = { if (dragEdge != null) latestCancel(); dragEdge = null },
                )
            }) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = seekDescription }) {
            val y = size.height / 2
            val top = y - 16.dp.toPx()
            drawRoundRect(muted, Offset(inset, top), Size(trackWidth, 32.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            val start = inset + TimelineTime.fraction(clip.inMs, durationMs) * trackWidth
            val end = inset + TimelineTime.fraction(clip.outMs, durationMs) * trackWidth
            drawRect(primary.copy(alpha = if (selected) .65f else .3f), Offset(start, top), Size((end - start).coerceAtLeast(1f), 32.dp.toPx()))
            for (i in 0..10) {
                val x = inset + trackWidth * i / 10
                drawLine(Color.White.copy(alpha = .16f), Offset(x, top), Offset(x, top + 32.dp.toPx()), 1.dp.toPx())
            }
            val playhead = inset + TimelineTime.fraction(positionMs, durationMs) * trackWidth
            drawLine(pointerColor, Offset(playhead, 4.dp.toPx()), Offset(playhead, size.height - 4.dp.toPx()), 2.dp.toPx())
        }
        Boundary.entries.forEach { boundary ->
            val value = if (boundary == Boundary.In) clip.inMs else clip.outMs
            val fraction = TimelineTime.fraction(value, durationMs)
            val description = stringResource(if (boundary == Boundary.In) R.string.in_handle else R.string.out_handle,
                TimelineTime.format(value))
            Box(Modifier.offset { IntOffset((fraction * trackWidth).roundToInt(), 0) }
                .size(48.dp).testTag("handle-${boundary.name}-${clip.id}")
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { progress ->
                        latestBegin(boundary)
                        latestDrag(boundary, TimelineTime.position(progress, durationMs))
                        latestFinish(); true
                    }
                }
                , contentAlignment = Alignment.Center) {
                Box(Modifier.width(12.dp).height(36.dp).background(primary, RoundedCornerShape(4.dp)))
                Box(Modifier.width(2.dp).height(16.dp).background(MaterialTheme.colorScheme.onPrimary))
            }
        }
    }
    }
}

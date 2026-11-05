package com.hirocut.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.hirocut.app.R
import com.hirocut.app.domain.model.TimelineTime

@Composable
fun SourceScrubber(positionMs: Long, durationMs: Long, enabled: Boolean,
    onPause: () -> Unit, onPreview: (Long) -> Unit, onSeek: (Long) -> Unit) {
    val description = stringResource(R.string.seek_source)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = .4f)
    val latestPreview by rememberUpdatedState(onPreview)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestPause by rememberUpdatedState(onPause)
    Column {
        Text(stringResource(R.string.time_pair, TimelineTime.format(positionMs), TimelineTime.format(durationMs)),
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("source-position"))
        Canvas(Modifier.fillMaxWidth().height(28.dp).testTag("source-scrubber")
            .semantics {
                contentDescription = description
                stateDescription = TimelineTime.format(positionMs)
                progressBarRangeInfo = ProgressBarRangeInfo(TimelineTime.fraction(positionMs, durationMs), 0f..1f)
                if (!enabled) disabled()
                setProgress { fraction -> if (enabled) { latestPause(); latestSeek(TimelineTime.position(fraction, durationMs)); true } else false }
            }
            .pointerInput(durationMs, enabled) {
                if (enabled) detectTapGestures { point ->
                    latestPause(); latestSeek(TimelineTime.position(point.x / size.width.coerceAtLeast(1), durationMs))
                }
            }
            .pointerInput(durationMs, enabled) {
                var x = 0f
                if (enabled) detectHorizontalDragGestures(
                    onDragStart = { latestPause(); x = it.x; latestPreview(TimelineTime.position(x / size.width.coerceAtLeast(1), durationMs)) },
                    onHorizontalDrag = { change, _ -> change.consume(); x = change.position.x; latestPreview(TimelineTime.position(x / size.width.coerceAtLeast(1), durationMs)) },
                    onDragEnd = { latestSeek(TimelineTime.position(x / size.width.coerceAtLeast(1), durationMs)) },
                    onDragCancel = { latestSeek(TimelineTime.position(x / size.width.coerceAtLeast(1), durationMs)) },
                )
            }) {
            val y = size.height / 2
            drawLine(track, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            val x = TimelineTime.fraction(positionMs, durationMs) * size.width
            drawLine(primary.copy(alpha = if (enabled) .8f else .3f), Offset(0f, y), Offset(x, y), 2.dp.toPx())
            drawCircle(primary, 3.dp.toPx(), Offset(x.coerceIn(3.dp.toPx(), (size.width - 3.dp.toPx()).coerceAtLeast(3.dp.toPx())), y))
        }
    }
}

package com.hirocut.app.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.hirocut.app.R
import com.hirocut.app.domain.model.TimelineTime
import com.hirocut.app.presentation.EditorUiState
import com.hirocut.app.presentation.EditorViewModel

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TrackEditor(state: EditorUiState, positionMs: Long, model: EditorViewModel, modifier: Modifier = Modifier, onAdd: () -> Unit = { model.addClip() }) {
    val project = state.project ?: return
    val listState = rememberLazyListState()
    var precisionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.listScrollRevision) {
        val index = project.clips.indexOfFirst { it.id == state.selectedId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyColumn(modifier.testTag("track-list"), state = listState,
        contentPadding = PaddingValues(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(project.clips, key = { _, clip -> clip.id }) { index, clip ->
            val selected = clip.id == state.selectedId
            val accent = trackAccent(index)
            Card(onClick = { model.selectClip(clip.id) },
                colors = CardDefaults.cardColors(containerColor = if (selected) accent.copy(alpha = .20f)
                    else MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, if (selected) accent else accent.copy(alpha = .35f)),
                modifier = Modifier.fillMaxWidth().testTag("clip-${clip.id}")) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${index + 1}  ·  ${TimelineTime.format(clip.durationMs)}", modifier = Modifier.weight(1f).align(Alignment.CenterVertically).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                        TextButton(onClick = { model.moveClip(clip.id, -1) }, enabled = index > 0 && !state.inGesture,
                            contentPadding = PaddingValues(3.dp), modifier = Modifier.widthIn(min = 42.dp).height(32.dp).testTag("move-up-${clip.id}")) { Text(stringResource(R.string.move_up), style = MaterialTheme.typography.labelSmall) }
                        VerticalDivider(Modifier.height(20.dp).align(Alignment.CenterVertically))
                        TextButton(onClick = { model.moveClip(clip.id, 1) }, enabled = index < project.clips.lastIndex && !state.inGesture,
                            contentPadding = PaddingValues(3.dp), modifier = Modifier.widthIn(min = 42.dp).height(32.dp).testTag("move-down-${clip.id}")) { Text(stringResource(R.string.move_down), style = MaterialTheme.typography.labelSmall) }
                        VerticalDivider(Modifier.height(20.dp).align(Alignment.CenterVertically))
                        TextButton(onClick = { model.cancelGesture(); model.selectClip(clip.id); model.pausePlayback(); precisionId = clip.id },
                            modifier = Modifier.widthIn(min = 42.dp).height(32.dp).testTag("precision-${clip.id}"), contentPadding = PaddingValues(3.dp)) { Text(stringResource(R.string.precision), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Text(stringResource(R.string.range_times, TimelineTime.format(clip.inMs), TimelineTime.format(clip.outMs)),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp).testTag("range-${clip.id}"))
                    RangeTimeline(clip, project.durationMs, positionMs, selected, accent,
                        onSelect = { model.selectClip(clip.id); model.pausePlayback() },
                        onSeek = model::previewSeek, onSeekFinished = model::seek,
                        onBegin = { model.beginRangeGesture(clip.id, it) },
                        onDrag = { edge, time -> model.dragBoundary(clip.id, edge, time) },
                        onFinish = { model.finishRangeGesture(clip.id) }, onCancel = { model.cancelGesture(clip.id) })

                }
            }
        }
        item(key = "add") {
            Card(onClick = onAdd, enabled = !state.inGesture, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().testTag("add-clip-card")) {
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_plus), stringResource(R.string.add_track), Modifier.size(22.dp))
                }
            }
        }
    }
    project.clips.firstOrNull { it.id == precisionId }?.let { clip ->
        PrecisionDialog(clip, project.durationMs, onPreview = model::previewSeek,
            onConfirm = { start, end -> model.setRange(clip.id, start, end); model.seek(start); precisionId = null },
            onDismiss = { precisionId = null })
    }
}

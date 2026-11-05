package com.hirocut.app.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hirocut.app.R
import com.hirocut.app.media.player.PlaybackState
import java.util.Locale

@Composable
fun EditorToolbar(playback: PlaybackState, enabled: Boolean, markEnabled: Boolean,
    canDelete: Boolean, canUndo: Boolean, canRedo: Boolean,
    onSkip: (Long) -> Unit, onMarkIn: () -> Unit, onMarkOut: () -> Unit, onPlay: () -> Unit,
    onSpeed: (Float) -> Unit, onDelete: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, onExport: () -> Unit) {
    var showSpeed by remember { mutableStateOf(false) }
    val speedText = String.format(Locale.ROOT, "%.2f", playback.speed).trimEnd('0').trimEnd('.')
    Surface(tonalElevation = 1.dp) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(R.drawable.ic_back5, R.string.seek_back_five, enabled, { onSkip(-5000) }, Modifier.weight(1f))
                    ToolIcon(R.drawable.ic_mark_in, R.string.mark_current_in, markEnabled, onMarkIn, Modifier.weight(1f))
                    ToolIcon(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        if (playback.isPlaying) R.string.pause else R.string.play, enabled, onPlay, Modifier.weight(1f))
                    ToolIcon(R.drawable.ic_mark_out, R.string.mark_current_out, markEnabled, onMarkOut, Modifier.weight(1f))
                    ToolIcon(R.drawable.ic_forward5, R.string.seek_forward_five, enabled, { onSkip(5000) }, Modifier.weight(1f))
                    Box(Modifier.weight(1f)) {
                        IconButton(onClick = { showSpeed = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                            Icon(painterResource(R.drawable.ic_speed), stringResource(R.string.preview_speed_current, speedText), Modifier.size(24.dp))
                        }
                        DropdownMenu(expanded = showSpeed, onDismissRequest = { showSpeed = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                            listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { rate ->
                                val text = String.format(Locale.ROOT, "%.2f", rate).trimEnd('0').trimEnd('.')
                                DropdownMenuItem(text = { Text(stringResource(R.string.speed_value, text)) },
                                    onClick = { onSpeed(rate); showSpeed = false },
                                    trailingIcon = { if (playback.speed == rate) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp)) })
                            }
                            Text(stringResource(R.string.preview_speed_only), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(R.drawable.ic_undo, R.string.undo, canUndo && enabled, onUndo)
                    ToolIcon(R.drawable.ic_redo, R.string.redo, canRedo && enabled, onRedo)
                    ToolIcon(R.drawable.ic_delete, R.string.delete_track, canDelete && enabled, onDelete)
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(onClick = onExport, enabled = enabled, modifier = Modifier.size(width = 56.dp, height = 40.dp)) {
                        Icon(painterResource(R.drawable.ic_export), stringResource(R.string.export), Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolIcon(drawable: Int, description: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.sizeIn(minWidth = 40.dp).height(42.dp)) {
        Icon(painterResource(drawable), stringResource(description), Modifier.size(22.dp))
    }
}

package com.hirocut.app.ui.editor

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hirocut.app.HiroCutApplication
import com.hirocut.app.R
import com.hirocut.app.data.files.ThumbnailRepository
import com.hirocut.app.data.settings.*
import com.hirocut.app.domain.model.TimelineTime
import com.hirocut.app.presentation.EditorUiState

@Composable
fun EditorDrawer(editor: EditorUiState, open: Boolean, onSettings: () -> Unit, onSelect: (String) -> Unit, editable: Boolean, onDelete: (String) -> Unit) {
    val context = LocalContext.current
    val thumbnails = remember { ThumbnailRepository(context.applicationContext) }
    Column(Modifier.fillMaxSize().padding(12.dp).testTag("editor-drawer")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(38.dp))
            Text(stringResource(R.string.app_name), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_gear), stringResource(R.string.settings)) }
        }
        Text(stringResource(R.string.offline_badge), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(14.dp))
        editor.project?.let { project ->
            Text(pluralStringResource(R.plurals.project_summary, project.clips.size, project.clips.size, TimelineTime.format(project.totalDurationMs)), style = MaterialTheme.typography.labelMedium)
        }
        Text(editor.source?.displayName.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 2)
        editor.source?.let { source ->
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(stringResource(R.string.source_video_info, source.videoCodec ?: "?", source.videoMime ?: "?", source.width, source.height), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.source_audio_info, source.audioCodec ?: stringResource(R.string.none), source.audioSampleRate, source.audioChannels), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.source_container_info, source.containerMime ?: stringResource(R.string.unknown), source.fileSizeBytes?.let { formatBytes(it) } ?: stringResource(R.string.unknown)), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.source_bitrate_info, formatBitrate(source.videoBitrate), if (source.frameRate > 0) String.format(java.util.Locale.ROOT, "%.2f", source.frameRate) else stringResource(R.string.unknown)), style = MaterialTheme.typography.labelSmall)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(editor.project?.clips.orEmpty(), key = { _, clip -> clip.id }) { index, clip ->
                val accent = trackAccent(index)
                val cover by produceState<android.graphics.Bitmap?>(null, open, editor.source?.uri, clip.inMs) {
                    value = if (open) editor.source?.let { thumbnails.firstFrame(it, clip.inMs) } else null
                }
                Surface(color = if (clip.id == editor.selectedId) accent.copy(alpha = .20f) else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable(enabled = editable) { onSelect(clip.id) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(42.dp).background(accent))
                        Box(Modifier.size(72.dp, 42.dp), contentAlignment = Alignment.Center) {
                            cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().border(1.dp, accent.copy(alpha = .75f)), contentScale = ContentScale.Crop) }
                        }
                        Text("${index + 1}  ·  ${TimelineTime.format(clip.durationMs)}", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = if (clip.id == editor.selectedId) accent else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { onDelete(clip.id) }, enabled = editable && (editor.project?.clips?.size ?: 0) > 1,
                            modifier = Modifier.size(40.dp).testTag("drawer-delete-${clip.id}")) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete_clip_number, index + 1), Modifier.size(20.dp), tint = accent)
                        }
                    }
                }
            }
        }
        val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
        Text(stringResource(R.string.version_info, version), style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.rights_reserved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBitrate(value: Int): String = if (value > 0) "${value / 1000} kbps" else "?"
private fun formatBytes(value: Long): String = when {
    value >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.2f GB", value / 1_000_000_000.0)
    value >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1f KB", value / 1_000.0)
    else -> "$value B"
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun EditorSettings(onDismiss: () -> Unit) {
    val store = (LocalContext.current.applicationContext as HiroCutApplication).preferences
    val prefs by store.state.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.settings)) }, text = {
        Column(Modifier.testTag("editor-settings").verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.color_theme), style = MaterialTheme.typography.titleSmall)
            ColorTheme.entries.forEach { theme ->
                Row(Modifier.fillMaxWidth().clickable { store.setTheme(theme) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(prefs.theme == theme, onClick = { store.setTheme(theme) })
                    Text(stringResource(when (theme) { ColorTheme.Forest -> R.string.theme_forest; ColorTheme.Ocean -> R.string.theme_ocean; ColorTheme.Amber -> R.string.theme_amber }))
                }
            }
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
            AppLanguage.entries.forEach { language ->
                Row(Modifier.fillMaxWidth().clickable { store.setLanguage(language) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(prefs.language == language, onClick = { store.setLanguage(language) })
                    Text(stringResource(when (language) { AppLanguage.System -> R.string.language_system; AppLanguage.Chinese -> R.string.language_chinese; AppLanguage.English -> R.string.language_english }))
                }
            }
            HorizontalDivider()
            TextButton(onClick = {}, enabled = false) {
                Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(18.dp))
                Text(stringResource(R.string.custom_background_locked), Modifier.padding(start = 8.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}

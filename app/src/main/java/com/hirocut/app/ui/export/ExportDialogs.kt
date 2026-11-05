package com.hirocut.app.ui.export

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.hirocut.app.R
import com.hirocut.app.domain.export.ExportMode
import com.hirocut.app.domain.export.ExportProblem
import com.hirocut.app.domain.model.TimelineTime
import com.hirocut.app.media.export.ExportStage
import com.hirocut.app.media.export.ExportUiState
import com.hirocut.app.presentation.EditorViewModel

@Composable
fun ExportModeDialog(onChoose: (ExportMode) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(ExportMode.Fast) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.export_options)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExportMode.entries.forEach { value ->
                FilterChip(selected = mode == value, onClick = { mode = value },
                    label = { Text(stringResource(if (value == ExportMode.Fast) R.string.mode_fast else R.string.mode_precise)) })
            }
            Text(stringResource(if (mode == ExportMode.Fast) R.string.fast_description else R.string.precise_description))
        } },
        confirmButton = { TextButton(onClick = { onChoose(mode) }, modifier = Modifier.testTag("prepare-export")) { Text(stringResource(R.string.check_export)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ExportDialogs(state: ExportUiState, model: EditorViewModel) {
    if (state.stage in listOf(ExportStage.Idle, ExportStage.Initializing)) return
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        uri?.let(model::saveExport)
    }
    val stageTitle = when (state.stage) {
        ExportStage.Preparing -> R.string.export_preparing
        ExportStage.Review -> R.string.export_review
        ExportStage.Rendering -> if (state.merging) R.string.export_merging else R.string.export_rendering
        ExportStage.Verifying -> R.string.export_verifying
        ExportStage.Ready -> R.string.export_ready
        ExportStage.Saving -> R.string.export_saving
        ExportStage.Cancelling -> R.string.export_cancelling
        ExportStage.Completed -> R.string.export_completed
        ExportStage.Cancelled -> R.string.export_cancelled
        else -> R.string.export_failed
    }
    AlertDialog(
        onDismissRequest = { if (!state.working && state.stage != ExportStage.Ready) model.dismissExport() },
        title = { Text(stringResource(stageTitle)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.working) {
                if (state.progress != null) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.export_percent, state.progress))
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.plan != null && state.stage in listOf(ExportStage.Rendering, ExportStage.Verifying, ExportStage.Ready, ExportStage.Completed)) {
                Text(stringResource(R.string.clip_input_progress), style = MaterialTheme.typography.labelMedium)
                state.plan.segments.forEachIndexed { index, segment ->
                    val percent = state.clipProgress[segment.id] ?: 0
                    val done = segment.id in state.completedClipIds
                    Text(stringResource(if (done) R.string.clip_progress_done else R.string.clip_progress_active, index + 1, percent),
                        style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (state.stage == ExportStage.Review) state.plan?.let { plan ->
                Text(stringResource(if (plan.mode == ExportMode.Fast) R.string.review_fast else R.string.review_precise))
                Text(stringResource(R.string.planned_duration, TimelineTime.format(plan.durationUs / 1000)))
                if (plan.adjusted) Text(stringResource(R.string.boundaries_adjusted), color = MaterialTheme.colorScheme.secondary)
                plan.segments.forEachIndexed { index, segment ->
                    Text(stringResource(R.string.export_range, index + 1,
                        TimelineTime.format(segment.requestedInMs), TimelineTime.format(segment.requestedOutMs),
                        TimelineTime.format(segment.inUs / 1000), TimelineTime.format(segment.outUs / 1000)),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.stage in listOf(ExportStage.Ready, ExportStage.Completed)) state.result?.let { result ->
                Text(stringResource(R.string.actual_duration, TimelineTime.format(result.durationMs)))
                Text(stringResource(if (result.videoCopied) R.string.video_copied else R.string.video_encoded))
                if (result.hasAudio) Text(stringResource(if (result.audioCopied) R.string.audio_copied else R.string.audio_encoded))
                else Text(stringResource(R.string.export_no_audio))
            }
            if (state.recovered && state.stage == ExportStage.Ready) Text(stringResource(R.string.recovered_output), style = MaterialTheme.typography.bodySmall)
            if (state.incompleteDestination) Text(stringResource(R.string.incomplete_destination), color = MaterialTheme.colorScheme.error)
            state.problem?.let { Text(stringResource(problemString(it)), color = MaterialTheme.colorScheme.error) }
            if (openFailed) Text(stringResource(R.string.open_result_failed), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = {
            when (state.stage) {
                ExportStage.Review -> TextButton(onClick = model::renderExport, modifier = Modifier.testTag("render-export")) { Text(stringResource(R.string.start_export)) }
                ExportStage.Ready -> TextButton(onClick = { savePicker.launch(context.getString(R.string.export_filename, System.currentTimeMillis())) },
                    modifier = Modifier.testTag("save-export")) { Text(stringResource(R.string.save_video)) }
                ExportStage.Completed -> TextButton(onClick = {
                    val uri = state.savedUri ?: return@TextButton
                    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: Exception) { openFailed = true }
                }) { Text(stringResource(R.string.open_video)) }
                ExportStage.Failed -> if (state.problem in listOf(ExportProblem.FastUnavailable, ExportProblem.NotStreamCopy)) {
                    TextButton(onClick = { model.dismissExport(); model.prepareExport(ExportMode.Precise) }) { Text(stringResource(R.string.try_precise)) }
                } else TextButton(onClick = model::dismissExport) { Text(stringResource(R.string.ok)) }
                else -> if (state.working && state.stage != ExportStage.Cancelling) TextButton(onClick = model::cancelExport,
                    modifier = Modifier.testTag("cancel-export")) { Text(stringResource(R.string.cancel)) }
                else if (!state.working) TextButton(onClick = model::dismissExport) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = {
            if (state.stage == ExportStage.Review) TextButton(onClick = model::dismissExport) { Text(stringResource(R.string.cancel)) }
            if (state.stage == ExportStage.Ready) TextButton(onClick = model::dismissExport) { Text(stringResource(R.string.discard_output)) }
            if (state.stage == ExportStage.Completed || (state.stage == ExportStage.Failed && state.problem in listOf(ExportProblem.FastUnavailable, ExportProblem.NotStreamCopy))) {
                TextButton(onClick = model::dismissExport) { Text(stringResource(R.string.close_export)) }
            }
        },
    )
}

private fun problemString(problem: ExportProblem) = when (problem) {
    ExportProblem.HdrUnsupported -> R.string.export_hdr_unsupported
    ExportProblem.Unsupported -> R.string.export_format_unsupported
    ExportProblem.FastUnavailable -> R.string.export_fast_unavailable
    ExportProblem.EmptyRange -> R.string.export_empty_range
    ExportProblem.InsufficientSpace -> R.string.export_no_space
    ExportProblem.SourceUnreadable -> R.string.export_source_unreadable
    ExportProblem.UnexpectedFallback -> R.string.export_fallback_rejected
    ExportProblem.NotStreamCopy -> R.string.export_not_copy
    ExportProblem.Verification -> R.string.export_verification_failed
    ExportProblem.Destination -> R.string.export_destination_failed
    ExportProblem.Interrupted -> R.string.export_interrupted
    else -> R.string.export_processing_failed
}

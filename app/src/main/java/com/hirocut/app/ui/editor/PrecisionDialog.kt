package com.hirocut.app.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hirocut.app.R
import com.hirocut.app.domain.editor.Boundary
import com.hirocut.app.domain.editor.ClipRange
import com.hirocut.app.domain.model.TimelineTime

/** A modal edit is committed once. Local zoom never changes the rows' global time scale. */
@Composable
fun PrecisionDialog(clip: ClipRange, durationMs: Long, onPreview: (Long) -> Unit,
    onConfirm: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    var startText by remember(clip.id) { mutableStateOf(TimelineTime.format(clip.inMs)) }
    var endText by remember(clip.id) { mutableStateOf(TimelineTime.format(clip.outMs)) }
    var edge by remember { mutableStateOf(Boundary.In) }
    var center by remember { mutableLongStateOf(clip.inMs) }
    val start = TimelineTime.parse(startText)
    val end = TimelineTime.parse(endText)
    val valid = start != null && end != null && start >= 0 && start < end && end <= durationMs
    val active = (if (edge == Boundary.In) start else end) ?: 0L
    val windowStart = (center - 5_000).coerceAtLeast(0)
    val windowEnd = if (durationMs - center < 5_000) durationMs else center + 5_000
    fun update(position: Long) {
        if (!valid) return
        val value = if (edge == Boundary.In) position.coerceIn(0, end!! - 1)
            else position.coerceIn(start!! + 1, durationMs)
        if (edge == Boundary.In) startText = TimelineTime.format(value) else endText = TimelineTime.format(value)
        onPreview(if (edge == Boundary.Out) value - 1 else value)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.precision_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.time_input_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = startText, onValueChange = { startText = it },
                    label = { Text(stringResource(R.string.in_point)) }, singleLine = true,
                    isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().testTag("range-start-input"))
                OutlinedTextField(value = endText, onValueChange = { endText = it },
                    label = { Text(stringResource(R.string.out_point)) }, singleLine = true,
                    isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().testTag("range-end-input"))
                if (!valid) Text(stringResource(R.string.invalid_range), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Boundary.entries.forEach { value ->
                        FilterChip(selected = edge == value, onClick = {
                            edge = value; center = (if (value == Boundary.In) start else end) ?: 0
                        }, label = { Text(stringResource(if (value == Boundary.In) R.string.adjust_in else R.string.adjust_out)) })
                    }
                }
                Text(stringResource(R.string.local_window, TimelineTime.format(windowStart), TimelineTime.format(windowEnd)),
                    style = MaterialTheme.typography.labelSmall)
                Slider(value = TimelineTime.fraction((active - windowStart).coerceAtLeast(0), windowEnd - windowStart),
                    onValueChange = { update(windowStart + TimelineTime.position(it, windowEnd - windowStart)) },
                    enabled = valid && windowEnd > windowStart, modifier = Modifier.testTag("precision-slider"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(-1000L to R.string.minus_second, -100L to R.string.minus_tenth,
                        100L to R.string.plus_tenth, 1000L to R.string.plus_second).forEach { (delta, label) ->
                        TextButton(onClick = { update((active + delta).coerceAtLeast(0)) }, enabled = valid,
                            contentPadding = PaddingValues(4.dp)) { Text(stringResource(label)) }
                    }
                }
                TextButton(onClick = { center = active.coerceIn(0, durationMs) }, enabled = valid) {
                    Text(stringResource(R.string.recenter_window))
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (valid) onConfirm(start!!, end!!) }, enabled = valid,
            modifier = Modifier.testTag("confirm-range")) { Text(stringResource(R.string.apply_range)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

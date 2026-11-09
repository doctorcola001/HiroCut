package com.hirocut.app.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hirocut.app.media.export.ExportStage
import com.hirocut.app.ui.export.ExportDialogs
import com.hirocut.app.ui.export.ExportModeDialog
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hirocut.app.R
import com.hirocut.app.domain.model.ImportFailure
import com.hirocut.app.domain.model.TimelineTime
import com.hirocut.app.media.player.PlaybackState
import com.hirocut.app.presentation.EditNotice
import com.hirocut.app.presentation.EditorUiState
import com.hirocut.app.presentation.EditorViewModel

import android.os.Build
import android.view.LayoutInflater
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.hirocut.app.HiroCutApplication
import com.hirocut.app.domain.editor.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EditorScreen(editor: EditorUiState, playback: PlaybackState, model: EditorViewModel) {
    val exportState by model.exportState.collectAsStateWithLifecycle()
    var chooseExport by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    var openingDrawer by remember { mutableStateOf(false) }
    val backgroundObscured = openingDrawer || drawer.isOpen || drawer.targetValue == DrawerValue.Open
    val blurRadiusPx = with(LocalDensity.current) { 12.dp.toPx() }
    val drawerBlurEffect = remember(blurRadiusPx) {
        if (Build.VERSION.SDK_INT >= 31) android.graphics.RenderEffect.createBlurEffect(
            blurRadiusPx, blurRadiusPx, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
        else null
    }
    var legacyBlur by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importVideo) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.onBackground() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); model.onBackground() }
    }
    fun requestImport() { if (editor.project?.hasEdits == true) confirmReplace = true else picker.launch(arrayOf("video/*")) }
    fun notify(message: Int) { scope.launch { snackbar.showSnackbar(context.getString(message)) } }
    fun add() { if (model.addClip() == AddClipResult.AtEnd) notify(R.string.add_at_end) }
    fun mark(boundary: Boundary) {
        when (model.markSelectedBoundary(boundary)) {
            MarkBoundaryResult.InvalidRange -> notify(R.string.invalid_mark)
            MarkBoundaryResult.FrameUnavailable -> notify(R.string.frame_unavailable)
            else -> Unit
        }
    }
    val source = editor.source
    val enabled = source != null && !editor.loading && !editor.inGesture && !exportState.locked && !playback.failed
    val duration = source?.durationMs ?: 0L
    val playDescription = stringResource(if (playback.isPlaying) R.string.pause else R.string.play)
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(Modifier.fillMaxWidth(.7f).testTag("drawer-sheet")) {
            EditorDrawer(editor, drawer.isOpen, onSettings = { settings = true }, onSelect = { id -> model.focusClip(id); scope.launch { drawer.close() } },
                editable = !exportState.locked && !editor.loading && !editor.inGesture, onDelete = model::deleteClip)
        }
    }) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(modifier = Modifier
            .testTag(if (backgroundObscured) "editor-background-blurred" else "editor-background-clear")
            .graphicsLayer { renderEffect = if (backgroundObscured) drawerBlurEffect else null },
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Row(Modifier.statusBarsPadding().fillMaxWidth().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (!openingDrawer) {
                            model.pausePlayback()
                            openingDrawer = true
                            scope.launch {
                                try {
                                    if (Build.VERSION.SDK_INT < 31) {
                                        legacyBlur = null
                                        val snapshot = (context as? android.app.Activity)?.let { captureWindowSnapshot(it) }
                                        if (snapshot != null) launch {
                                            legacyBlur = withContext(Dispatchers.Default) { gaussianBlur(snapshot) }
                                        }
                                    }
                                    drawer.open()
                                } finally { openingDrawer = false }
                            }
                        }
                    }, enabled = !exportState.locked) { Icon(painterResource(R.drawable.ic_chevron), stringResource(R.string.open_sidebar)) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        editor.project?.let { Text(pluralStringResource(R.plurals.project_summary, it.clips.size, it.clips.size, TimelineTime.format(it.totalDurationMs)), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                    IconButton(onClick = ::requestImport, enabled = !editor.loading && !exportState.locked) { Icon(painterResource(R.drawable.ic_import), stringResource(if (source == null) R.string.import_video else R.string.replace_video)) }
                }
            },
            bottomBar = {
                EditorToolbar(playback, enabled, enabled && playback.framePositionMs >= 0,
                    (editor.project?.clips?.size ?: 0) > 1, editor.canUndo, editor.canRedo,
                    model::skipPreview, { mark(Boundary.In) }, { mark(Boundary.Out) }, model::togglePlayback,
                    model::setPreviewSpeed, model::deleteSelected, model::undo, model::redo, { chooseExport = true })
            }
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val previewHeight = (maxHeight * .30f).coerceIn(100.dp, 210.dp)
                Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color.Black, modifier = Modifier.fillMaxWidth().height(previewHeight).testTag("video-preview")) {
                        Box(contentAlignment = Alignment.Center) {
                            if (source != null) {
                                AndroidView(factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.preview_player, null) as PlayerView).apply { player = model.playback.player } },
                                    update = { it.player = model.playback.player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().testTag("preview-toggle").semantics { contentDescription = playDescription }.clickable(enabled = enabled, onClickLabel = playDescription) { model.togglePlayback() }, contentAlignment = Alignment.Center) {
                                    if (!playback.isPlaying && !playback.isBuffering) Icon(painterResource(R.drawable.ic_play), null, Modifier.size(36.dp), tint = Color.White)
                                }
                            } else Text(stringResource(R.string.preview_title), color = Color.White)
                            if (editor.loading || playback.isBuffering) CircularProgressIndicator()
                        }
                    }
                    SourceScrubber(playback.positionMs, duration, enabled, model::pausePlayback, model::previewSeek, model::seek)
                    if (playback.failed) Text(stringResource(R.string.error_playback), color = MaterialTheme.colorScheme.error)
                    if (source?.hasPersistentAccess == false) Text(stringResource(R.string.session_only), style = MaterialTheme.typography.labelSmall)
                    if (editor.draftDiscarded || editor.draftSaveFailed) Text(stringResource(if (editor.draftSaveFailed) R.string.draft_save_failed else R.string.draft_discarded), style = MaterialTheme.typography.labelSmall)
                    if (editor.project != null) {
                        Box(Modifier.weight(1f)) {
                            TrackEditor(editor, playback.positionMs, model, Modifier.fillMaxSize(), ::add)
                            if (exportState.locked) Box(Modifier.fillMaxSize().clickable { })
                        }
                    } else {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.welcome_body))
                            Button(onClick = ::requestImport, enabled = !editor.loading) { Text(stringResource(R.string.choose_video)) }
                        }
                    }
                }
            }
        }
        if (backgroundObscured && Build.VERSION.SDK_INT < 31) legacyBlur?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds) }
        }
    }
    if (settings) EditorSettings(onDismiss = { settings = false })
    if (editor.restoreChoicePending) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.restore_choice_title)) },
        text = { Text(stringResource(R.string.restore_choice_body)) },
        confirmButton = { TextButton(onClick = model::continueLastProject) { Text(stringResource(R.string.continue_last_project)) } },
        dismissButton = { TextButton(onClick = model::startNewProject) { Text(stringResource(R.string.start_new_project)) } },
    )
    if (chooseExport) ExportModeDialog(
        onChoose = { mode -> chooseExport = false; model.prepareExport(mode) },
        onDismiss = { chooseExport = false },
    )
    ExportDialogs(exportState, model)
    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false },
        title = { Text(stringResource(R.string.replace_title)) },
        text = { Text(stringResource(R.string.replace_body)) },
        confirmButton = { TextButton(onClick = { confirmReplace = false; picker.launch(arrayOf("video/*")) }) {
            Text(stringResource(R.string.replace_video))
        } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (editor.error != null) AlertDialog(
        onDismissRequest = model::dismissError,
        title = { Text(stringResource(if (editor.restoreFailed) R.string.restore_failed else R.string.import_failed)) },
        text = { Text(stringResource(when (editor.error) {
            ImportFailure.NoVideo -> R.string.error_no_video
            ImportFailure.InvalidDuration -> R.string.error_duration
            ImportFailure.NotSeekable -> R.string.error_not_seekable
            else -> R.string.error_unreadable
        })) },
        confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.ok)) } },
    )
}

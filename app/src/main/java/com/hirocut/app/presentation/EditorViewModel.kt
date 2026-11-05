package com.hirocut.app.presentation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.hirocut.app.HiroCutApplication
import com.hirocut.app.domain.export.ExportMode
import com.hirocut.app.media.export.ExportStage
import com.hirocut.app.data.files.SourceRepository
import com.hirocut.app.data.project.DraftRepository
import com.hirocut.app.data.project.DraftSnapshot
import com.hirocut.app.domain.editor.*
import com.hirocut.app.domain.model.ImportFailure
import com.hirocut.app.domain.model.SourceImportException
import com.hirocut.app.domain.model.SourceVideo
import com.hirocut.app.media.player.PlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class EditNotice { Ready, Added, Deleted, Moved, RangeChanged, Undone, Redone }

data class EditorUiState(
    val source: SourceVideo? = null,
    val loading: Boolean = false,
    val error: ImportFailure? = null,
    val restoreFailed: Boolean = false,
    val project: EditProject? = null,
    val selectedId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val inGesture: Boolean = false,
    val listScrollRevision: Long = 0,
    val draftDiscarded: Boolean = false,
    val draftSaveFailed: Boolean = false,
    val notice: EditNotice = EditNotice.Ready,
)

@UnstableApi
class EditorViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val exports = (application as HiroCutApplication).exports
    val exportState get() = exports.state
    private val repository = SourceRepository(application)
    private val drafts = DraftRepository(application)
    private val writes = Channel<DraftSnapshot>(Channel.CONFLATED)
    private var history: EditHistory? = null
    private var lastPreviewSeekNs = 0L
    private var gestureOwnerId: String? = null
    private var draggedBoundary: Boundary = Boundary.In
    val playback = PlayerController(application, viewModelScope)
    private val mutableState = MutableStateFlow(EditorUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            for (snapshot in writes) {
                val success = try { drafts.save(snapshot) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { false }
                if (mutableState.value.source?.uri == snapshot.source.uri) {
                    mutableState.value = mutableState.value.copy(draftSaveFailed = !success)
                }
            }
        }
        repository.savedUri()?.let { importVideo(it, restoring = true) }
    }

    fun importVideo(uri: Uri, restoring: Boolean = false) {
        if (mutableState.value.loading || (!restoring && exports.state.value.locked)) return
        cancelGesture()
        val restoreDraft = restoring || mutableState.value.source == null
        playback.pause()
        mutableState.value = mutableState.value.copy(loading = true, error = null, restoreFailed = false)
        viewModelScope.launch {
            try {
                val source = repository.inspect(uri)
                val restored = if (restoreDraft) drafts.load(source) else null
                val project = restored?.project ?: EditorRules.create(source.durationMs, newId())
                history = EditHistory(project)
                repository.remember(source)
                val position = if (restoring) savedState.get<Long>("positionMs") ?: 0 else 0
                playback.load(source.uri, position.coerceIn(0, source.durationMs), source.durationMs)
                savedState["positionMs"] = position
                mutableState.value = EditorUiState(source = source, project = project,
                    selectedId = restored?.selectedId ?: project.clips.first().id,
                    draftDiscarded = restored?.discarded == true)
                saveDraft()
            } catch (error: CancellationException) { throw error }
            catch (error: SourceImportException) {
                if (restoring) repository.forget()
                mutableState.value = mutableState.value.copy(loading = false, error = error.reason, restoreFailed = restoring)
            }
        }
    }

    fun selectClip(id: String) {
        if (exports.state.value.locked) return
        if (mutableState.value.project?.clips?.none { it.id == id } != false) return
        mutableState.value = mutableState.value.copy(selectedId = id)
        if (history?.inGesture == false) saveDraft()
    }
    fun addClip(): AddClipResult {
        val h = editableHistory() ?: return AddClipResult.Unavailable
        val pointer = playback.state.value.positionMs.coerceIn(0, h.current.durationMs)
        if (pointer >= h.current.durationMs) return AddClipResult.AtEnd
        val id = newId()
        h.update(EditorRules.add(h.current, id, pointer))
        mutableState.value = mutableState.value.copy(selectedId = id)
        sync(EditNotice.Added)
        return AddClipResult.Added
    }
    fun focusClip(id: String) {
        selectClip(id)
        if (!exports.state.value.locked) mutableState.value = mutableState.value.copy(listScrollRevision = mutableState.value.listScrollRevision + 1)
    }
    fun skipPreview(deltaMs: Long) {
        if (exports.state.value.locked) return
        val duration = mutableState.value.source?.durationMs ?: return
        playback.seek((playback.state.value.positionMs + deltaMs).coerceIn(0, duration))
    }
    fun setPreviewSpeed(speed: Float) { if (!exports.state.value.locked) playback.setSpeed(speed) }
    fun markSelectedBoundary(boundary: Boundary): MarkBoundaryResult {
        val h = editableHistory() ?: return MarkBoundaryResult.Unavailable
        val clip = h.current.clips.firstOrNull { it.id == mutableState.value.selectedId } ?: return MarkBoundaryResult.Unavailable
        playback.pause()
        val frame = playback.currentFrameMs()?.coerceIn(0, h.current.durationMs) ?: return MarkBoundaryResult.FrameUnavailable
        val start = if (boundary == Boundary.In) frame else clip.inMs
        val end = if (boundary == Boundary.Out) frame else clip.outMs
        if (start >= end) return MarkBoundaryResult.InvalidRange
        h.update(EditorRules.range(h.current, clip.id, start, end))
        sync(EditNotice.RangeChanged)
        return MarkBoundaryResult.Applied
    }
    fun deleteSelected() { mutableState.value.selectedId?.let(::deleteClip) }
    fun deleteClip(id: String) {
        val h = editableHistory() ?: return
        if (h.current.clips.size == 1) return
        val index = h.current.clips.indexOfFirst { it.id == id }
        if (index < 0) return
        h.update(EditorRules.remove(h.current, id))
        if (mutableState.value.selectedId == id) {
            mutableState.value = mutableState.value.copy(selectedId = h.current.clips[index.coerceAtMost(h.current.clips.lastIndex)].id)
        }
        sync(EditNotice.Deleted)
    }
    fun moveClip(id: String, delta: Int) {
        val h = editableHistory() ?: return
        h.update(EditorRules.move(h.current, id, delta))
        mutableState.value = mutableState.value.copy(selectedId = id)
        sync(EditNotice.Moved)
    }
    fun setRange(id: String, start: Long, end: Long) {
        val h = editableHistory() ?: return
        if (start < 0 || start >= end || end > h.current.durationMs) return
        h.update(EditorRules.range(h.current, id, start, end))
        sync(EditNotice.RangeChanged)
    }
    fun beginRangeGesture(id: String, boundary: Boundary) {
        val h = history ?: return
        if (mutableState.value.loading || h.inGesture || exports.state.value.locked) return
        selectClip(id)
        gestureOwnerId = id
        draggedBoundary = boundary
        playback.pause()
        h.beginGesture()
        sync(save = false)
    }
    fun dragBoundary(id: String, boundary: Boundary, position: Long) {
        val h = history ?: return
        if (!h.inGesture || id != gestureOwnerId || boundary != draggedBoundary) return
        val clip = h.current.clips.firstOrNull { it.id == id } ?: return
        val start = if (boundary == Boundary.In) position.coerceIn(0, clip.outMs - 1) else clip.inMs
        val end = if (boundary == Boundary.Out) position.coerceIn(clip.inMs + 1, h.current.durationMs) else clip.outMs
        h.update(EditorRules.range(h.current, id, start, end))
        sync(save = false)
        previewSeek(if (boundary == Boundary.In) start else (end - 1).coerceAtLeast(start))
    }
    fun finishRangeGesture(id: String? = null) {
        val h = history ?: return
        if (!h.inGesture || (id != null && id != gestureOwnerId)) return
        h.finishGesture()
        gestureOwnerId = null
        sync(EditNotice.RangeChanged)
        val clip = h.current.clips.firstOrNull { it.id == mutableState.value.selectedId } ?: return
        seek(if (draggedBoundary == Boundary.In) clip.inMs else clip.outMs - 1)
    }
    fun cancelGesture(id: String? = null) {
        val h = history ?: return
        if (!h.inGesture || (id != null && id != gestureOwnerId)) return
        h.cancelGesture()
        gestureOwnerId = null
        sync(save = false)
    }
    fun undo() { editableHistory()?.let { it.undo(); sync(EditNotice.Undone) } }
    fun redo() { editableHistory()?.let { it.redo(); sync(EditNotice.Redone) } }
    fun prepareExport(mode: ExportMode) {
        cancelGesture()
        val state = mutableState.value
        if (state.loading || exports.state.value.stage != ExportStage.Idle) return
        val source = state.source ?: return
        val project = state.project ?: return
        playback.pause()
        exports.prepare(source, project, mode)
    }
    fun renderExport() = exports.render()
    fun saveExport(uri: Uri) = exports.save(uri)
    fun cancelExport() = exports.cancel()
    fun dismissExport() = exports.dismiss()
    fun pausePlayback() = playback.pause()
    fun togglePlayback() { if (!exports.state.value.locked) playback.toggle() }
    fun seek(positionMs: Long) { if (!exports.state.value.locked) playback.seek(positionMs) }
    fun previewSeek(positionMs: Long) {
        if (exports.state.value.locked) return
        playback.pause()
        val now = System.nanoTime()
        val issueSeek = now - lastPreviewSeekNs >= 33_000_000L
        if (issueSeek) lastPreviewSeekNs = now
        playback.previewSeek(positionMs, issueSeek)
    }
    fun onBackground() {
        cancelGesture()
        savedState["positionMs"] = playback.state.value.positionMs
        playback.pause()
    }
    fun dismissError() { mutableState.value = mutableState.value.copy(error = null, restoreFailed = false) }
    private fun editableHistory(): EditHistory? {
        if (mutableState.value.loading || exports.state.value.locked) return null
        cancelGesture()
        return history
    }
    private fun sync(notice: EditNotice = mutableState.value.notice, save: Boolean = true) {
        val h = history ?: return
        val selected = mutableState.value.selectedId.takeIf { id -> h.current.clips.any { it.id == id } } ?: h.current.clips.first().id
        val structureChanged = mutableState.value.project?.clips?.map { it.id } != h.current.clips.map { it.id }
        mutableState.value = mutableState.value.copy(project = h.current, selectedId = selected,
            listScrollRevision = mutableState.value.listScrollRevision + if (structureChanged) 1 else 0,
            canUndo = h.canUndo, canRedo = h.canRedo, inGesture = h.inGesture, notice = notice)
        if (save) saveDraft()
    }
    private fun saveDraft() {
        val state = mutableState.value
        val source = state.source ?: return
        val project = state.project ?: return
        if (!state.inGesture) writes.trySend(DraftSnapshot(source, project, state.selectedId ?: project.clips.first().id))
    }
    private fun newId() = UUID.randomUUID().toString()
    override fun onCleared() { writes.close(); playback.release(); super.onCleared() }
}

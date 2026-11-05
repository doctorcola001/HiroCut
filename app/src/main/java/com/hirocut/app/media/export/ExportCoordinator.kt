package com.hirocut.app.media.export

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.media3.common.util.UnstableApi
import com.hirocut.app.domain.editor.EditProject
import com.hirocut.app.domain.export.*
import com.hirocut.app.domain.model.SourceVideo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ExportStage { Initializing, Idle, Preparing, Review, Rendering, Verifying, Ready, Saving, Cancelling, Completed, Cancelled, Failed }
data class ExportUiState(val stage: ExportStage = ExportStage.Initializing, val progress: Int? = null,
    val plan: ExportPlan? = null, val result: RenderedVideo? = null, val problem: ExportProblem? = null,
    val savedUri: String? = null, val recovered: Boolean = false, val incompleteDestination: Boolean = false,
    val interruptedJob: Boolean = false,
    val clipProgress: Map<String, Int> = emptyMap(), val completedClipIds: Set<String> = emptySet(), val merging: Boolean = false) {
    val locked get() = stage !in listOf(ExportStage.Idle, ExportStage.Initializing, ExportStage.Completed, ExportStage.Cancelled, ExportStage.Failed)
    val working get() = stage in listOf(ExportStage.Preparing, ExportStage.Rendering, ExportStage.Verifying, ExportStage.Saving, ExportStage.Cancelling)
}

/** Process-wide foreground coordinator. No Activity, service, or background-completion promise. */
@UnstableApi
class ExportCoordinator(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ExportUiState())
    val state = mutableState.asStateFlow()
    private val root = File(context.filesDir, "export-jobs")
    private val writer = DestinationWriter(context)
    private var active: Job? = null
    private var directory: File? = null
    private val startup = scope.launch {
        val recovered = withContext(Dispatchers.IO) { recoverAndClean() }
        mutableState.value = recovered
    }

    fun prepare(source: SourceVideo, project: EditProject, mode: ExportMode) {
        if (mutableState.value.stage != ExportStage.Idle) return
        mutableState.value = ExportUiState(stage = ExportStage.Preparing)
        active = scope.launch {
            try {
                startup.join()
                val spec = SourceProbe(context).inspect(source, mode)
                val plan = ExportPlanner.build(source, project.copy(clips = project.clips.toList()), mode, spec)
                mutableState.value = ExportUiState(stage = ExportStage.Review, plan = plan)
            } catch (cancel: CancellationException) { mutableState.value = ExportUiState(stage = ExportStage.Cancelled); throw cancel }
            catch (known: ExportFailure) { fail(known.problem) }
            catch (_: Exception) { fail(ExportProblem.Processing) }
        }
    }

    fun render() {
        val plan = mutableState.value.plan ?: return
        if (mutableState.value.stage != ExportStage.Review) return
        mutableState.value = mutableState.value.copy(stage = ExportStage.Rendering, progress = null,
            clipProgress = plan.segments.associate { it.id to 0 }, completedClipIds = emptySet(), merging = false)
        active = scope.launch {
            var success = false
            var createdDir: File? = null
            try {
                val jobDir = withContext(Dispatchers.IO) {
                    root.mkdirs()
                    val estimated = ((plan.source.fileSizeBytes ?: 64L * 1024 * 1024).toDouble() *
                        (plan.durationUs.toDouble() / (plan.source.durationMs * 1000.0)) * if (plan.mode == ExportMode.Precise) 2.0 else 1.1)
                        .coerceIn(0.0, Long.MAX_VALUE.toDouble()).toLong()
                    if (root.usableSpace - 16L * 1024 * 1024 < maxOf(32L * 1024 * 1024, estimated)) throw ExportFailure(ExportProblem.InsufficientSpace)
                    File(root, UUID.randomUUID().toString()).also { check(it.mkdir()) }
                }
                createdDir = jobDir
                directory = jobDir
                writeJournal(jobDir, "rendering")
                val result = Media3ExportEngine(context).render(plan, File(jobDir, "output.mp4"),
                    onProgress = { mutableState.value = mutableState.value.copy(progress = it) },
                    onVerifying = { mutableState.value = mutableState.value.copy(stage = ExportStage.Verifying, progress = null,
                        clipProgress = plan.segments.associate { it.id to 100 }, completedClipIds = plan.segments.map { it.id }.toSet(), merging = true) },
                    onClipProgress = { id, percent, done ->
                        if (mutableState.value.stage == ExportStage.Rendering) {
                            val state = mutableState.value
                            val complete = if (done) state.completedClipIds + id else state.completedClipIds
                            mutableState.value = state.copy(clipProgress = state.clipProgress + (id to percent),
                                completedClipIds = complete, merging = complete.size == plan.segments.size)
                        }
                    })
                writeJournal(jobDir, "ready", result)
                mutableState.value = mutableState.value.copy(stage = ExportStage.Ready, result = result, progress = null)
                success = true
            } catch (cancel: CancellationException) { mutableState.value = ExportUiState(stage = ExportStage.Cancelled); throw cancel }
            catch (known: ExportFailure) { fail(known.problem) }
            catch (_: Exception) { fail(ExportProblem.Processing) }
            finally {
                if (!success) {
                    val stale = createdDir
                    withContext(NonCancellable + Dispatchers.IO) { stale?.deleteRecursively() }
                    if (directory == stale) directory = null
                }
            }
        }
    }

    fun save(uri: Uri) {
        val before = mutableState.value
        val result = before.result ?: return
        val dir = directory ?: return
        if (before.stage != ExportStage.Ready) return
        if (uri.scheme != "content" || uri.toString() == before.plan?.source?.uri) {
            mutableState.value = before.copy(problem = ExportProblem.Destination); return
        }
        mutableState.value = before.copy(stage = ExportStage.Saving, progress = 0, problem = null)
        active = scope.launch {
            var acceptedEmptyTarget = false
            try {
                writer.copy(result.file, uri,
                    onAccepted = { acceptedEmptyTarget = true; writeJournal(dir, "saving", result) },
                    onProgress = { mutableState.value = mutableState.value.copy(progress = it) })
                writeJournal(dir, "completed", result)
                withContext(Dispatchers.IO) { result.file.delete() }
                mutableState.value = mutableState.value.copy(stage = ExportStage.Completed, savedUri = uri.toString(), progress = null)
            } catch (cancel: CancellationException) {
                val deleted = if (acceptedEmptyTarget) withContext(NonCancellable) { writer.deleteIncomplete(uri) } else false
                withContext(NonCancellable) { writeJournal(dir, "ready", result) }
                mutableState.value = before.copy(problem = ExportProblem.Destination, incompleteDestination = acceptedEmptyTarget && !deleted)
                throw cancel
            } catch (_: Exception) {
                val deleted = if (acceptedEmptyTarget) writer.deleteIncomplete(uri) else false
                runCatching { writeJournal(dir, "ready", result) }
                mutableState.value = before.copy(problem = ExportProblem.Destination, incompleteDestination = acceptedEmptyTarget && !deleted)
            }
        }
    }

    fun cancel() {
        if (!mutableState.value.working || mutableState.value.stage == ExportStage.Cancelling) return
        mutableState.value = mutableState.value.copy(stage = ExportStage.Cancelling)
        active?.cancel()
    }
    fun dismiss() {
        if (mutableState.value.working) return
        val old = directory
        if (mutableState.value.stage == ExportStage.Ready && old != null) {
            mutableState.value = mutableState.value.copy(stage = ExportStage.Cancelling)
            active = scope.launch {
                withContext(Dispatchers.IO) { old.deleteRecursively() }
                if (directory == old) directory = null
                mutableState.value = ExportUiState(stage = ExportStage.Idle)
            }
        } else {
            directory = null
            mutableState.value = ExportUiState(stage = ExportStage.Idle)
            scope.launch(Dispatchers.IO) { old?.deleteRecursively() }
        }
    }
    private fun fail(problem: ExportProblem) { mutableState.value = mutableState.value.copy(stage = ExportStage.Failed, problem = problem, progress = null) }

    private suspend fun writeJournal(dir: File, stage: String, result: RenderedVideo? = null) = withContext(Dispatchers.IO) {
        val data = JSONObject().put("stage", stage)
        result?.let { data.put("duration", it.durationMs).put("width", it.width).put("height", it.height)
            .put("hasAudio", it.hasAudio).put("videoCopied", it.videoCopied).put("audioCopied", it.audioCopied).put("mode", it.mode.name).put("size", it.file.length()) }
        val file = AtomicFile(File(dir, "journal.json"))
        val stream = file.startWrite()
        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun recoverAndClean(): ExportUiState {
        root.mkdirs()
        var restored: RenderedVideo? = null
        var interrupted = false
        root.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { dir ->
            if (!dir.isDirectory || !dir.name.matches(Regex("[0-9a-f-]{36}"))) return@forEach
            try {
                val data = JSONObject(AtomicFile(File(dir, "journal.json")).openRead().bufferedReader().use { it.readText() })
                val phase = data.getString("stage")
                val file = File(dir, "output.mp4")
                if (restored == null && phase in listOf("ready", "saving") && file.isFile && file.length() == data.getLong("size") && file.length() > 0) {
                    restored = RenderedVideo(file, data.getLong("duration"), data.getInt("width"), data.getInt("height"),
                        data.getBoolean("videoCopied"), data.getBoolean("audioCopied"), ExportMode.valueOf(data.getString("mode")), data.optBoolean("hasAudio"))
                    directory = dir
                    if (phase == "saving") interrupted = true
                } else { if (phase != "completed") interrupted = true; dir.deleteRecursively() }
            } catch (_: Exception) { interrupted = true; dir.deleteRecursively() }
        }
        return if (restored != null) ExportUiState(stage = ExportStage.Ready, result = restored, recovered = true,
            incompleteDestination = interrupted, interruptedJob = interrupted)
        else ExportUiState(stage = ExportStage.Idle, interruptedJob = interrupted)
    }
}

package com.hirocut.app

import android.content.ContentValues
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hirocut.app.data.files.SourceRepository
import com.hirocut.app.domain.editor.*
import com.hirocut.app.domain.export.*
import com.hirocut.app.domain.model.SourceVideo
import com.hirocut.app.media.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@UnstableApi
class M4ExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val coordinator get() = (context.applicationContext as HiroCutApplication).exports
    private val validationDir get() = File(context.filesDir, "m4-validation").also { it.mkdirs() }

    private fun insertVideo(asset: String?): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "HiroCut-M4-test-${System.nanoTime()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HiroCut-tests")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        if (asset != null) context.contentResolver.openOutputStream(uri)!!.use { output -> instrumentation.context.assets.open(asset).use { it.copyTo(output) } }
        return uri
    }
    private suspend fun withSource(asset: String = "m4-colors.mp4", block: suspend (SourceVideo) -> Unit) {
        val uri = insertVideo(asset)
        try { block(SourceRepository(context).inspect(uri)) } finally { context.contentResolver.delete(uri, null, null) }
    }
    private fun project(source: SourceVideo, precise: Boolean = false) = EditProject(source.durationMs,
        if (precise) listOf(ClipRange("c", 4125, 4875), ClipRange("a", 125, 875), ClipRange("again", 125, 875))
        else listOf(ClipRange("c", 4000, 5000), ClipRange("a", 0, 1000), ClipRange("again", 0, 1000)))
    private suspend fun render(source: SourceVideo, project: EditProject, mode: ExportMode, name: String): Pair<ExportPlan, RenderedVideo> {
        val spec = SourceProbe(context).inspect(source, mode)
        val plan = ExportPlanner.build(source, project, mode, spec)
        val file = File(validationDir, name).also { it.delete() }
        val progress = mutableMapOf<String, Int>()
        val done = mutableSetOf<String>()
        val result = withContext(Dispatchers.Main) { withTimeout(60_000) { Media3ExportEngine(context).render(plan, file, {}, onClipProgress = { id, percent, complete ->
            assertTrue(id in plan.segments.map { it.id })
            assertTrue(percent in 0..100)
            assertTrue(percent >= (progress[id] ?: 0))
            progress[id] = percent
            if (complete) { assertEquals(100, percent); done.add(id) }
        }) } }
        assertEquals("Each source clip must report real EOS", plan.segments.map { it.id }.toSet(), done)
        return plan to result
    }
    private fun assertColors(file: File, timesMs: List<Long>) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            timesMs.forEachIndexed { i, time ->
                val frame = requireNotNull(retriever.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 64, 36))
                try {
                    val color = frame.getPixel(32, 18)
                    if (i == 0) assertTrue("Expected blue first", Color.blue(color) > 180 && Color.red(color) < 70)
                    else assertTrue("Expected repeated red after blue", Color.red(color) > 180 && Color.blue(color) < 70)
                } finally { frame.recycle() }
            }
        } finally { retriever.release() }
    }
    private fun videoSamples(uri: Uri? = null, file: File? = null): List<Pair<Long, String>> {
        val extractor = MediaExtractor()
        try {
            if (uri != null) extractor.setDataSource(context, uri, null) else extractor.setDataSource(file!!.absolutePath)
            val index = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(index)
            val samples = mutableListOf<Pair<Long, String>>()
            while (extractor.sampleTime >= 0) {
                val buffer = ByteBuffer.allocate(extractor.sampleSize.toInt())
                val count = extractor.readSampleData(buffer, 0)
                val hash = MessageDigest.getInstance("SHA-256").digest(buffer.array().copyOf(count)).joinToString("") { "%02x".format(it) }
                samples.add(extractor.sampleTime to hash)
                if (!extractor.advance()) break
            }
            return samples
        } finally { extractor.release() }
    }
    private fun assertVideoPayloadsCopied(source: SourceVideo, plan: ExportPlan, output: File) {
        val input = videoSamples(uri = Uri.parse(source.uri))
        val expected = plan.segments.flatMap { segment -> input.filter { it.first >= segment.inUs && it.first < segment.outUs }.map { it.second } }
        assertEquals(expected, videoSamples(file = output).map { it.second })
    }

    @Test fun fastCopiesPayloadsAndExportsBlueRedRedWithAudio() = runBlocking {
        withSource { source ->
            val (plan, result) = render(source, project(source), ExportMode.Fast, "fast-ordered.mp4")
            assertTrue(result.videoCopied); assertTrue(result.audioCopied); assertTrue(result.hasAudio)
            assertTrue(result.durationMs in 2950..3050)
            assertColors(result.file, listOf(500, 1500, 2500))
            assertVideoPayloadsCopied(source, plan, result.file)
        }
    }
    @Test fun preciseExportsNonKeyframeBlueRedRed() = runBlocking {
        withSource { source ->
            val (_, result) = render(source, project(source, precise = true), ExportMode.Precise, "precise-ordered.mp4")
            assertFalse(result.videoCopied)
            assertTrackTiming(result, 2_250_000)
            assertFrameBoundaries(result.file)
            assertColors(result.file, listOf(300, 1050, 1800))
        }
    }
    private fun assertTrackTiming(result: RenderedVideo, expectedVideoUs: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(result.file.absolutePath)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val videoDurationUs = format.getLong(MediaFormat.KEY_DURATION)
            assertTrue("Video duration $videoDurationUs expected $expectedVideoUs", kotlin.math.abs(videoDurationUs - expectedVideoUs) <= 33_334)
            assertTrue("Container ${result.durationMs}ms versus video $videoDurationUs us",
                kotlin.math.abs(result.durationMs * 1000 - videoDurationUs) <= 50_000)
        } finally { extractor.release() }
    }
    private fun assertFrameBoundaries(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            for (segment in 0..2) {
                val prefixUs = segment * 750_000L
                val startIndex = if (segment == 0) 123 else 3
                val endIndex = if (segment == 0) 146 else 26
                fun readCounter(timeUs: Long): Int {
                    val frame = requireNotNull(retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST))
                    try {
                        var value = 0
                        for (bit in 0..7) {
                            val color = frame.getPixel(40 + 80 * bit, 20)
                            if (Color.red(color) + Color.green(color) + Color.blue(color) > 3 * 128) value = value or (1 shl bit)
                        }
                        return value
                    } finally { frame.recycle() }
                }
                val first = readCounter(prefixUs + if (segment == 0) 0 else 10_000)
                val last = readCounter(prefixUs + 730_000)
                assertTrue("Segment $segment first frame $first", first in startIndex..(startIndex + 1))
                assertTrue("Segment $segment last frame $last", last in (endIndex - 1)..(endIndex + 1))
            }
        } finally { retriever.release() }
    }
    @Test fun thirtyPreciseClipsDoNotAccumulateAudioTailDrift() = runBlocking {
        withSource { source ->
            val repeated = EditProject(source.durationMs, (0 until 30).map { ClipRange("part-$it", 125, 875) })
            val (_, result) = render(source, repeated, ExportMode.Precise, "precise-thirty-clips.mp4")
            assertTrackTiming(result, 22_500_000)
        }
    }

    @Test fun silentRotatedSourceExportsWithoutInventingAudio() = runBlocking {
        withSource("m1-silent-rotated.mp4") { source ->
            val (_, result) = render(source, EditProject(source.durationMs, listOf(ClipRange("a", 125, 2125))), ExportMode.Precise, "silent-rotated.mp4")
            assertFalse(result.hasAudio)
            assertTrue(result.durationMs in 1950..2050)
        }
    }
    @Test fun fastSilentRotatedSourceHasNoAudioAndPreservesVideoBytes() = runBlocking {
        withSource("m1-silent-rotated.mp4") { source ->
            val (plan, result) = render(source, project(source), ExportMode.Fast, "fast-silent-rotated.mp4")
            assertFalse(result.hasAudio)
            assertTrue(result.videoCopied)
            assertVideoPayloadsCopied(source, plan, result.file)
        }
    }

    @Test fun fastBFrameSourceAlsoPreservesVideoSampleBytes() = runBlocking {
        withSource("m1-test.mp4") { source ->
            val (plan, result) = render(source, project(source), ExportMode.Fast, "fast-bframes.mp4")
            assertTrue(result.videoCopied)
            assertVideoPayloadsCopied(source, plan, result.file)
        }
    }

    private suspend fun awaitStage(vararg stages: ExportStage): ExportUiState = withTimeout(65_000) { coordinator.state.first { it.stage in stages } }
    private suspend fun prepareAndRender(source: SourceVideo): RenderedVideo {
        awaitStage(ExportStage.Idle)
        withContext(Dispatchers.Main) { coordinator.prepare(source, project(source), ExportMode.Fast) }
        val review = awaitStage(ExportStage.Review, ExportStage.Failed)
        assertEquals("Preflight failed: ${review.problem}", ExportStage.Review, review.stage)
        withContext(Dispatchers.Main) { coordinator.render() }
        val ready = awaitStage(ExportStage.Ready, ExportStage.Failed)
        assertEquals("Render failed: ${ready.problem}", ExportStage.Ready, ready.stage)
        return requireNotNull(ready.result)
    }
    @After fun resetCoordinator(): Unit = runBlocking {
        withContext(Dispatchers.Main) { if (coordinator.state.value.working) coordinator.cancel() }
        withTimeout(10_000) { coordinator.state.first { !it.working } }
        withContext(Dispatchers.Main) { coordinator.dismiss() }
        awaitStage(ExportStage.Idle)
    }
    @Test fun failedSaveKeepsPrivateOutputThenRetryWritesExactFile() = runBlocking {
        withSource { source ->
            val result = prepareAndRender(source)
            val expected = MessageDigest.getInstance("SHA-256").digest(result.file.readBytes())
            withContext(Dispatchers.Main) { coordinator.save(Uri.parse("content://com.hirocut.invalid/missing")) }
            val failedSave = awaitStage(ExportStage.Ready)
            assertEquals(ExportProblem.Destination, failedSave.problem)
            assertTrue(result.file.isFile)
            val destination = insertVideo(null)
            try {
                withContext(Dispatchers.Main) { coordinator.save(destination) }
                val completed = awaitStage(ExportStage.Completed, ExportStage.Ready)
                assertEquals(ExportStage.Completed, completed.stage)
                assertEquals(destination.toString(), completed.savedUri)
                val actual = context.contentResolver.openInputStream(destination)!!.use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
                assertArrayEquals(expected, actual)
                assertFalse(result.file.exists())
            } finally { context.contentResolver.delete(destination, null, null) }
        }
    }
    @Test fun anExistingNonemptyDestinationIsNeverTruncatedOrDeleted() = runBlocking {
        withSource { source ->
            val result = prepareAndRender(source)
            val destination = insertVideo("m4-colors.mp4")
            try {
                val before = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
                withContext(Dispatchers.Main) { coordinator.save(destination) }
                val refused = awaitStage(ExportStage.Ready)
                assertEquals(ExportProblem.Destination, refused.problem)
                val after = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
                assertArrayEquals(before, after)
                assertTrue(result.file.isFile)
            } finally { context.contentResolver.delete(destination, null, null) }
        }
    }

    @Test fun cancelRenderingCleansPrivateMediaAndLeavesSourceReadable() = runBlocking {
        withSource { source ->
            awaitStage(ExportStage.Idle)
            val longPlan = EditProject(source.durationMs, (0 until 200).map { ClipRange("part-$it", 0, 2000) })
            withContext(Dispatchers.Main) { coordinator.prepare(source, longPlan, ExportMode.Precise) }
            assertEquals(ExportStage.Review, awaitStage(ExportStage.Review, ExportStage.Failed).stage)
            withContext(Dispatchers.Main) { coordinator.render() }
            delay(300)
            withContext(Dispatchers.Main) { coordinator.cancel() }
            assertEquals(ExportStage.Cancelled, awaitStage(ExportStage.Cancelled, ExportStage.Failed).stage)
            withTimeout(5000) {
                while (File(context.filesDir, "export-jobs").walkTopDown().any { it.name == "output.mp4" }) delay(50)
            }
            assertEquals(6000L, SourceRepository(context).inspect(Uri.parse(source.uri)).durationMs)
        }
    }
    @Test fun cancelSavingRetainsVerifiedOutputForAnotherLocation() = runBlocking {
        withSource { source ->
            val result = prepareAndRender(source)
            val destination = insertVideo(null)
            try {
                withContext(Dispatchers.Main) { coordinator.save(destination); coordinator.cancel() }
                assertEquals(ExportStage.Ready, awaitStage(ExportStage.Ready).stage)
                assertTrue(result.file.isFile)
            } finally { runCatching { context.contentResolver.delete(destination, null, null) } }
        }
    }
    @Test fun sourceLocationIsNotCopiedToOutput() = runBlocking {
        withSource("m4-location.mp4") { source ->
            // Android's retriever does not expose every valid QuickTime location atom.
            // Establish that the actual source bytes carry the synthetic location, then check removal.
            val inputBytes = context.contentResolver.openInputStream(Uri.parse(source.uri))!!.use { it.readBytes() }
            assertTrue(String(inputBytes, Charsets.ISO_8859_1).contains("+12.3400+056.7800/"))
            val (_, result) = render(source, project(source), ExportMode.Fast, "without-location.mp4")
            val output = MediaMetadataRetriever()
            try {
                output.setDataSource(result.file.absolutePath)
                assertNull(output.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                assertFalse(String(result.file.readBytes(), Charsets.ISO_8859_1).contains("+12.3400+056.7800/"))
            } finally { output.release() }
        }
    }
    @Test fun hdrInputIsRejectedWithoutSilentToneMapping() = runBlocking {
        withSource("m4-hdr.mp4") { source ->
            for (mode in ExportMode.entries) {
                try { SourceProbe(context).inspect(source, mode); fail("Expected HDR rejection") }
                catch (expected: ExportFailure) { assertEquals(ExportProblem.HdrUnsupported, expected.problem) }
            }
        }
    }

    @Test fun preflightCancellationCanReturnToEditing() = runBlocking {
        withSource { source ->
            awaitStage(ExportStage.Idle)
            withContext(Dispatchers.Main) { coordinator.prepare(source, project(source), ExportMode.Fast); coordinator.cancel() }
            assertEquals(ExportStage.Cancelled, awaitStage(ExportStage.Cancelled).stage)
        }
    }
}

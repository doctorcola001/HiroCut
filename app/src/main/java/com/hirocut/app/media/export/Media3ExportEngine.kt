package com.hirocut.app.media.export

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import com.hirocut.app.domain.export.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RenderedVideo(val file: File, val durationMs: Long, val width: Int, val height: Int,
    val videoCopied: Boolean, val audioCopied: Boolean, val mode: ExportMode, val hasAudio: Boolean = false)

@UnstableApi
// Opt in only to explicitly disable both experimental trim paths; neither is enabled.
@androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
class Media3ExportEngine(private val context: Context) {
    suspend fun render(plan: ExportPlan, output: File, onProgress: (Int?) -> Unit, onVerifying: () -> Unit = {}, onClipProgress: (String, Int, Boolean) -> Unit = { _, _, _ -> }): RenderedVideo = coroutineScope {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (plan.mode == ExportMode.Smart || plan.mode == ExportMode.Hybrid) {
            return@coroutineScope renderSmart(plan, output, onProgress, onVerifying, onClipProgress)
        }
        val items = plan.segments.map { segment ->
            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionUs(segment.inUs)
                .setEndPositionUs(segment.outUs).setStartsAtKeyFrame(plan.mode == ExportMode.Fast).build()
            val item = EditedMediaItem.Builder(MediaItem.Builder().setMediaId(segment.id).setUri(plan.source.uri).setClippingConfiguration(clipping).build())
                .setDurationUs(plan.spec.durationUs).setRemoveAudio(plan.spec.audioMime == null)
            if (plan.mode != ExportMode.Fast && plan.spec.audioMime != null) {
                item.setEffects(Effects(listOf(ClipAudioDurationProcessor(segment.durationUs)), emptyList()))
            }
            item.build()
        }
        val trackTypes = if (plan.spec.audioMime == null) setOf(C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
        val sequence = EditedMediaItemSequence.Builder(trackTypes).addItems(items).build()
        val composition = Composition.Builder(sequence).setHdrMode(Composition.HDR_MODE_KEEP_HDR)
            .setTransmuxVideo(plan.mode == ExportMode.Fast).setTransmuxAudio(plan.mode == ExportMode.Fast).build()
        val encoder = DefaultEncoderFactory.Builder(context).setEnableFallback(false).setEnableFormatFallback(false)
        if (plan.mode != ExportMode.Fast && plan.spec.videoBitrate > 0) encoder.setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.spec.videoBitrate).build())
        if (plan.mode != ExportMode.Fast && plan.spec.audioBitrate > 0) encoder.setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(plan.spec.audioBitrate).build())
        val tracker = TrackingAssetLoaderFactory(context, onClipProgress)
        var transformer: Transformer? = null
        val poll = launch {
            val holder = ProgressHolder()
            while (isActive) {
                val t = transformer
                onProgress(if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
                delay(200)
            }
        }
        try {
            val result = suspendCancellableCoroutine<ExportResult> { continuation ->
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(exportResult)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(ExportFailure(if (output.parentFile?.usableSpace?.let { it < 16L * 1024 * 1024 } == true)
                            ExportProblem.InsufficientSpace else ExportProblem.Processing).apply { initCause(exportException) })
                    }
                    override fun onFallbackApplied(composition: Composition, originalTransformationRequest: TransformationRequest,
                        fallbackTransformationRequest: TransformationRequest) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(ExportFailure(ExportProblem.UnexpectedFallback))
                            Handler(Looper.getMainLooper()).post { transformer?.cancel() }
                        }
                    }
                }
                val builder = Transformer.Builder(context).setUsePlatformDiagnostics(false)
                    .setAssetLoaderFactory(tracker)
                    // Required orientation/color is written from track Format, not this optional metadata set.
                    .setMuxerFactory(InAppMp4Muxer.Factory { entries -> entries.clear() })
                    .experimentalSetTrimOptimizationEnabled(false).experimentalSetMp4EditListTrimEnabled(false)
                    .setEncoderFactory(encoder.build()).addListener(listener)
                if (plan.mode == ExportMode.Precise) {
                    builder.setVideoMimeType(plan.spec.videoMime)
                    if (plan.spec.audioMime != null) builder.setAudioMimeType(plan.spec.audioMime)
                }
                transformer = builder.build()
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer?.cancel() } }
                try { transformer!!.start(composition, output.absolutePath) }
                catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(ExportFailure(ExportProblem.Processing)) }
            }
            val videoCopied = result.videoConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED
            val audioCopied = result.audioConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED
            if (plan.mode == ExportMode.Fast && (!videoCopied || (plan.spec.audioMime != null && !audioCopied))) {
                throw ExportFailure(ExportProblem.NotStreamCopy)
            }
            onVerifying()
            verify(plan, output, videoCopied, audioCopied)
        } finally { tracker.close(); poll.cancel(); transformer?.cancel() }
    }

    /**
     * Smart render works per selected segment: IDR-safe segments are transmuxed, while only
     * segments whose requested boundary cannot be copied are encoded. The temporary MP4s are
     * then concatenated without decoding the already-safe segments.
     */
    private suspend fun renderSmart(plan: ExportPlan, output: File, onProgress: (Int?) -> Unit,
        onVerifying: () -> Unit, onClipProgress: (String, Int, Boolean) -> Unit): RenderedVideo = coroutineScope {
        val tempFiles = mutableListOf<File>()
        try {
            plan.segments.forEachIndexed { index, segment ->
                val temp = File(output.parentFile, "smart-${index}-${segment.id}.mp4")
                tempFiles += temp
                val mode = if (segment.smartCopy) ExportMode.Fast else ExportMode.Precise
                val subPlan = plan.copy(mode = mode, segments = listOf(segment.copy(smartCopy = false)))
                render(subPlan, temp, { progress ->
                    onProgress(progress?.let { ((index * 100) + it) / plan.segments.size })
                }, onVerifying = {}, onClipProgress = onClipProgress)
            }
            concatSmartSegments(plan, tempFiles, output)
            onProgress(100)
            onVerifying()
            verify(plan, output, plan.segments.all { it.smartCopy }, plan.segments.all { it.smartCopy })
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    private suspend fun concatSmartSegments(plan: ExportPlan, files: List<File>, output: File) = withContext(Dispatchers.IO) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = mutableListOf<MediaExtractor>()
        try {
            if (plan.spec.rotation % 360 != 0) muxer.setOrientationHint(plan.spec.rotation)
            var videoTrack = -1
            var audioTrack = -1
            var timelineUs = 0L
            files.forEachIndexed { index, file ->
                val extractor = MediaExtractor().also { it.setDataSource(file.absolutePath) }
                extractors += extractor
                val formats = (0 until extractor.trackCount).associateBy({ extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.substringBefore('/') }, { it })
                val sourceVideoTrack = formats["video"] ?: throw ExportFailure(ExportProblem.Verification, "smart segment has no video track")
                val sourceAudioTrack = formats["audio"]
                if (videoTrack < 0) {
                    videoTrack = muxer.addTrack(extractor.getTrackFormat(sourceVideoTrack))
                    if (sourceAudioTrack != null) audioTrack = muxer.addTrack(extractor.getTrackFormat(sourceAudioTrack))
                    muxer.start()
                }
                copyTrack(muxer, extractor, sourceVideoTrack, videoTrack, timelineUs)
                if (sourceAudioTrack != null && audioTrack >= 0) copyTrack(muxer, extractor, sourceAudioTrack, audioTrack, timelineUs)
                timelineUs += plan.segments[index].durationUs
            }
            if (videoTrack < 0) throw ExportFailure(ExportProblem.Verification, "smart render produced no segments")
        } catch (known: ExportFailure) { throw known }
        catch (error: Exception) { throw ExportFailure(ExportProblem.Processing, "smart segment mux failed: ${error.message}") }
        finally {
            runCatching { muxer.stop() }
            muxer.release()
            extractors.forEach { it.release() }
        }
    }

    private fun copyTrack(muxer: MediaMuxer, extractor: MediaExtractor, sourceTrack: Int, targetTrack: Int, offsetUs: Long) {
        extractor.selectTrack(sourceTrack)
        var firstPts = C.TIME_UNSET
        var buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        while (extractor.sampleTime >= 0) {
            val size = extractor.sampleSize
            if (size > buffer.capacity()) buffer = java.nio.ByteBuffer.allocate(size.toInt().coerceAtMost(32 * 1024 * 1024))
            buffer.clear()
            val read = extractor.readSampleData(buffer, 0)
            if (read <= 0) break
            if (firstPts == C.TIME_UNSET) firstPts = extractor.sampleTime
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                this.size = read
                presentationTimeUs = offsetUs + (extractor.sampleTime - firstPts).coerceAtLeast(0)
                flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }
            // MediaMuxer reads from the buffer's current position to limit.
            buffer.position(0)
            buffer.limit(read)
            // A copied AAC/video sample may be larger than the conservative duration estimate;
            // timestamps remain monotonic and the final verifier checks the complete output.
            muxer.writeSampleData(targetTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(sourceTrack)
    }

    private suspend fun verify(plan: ExportPlan, output: File, videoCopied: Boolean, audioCopied: Boolean): RenderedVideo = withContext(Dispatchers.IO) {
        if (!output.isFile || output.length() <= 0) throw ExportFailure(ExportProblem.Verification)
        val metadata = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            metadata.setDataSource(output.absolutePath)
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val sourceDisplay = if (plan.spec.rotation % 180 == 0) plan.spec.width to plan.spec.height else plan.spec.height to plan.spec.width
            val outputDisplay = if (rotation % 180 == 0) width to height else height to width
            if (duration <= 0 || sourceDisplay != outputDisplay) throw ExportFailure(ExportProblem.Verification)
            // This is a gross corruption guard, not a claim of arbitrary-millisecond cut accuracy.
            if (kotlin.math.abs(duration * 1000 - plan.durationUs) > maxOf(250_000, plan.segments.size * 50_000L)) throw ExportFailure(ExportProblem.Verification)
            extractor.setDataSource(output.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.singleOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: throw ExportFailure(ExportProblem.Verification)
            val audios = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (video.getString(MediaFormat.KEY_MIME) != plan.spec.videoMime || audios.size != if (plan.spec.audioMime == null) 0 else 1) throw ExportFailure(ExportProblem.Verification)
            if (plan.spec.audioMime != null && (audios.single().getString(MediaFormat.KEY_MIME) != plan.spec.audioMime
                || audios.single().int(MediaFormat.KEY_SAMPLE_RATE) != plan.spec.sampleRate
                || audios.single().int(MediaFormat.KEY_CHANNEL_COUNT) != plan.spec.channels)) throw ExportFailure(ExportProblem.Verification)
            if (video.int(MediaFormat.KEY_COLOR_TRANSFER) in listOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)) throw ExportFailure(ExportProblem.Verification)
            val videoDuration = video.long(MediaFormat.KEY_DURATION)
            if (videoDuration <= 0) throw ExportFailure(ExportProblem.Verification)
            if (plan.spec.audioMime != null) {
                val audioDuration = audios.single().long(MediaFormat.KEY_DURATION)
                if (audioDuration <= 0 || kotlin.math.abs(audioDuration - videoDuration) > 75_000 ||
                    kotlin.math.abs(duration * 1000 - videoDuration) > 75_000) throw ExportFailure(ExportProblem.Verification)
            }
            listOf(MediaFormat.KEY_COLOR_STANDARD to plan.spec.colorStandard,
                MediaFormat.KEY_COLOR_TRANSFER to plan.spec.colorTransfer, MediaFormat.KEY_COLOR_RANGE to plan.spec.colorRange).forEach { (key, expected) ->
                if (expected > 0 && video.int(key) != expected) throw ExportFailure(ExportProblem.Verification)
            }
            RenderedVideo(output, duration, width, height, videoCopied, audioCopied, plan.mode, plan.spec.audioMime != null)
        } catch (known: ExportFailure) { throw known }
        catch (_: Exception) { throw ExportFailure(ExportProblem.Verification) }
        finally { metadata.release(); extractor.release() }
    }
}

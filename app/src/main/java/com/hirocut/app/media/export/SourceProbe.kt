package com.hirocut.app.media.export

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.hirocut.app.domain.export.*
import com.hirocut.app.domain.model.SourceVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class SourceProbe(private val context: Context) {
    suspend fun inspect(source: SourceVideo, mode: ExportMode): MediaSpec = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(source.uri), null)
            val videos = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audios = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (videos.size != 1 || audios.size > 1) throw ExportFailure(ExportProblem.Unsupported,
                "video tracks=${videos.size}, audio tracks=${audios.size}; expected one video and at most one audio track")
            val video = extractor.getTrackFormat(videos.single())
            val audio = audios.firstOrNull()?.let(extractor::getTrackFormat)
            val mime = video.getString(MediaFormat.KEY_MIME).orEmpty()
            val audioMime = audio?.getString(MediaFormat.KEY_MIME)
            val profile = video.int(MediaFormat.KEY_PROFILE)
            if (video.int(MediaFormat.KEY_COLOR_TRANSFER) in listOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)
                || video.int(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT2020
                || video.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)
                || (mime == "video/hevc" && profile in listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus))
                || (mime == "video/avc" && profile in listOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444))) {
                throw ExportFailure(ExportProblem.HdrUnsupported, "video=$mime profile=$profile transfer=${video.int(MediaFormat.KEY_COLOR_TRANSFER)} standard=${video.int(MediaFormat.KEY_COLOR_STANDARD)}")
            }
            if (mime !in listOf("video/avc", "video/hevc") || (audioMime != null && audioMime != "audio/mp4a-latm")) {
                throw ExportFailure(ExportProblem.Unsupported, "video=$mime, audio=${audioMime ?: "none"}; supported audio is AAC and video is AVC/HEVC")
            }
            // Do not guess the bit depth when the extractor cannot establish the source profile.
            if (mime == "video/hevc" && profile != MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) throw ExportFailure(ExportProblem.Unsupported, "HEVC profile=$profile; v0.2 accepts Main 8-bit HEVC only")
            if (mime == "video/avc" && !hasEightBitAvcSps(video)) throw ExportFailure(ExportProblem.Unsupported, "AVC profile/SPS did not verify as 8-bit")
            if ((audio != null) != source.hasAudio) throw ExportFailure(ExportProblem.Unsupported, "source audio metadata does not match extractor tracks")
            val keys = mutableListOf<SyncPoint>()
            if (mode == ExportMode.Fast) {
                if (mime != "video/avc" || context.contentResolver.getType(Uri.parse(source.uri)) != "video/mp4") {
                    throw ExportFailure(ExportProblem.FastUnavailable, "fast mode requires MP4 content with AVC video")
                }
            }
            if (mode == ExportMode.Fast || mode == ExportMode.Smart || mode == ExportMode.Hybrid) {
                extractor.selectTrack(videos.single())
                // The source does not need its first sync sample at timestamp 0. A selected
                // range may start later in the file; ExportPlanner will require an IDR at or
                // after each requested In and the muxer will normalize the copied timestamps.
                extractor.seekTo(0, MediaExtractor.SEEK_TO_NEXT_SYNC)
                var previous = -1L
                while (extractor.sampleTime > previous && extractor.sampleTime >= 0) {
                    ensureActive()
                    val time = extractor.sampleTime
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) throw ExportFailure(ExportProblem.FastUnavailable, "sync sample at $time is not flagged as sync")
                    val size = extractor.sampleSize
                    if (size <= 0 || size > 32 * 1024 * 1024) throw ExportFailure(ExportProblem.FastUnavailable, "invalid sync sample size=$size at $time")
                    val buffer = ByteBuffer.allocate(size.toInt())
                    val bytesRead = extractor.readSampleData(buffer, 0)
                    keys.add(SyncPoint(time, bytesRead > 0 && containsIdr(buffer.array(), bytesRead)))
                    previous = time
                    extractor.seekTo(time + 1000, MediaExtractor.SEEK_TO_NEXT_SYNC)
                }
            }
            MediaSpec(mime, audioMime, video.long(MediaFormat.KEY_DURATION, source.durationMs * 1000),
                video.int(MediaFormat.KEY_WIDTH), video.int(MediaFormat.KEY_HEIGHT), source.rotationDegrees,
                video.int(MediaFormat.KEY_BIT_RATE), audio?.int(MediaFormat.KEY_BIT_RATE) ?: 0,
                audio?.int(MediaFormat.KEY_SAMPLE_RATE) ?: 0, audio?.int(MediaFormat.KEY_CHANNEL_COUNT) ?: 0, keys,
                video.int(MediaFormat.KEY_COLOR_STANDARD), video.int(MediaFormat.KEY_COLOR_TRANSFER), video.int(MediaFormat.KEY_COLOR_RANGE))
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (known: ExportFailure) { throw known }
        catch (_: Exception) { throw ExportFailure(ExportProblem.SourceUnreadable) }
        finally { extractor.release() }
    }

    private fun hasEightBitAvcSps(format: MediaFormat): Boolean {
        val csd = format.getByteBuffer("csd-0")?.duplicate() ?: return false
        val bytes = ByteArray(minOf(csd.remaining(), 256))
        csd.get(bytes)
        for (i in 0 until bytes.size - 5) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                val nal = if (bytes[i + 2] == 1.toByte()) i + 3
                    else if (bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) i + 4 else -1
                if (nal >= 0 && nal + 1 < bytes.size && bytes[nal].toInt() and 31 == 7) {
                    return (bytes[nal + 1].toInt() and 255) in listOf(66, 77, 88, 100)
                }
            }
        }
        return false
    }

    private fun containsIdr(bytes: ByteArray, size: Int): Boolean {
        var offset = 0
        while (offset + 4 < size) {
            val length = ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
                ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            if (length <= 0 || length > size - offset - 4) break
            if (bytes[offset + 4].toInt() and 31 == 5) return true
            offset += 4 + length
        }
        for (i in 0 until size - 4) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                val nal = if (bytes[i + 2] == 1.toByte()) i + 3
                    else if (bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) i + 4 else -1
                if (nal in 0 until size && bytes[nal].toInt() and 31 == 5) return true
            }
        }
        return false
    }
}
internal fun MediaFormat.int(key: String, default: Int = 0): Int = if (containsKey(key)) getInteger(key) else default
internal fun MediaFormat.long(key: String, default: Long = 0): Long = if (containsKey(key)) getLong(key) else default

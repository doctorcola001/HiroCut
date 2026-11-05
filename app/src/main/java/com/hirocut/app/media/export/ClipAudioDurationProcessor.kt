package com.hirocut.app.media.export

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Caps each decoded PCM item to its requested sample count before the shared AAC encoder.
 * Media3 1.11.1 can deliver the whole last decoded AAC block past a clipping boundary.
 * Without this per-item cap, that tail accumulates across a sequence.
 */
@UnstableApi
class ClipAudioDurationProcessor(private val durationUs: Long) : BaseAudioProcessor() {
    private var frameLimit = 0L
    private var outputFrames = 0L
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.bytesPerFrame <= 0) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        frameLimit = Math.addExact(Math.multiplyExact(durationUs / 1_000_000, inputAudioFormat.sampleRate.toLong()),
            durationUs % 1_000_000 * inputAudioFormat.sampleRate / 1_000_000)
        outputFrames = 0
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val keepFrames = minOf(inputBuffer.remaining().toLong() / bytesPerFrame, (frameLimit - outputFrames).coerceAtLeast(0))
        val keepBytes = (keepFrames * bytesPerFrame).toInt()
        val slice = inputBuffer.duplicate()
        slice.limit(slice.position() + keepBytes)
        replaceOutputBuffer(keepBytes).put(slice).flip()
        inputBuffer.position(inputBuffer.limit())
        outputFrames += keepFrames
    }
}

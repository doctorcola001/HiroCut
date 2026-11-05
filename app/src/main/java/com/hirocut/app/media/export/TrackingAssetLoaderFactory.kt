package com.hirocut.app.media.export

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.*
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Observes accepted source samples/EOS without changing buffers, timestamps or codec decisions. */
@UnstableApi
class TrackingAssetLoaderFactory(context: Context, private val onProgress: (String, Int, Boolean) -> Unit) : AssetLoader.Factory {
    private val delegate = DefaultAssetLoaderFactory(context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT, null)
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)
    fun close() { active.set(false) }

    override fun createAssetLoader(item: EditedMediaItem, looper: Looper, listener: AssetLoader.Listener,
        settings: AssetLoader.CompositionSettings): AssetLoader {
        val id = item.mediaItem.mediaId
        val clipping = item.mediaItem.clippingConfiguration
        val expectedDuration = (clipping.endPositionUs - clipping.startPositionUs).coerceAtLeast(1)
        val trackCount = AtomicInteger(1)
        val progress = mutableMapOf<Int, Int>()
        val ended = mutableSetOf<Int>()
        var lastReported = -1
        var reportedDone = false
        fun report(type: Int, timeUs: Long = 0, done: Boolean = false) {
            synchronized(progress) {
                if (done) ended.add(type)
                val value = if (done) 100 else (timeUs.toDouble() / expectedDuration * 100).toInt().coerceIn(0, 99)
                progress[type] = maxOf(progress[type] ?: 0, value)
                val finished = ended.size >= trackCount.get()
                val percent = if (finished) 100 else if (progress.size < trackCount.get()) 0 else progress.values.minOrNull() ?: 0
                if (percent == lastReported && finished == reportedDone) return
                lastReported = percent; reportedDone = finished
                main.post { if (active.get()) onProgress(id, percent, finished) }
            }
        }
        val consumers = IdentityHashMap<SampleConsumer, SampleConsumer>()
        val wrapped = object : AssetLoader.Listener {
            override fun onDurationUs(durationUs: Long) { listener.onDurationUs(durationUs) }
            override fun onTrackCount(count: Int) { trackCount.set(count); listener.onTrackCount(count) }
            override fun onTrackAdded(format: Format, supportedOutputTypes: Int): Boolean = listener.onTrackAdded(format, supportedOutputTypes)
            override fun onError(exception: ExportException) { listener.onError(exception) }
            override fun onOutputFormat(format: Format): SampleConsumer? {
                val target = listener.onOutputFormat(format) ?: return null
                consumers[target]?.let { return it }
                val type = MimeTypes.getTrackType(format.sampleMimeType)
                return object : SampleConsumer {
                    // Explicitly forward every Java default method. Kotlin delegation can inherit
                    // interface defaults instead, which would throw for decoded-video surfaces.
                    override fun getInputSurface(): android.view.Surface = target.inputSurface
                    override fun getPendingVideoFrameCount(): Int = target.pendingVideoFrameCount
                    override fun setOnInputFrameProcessedListener(listener: androidx.media3.common.OnInputFrameProcessedListener) = target.setOnInputFrameProcessedListener(listener)
                    override fun setOnInputSurfaceReadyListener(runnable: Runnable) = target.setOnInputSurfaceReadyListener(runnable)
                    override fun queueInputBitmap(bitmap: android.graphics.Bitmap, timestamps: androidx.media3.common.util.TimestampIterator): Int = target.queueInputBitmap(bitmap, timestamps)
                    override fun queueInputTexture(texId: Int, presentationTimeUs: Long): Int = target.queueInputTexture(texId, presentationTimeUs)
                    var input: DecoderInputBuffer? = null
                    override fun getInputBuffer(): DecoderInputBuffer? = target.inputBuffer.also { input = it }
                    override fun queueInputBuffer(): Boolean {
                        val eos = input?.isEndOfStream == true
                        val time = input?.timeUs ?: 0
                        val accepted = target.queueInputBuffer()
                        if (accepted) { report(type, time, eos); input = null }
                        return accepted
                    }
                    override fun registerVideoFrame(presentationTimeUs: Long): Boolean {
                        val accepted = target.registerVideoFrame(presentationTimeUs)
                        if (accepted) report(type, presentationTimeUs)
                        return accepted
                    }
                    override fun signalEndOfVideoInput() { target.signalEndOfVideoInput(); report(type, done = true) }
                }.also { consumers[target] = it }
            }
        }
        return delegate.createAssetLoader(item, looper, wrapped, settings)
    }
}

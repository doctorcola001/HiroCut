package com.hirocut.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.hirocut.app.domain.model.SourceVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Small in-memory covers, one decoder at a time, no files or uploads. */
class ThumbnailRepository(private val context: Context) {
    private val slots = Semaphore(1)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    suspend fun firstFrame(source: SourceVideo, inMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${source.uri}|${source.fileSizeBytes}|${source.durationMs}|$inMs"
        cache.get(key)?.let { return@withContext it }
        slots.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(source.uri))
                retriever.getScaledFrameAtTime(inMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 160, 90)?.also { cache.put(key, it) }
            } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
        }
    }
}

package com.hirocut.app.data.files

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.hirocut.app.domain.model.ImportFailure
import com.hirocut.app.domain.model.SourceImportException
import com.hirocut.app.domain.model.SourceVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads only user-granted documents. No filesystem-path conversion or network client. */
class SourceRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences("source", Context.MODE_PRIVATE)

    fun savedUri(): Uri? = preferences.getString("uri", null)?.let(Uri::parse)

    suspend fun inspect(uri: Uri): SourceVideo = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw SourceImportException(ImportFailure.Unreadable)
        val heldBefore = hasGrant(uri)
        try {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers only offer a session grant. Show that limitation to the user.
            }
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                try {
                    Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                } catch (_: ErrnoException) {
                    throw SourceImportException(ImportFailure.NotSeekable)
                }
            } ?: throw SourceImportException(ImportFailure.Unreadable)
            var fileSize: Long? = null
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        if (!cursor.isNull(1)) fileSize = cursor.getLong(1).takeIf { it >= 0 }
                        cursor.getString(0).orEmpty()
                    } else ""
                }.orEmpty()
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (width <= 0 || height <= 0) throw SourceImportException(ImportFailure.NoVideo)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                if (duration <= 0) throw SourceImportException(ImportFailure.InvalidDuration)
                SourceVideo(uri.toString(), displayName, duration, width, height,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                    hasGrant(uri), fileSize)
            }
        } catch (error: Exception) {
            if (!heldBefore) releaseGrant(uri)
            if (error is SourceImportException) throw error
            throw SourceImportException(ImportFailure.Unreadable)
        }
    }

    fun remember(source: SourceVideo) {
        val oldUri = savedUri()
        // Session-only documents cannot reliably be restored after process death.
        if (source.hasPersistentAccess) preferences.edit().putString("uri", source.uri).apply()
        else preferences.edit().remove("uri").apply()
        if (oldUri != null && oldUri.toString() != source.uri) releaseGrant(oldUri)
    }

    fun forget() {
        savedUri()?.let(::releaseGrant)
        preferences.edit().remove("uri").apply()
    }

    private fun hasGrant(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun releaseGrant(uri: Uri) {
        try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* Already revoked or session-only. */ }
    }
}

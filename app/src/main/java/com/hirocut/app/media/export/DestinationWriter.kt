package com.hirocut.app.media.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import java.io.File

class DestinationWriter(private val context: Context) {
    suspend fun copy(file: File, uri: Uri, onAccepted: suspend () -> Unit, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val total = file.length().coerceAtLeast(1)
        // CreateDocument should give a new empty document. Never truncate an existing file,
        // including a different content-URI alias for the source, and never delete a rejected target.
        val knownSize = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        if (knownSize != null && knownSize > 0) throw java.io.IOException()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw java.io.IOException()
        descriptor.use {
            val stat = Os.fstat(it.fileDescriptor)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 0L) throw java.io.IOException()
            Os.lseek(it.fileDescriptor, 0L, OsConstants.SEEK_SET)
            withContext(Dispatchers.Main.immediate) { onAccepted() }
            ParcelFileDescriptor.AutoCloseOutputStream(it).use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        val percent = (written.toDouble() / total * 100).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main.immediate) { onProgress(percent) }
                        }
                    }
                    output.flush()
                }
            }
        }
    }
    suspend fun deleteIncomplete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        catch (_: Exception) {
            try { context.contentResolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }
        }
    }
}

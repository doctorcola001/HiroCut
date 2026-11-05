package com.hirocut.app.domain.model

/** Platform-independent metadata. Times are milliseconds; URI stays opaque. */
data class SourceVideo(
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val hasAudio: Boolean,
    val hasPersistentAccess: Boolean,
    val fileSizeBytes: Long? = null,
)

enum class ImportFailure { Unreadable, NoVideo, InvalidDuration, NotSeekable }

class SourceImportException(val reason: ImportFailure) : Exception()

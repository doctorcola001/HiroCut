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
    val containerMime: String? = null,
    val videoMime: String? = null,
    val videoCodec: String? = null,
    val videoProfile: String? = null,
    val videoBitrate: Int = 0,
    val frameRate: Float = 0f,
    val audioMime: String? = null,
    val audioCodec: String? = null,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
)

enum class ImportFailure { Unreadable, NoVideo, InvalidDuration, NotSeekable }

class SourceImportException(val reason: ImportFailure) : Exception()

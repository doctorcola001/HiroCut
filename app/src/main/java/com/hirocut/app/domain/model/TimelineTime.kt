package com.hirocut.app.domain.model

import java.util.Locale
import kotlin.math.roundToLong

object TimelineTime {
    fun format(milliseconds: Long): String {
        val time = milliseconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
            time / 3_600_000, time / 60_000 % 60, time / 1_000 % 60, time % 1_000)
    }

    fun parse(text: String): Long? {
        val match = Regex("""(\d{1,12}):([0-5]\d):([0-5]\d)\.(\d{3})""").matchEntire(text.trim()) ?: return null
        val (hours, minutes, seconds, millis) = match.destructured
        return runCatching {
            Math.addExact(Math.multiplyExact(hours.toLong(), 3_600_000L),
                minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + millis.toLong())
        }.getOrNull()
    }

    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.coerceIn(0, durationMs).toDouble() / durationMs).toFloat()

    fun position(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0 || !fraction.isFinite()) return 0
        return (fraction.coerceIn(0f, 1f).toDouble() * durationMs).roundToLong().coerceIn(0, durationMs)
    }
}

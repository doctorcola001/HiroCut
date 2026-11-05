package com.hirocut.app.media.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class PlaybackState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val failed: Boolean = false,
    val speed: Float = 1f,
    val framePositionMs: Long = -1,
)

/** A single presentation clock drives every timeline, including optimistic scrub updates. */
@UnstableApi
class PlayerController(context: Context, scope: CoroutineScope) {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).setUsePlatformDiagnostics(false).build()
    private var released = false
    private var knownDurationMs = 0L
    private var pendingPosition: Long? = null
    private var pendingDeadlineNs = 0L
    private val frameTimeUs = AtomicLong(C.TIME_UNSET)
    private val mutableState = MutableStateFlow(PlaybackState())
    val state = mutableState.asStateFlow()

    init {
        player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ -> frameTimeUs.set(presentationTimeUs) }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publish() }
            override fun onPlayerError(error: PlaybackException) {
                mutableState.value = mutableState.value.copy(failed = true, isBuffering = false, isPlaying = false)
            }
        })
        scope.launch {
            while (isActive) { publish(); delay(if (player.isPlaying) 16 else 66) }
        }
    }
    fun load(uri: String, positionMs: Long = 0, durationMs: Long = 0) {
        if (released) return
        knownDurationMs = durationMs.coerceAtLeast(0)
        frameTimeUs.set(C.TIME_UNSET)
        pendingPosition = null
        mutableState.value = PlaybackState(durationMs = knownDurationMs, speed = player.playbackParameters.speed)
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)), positionMs.coerceAtLeast(0))
        player.playWhenReady = false
        player.prepare()
    }
    fun toggle() {
        if (released) return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) { pendingPosition = null; player.seekTo(0) }
            player.play()
        }
        publish()
    }
    fun seek(positionMs: Long) = previewSeek(positionMs, performSeek = true)
    fun previewSeek(positionMs: Long, performSeek: Boolean) {
        if (released) return
        val duration = knownDurationMs.takeIf { it > 0 } ?: player.duration.coerceAtLeast(0)
        val target = positionMs.coerceIn(0, duration.coerceAtLeast(0))
        pendingPosition = target
        pendingDeadlineNs = System.nanoTime() + 350_000_000
        mutableState.value = mutableState.value.copy(positionMs = target)
        if (performSeek) player.seekTo(target)
    }
    fun currentFrameMs(): Long? {
        val time = frameTimeUs.get()
        return if (time == C.TIME_UNSET || time < 0) null else (time + 500) / 1000
    }
    fun setSpeed(speed: Float) {
        if (released || !speed.isFinite() || speed !in 0.25f..4f) return
        player.setPlaybackSpeed(speed)
        publish()
    }
    fun pause() { if (!released) { player.pause(); publish() } }
    fun release() { if (!released) { released = true; player.release() } }
    private fun publish() {
        if (released) return
        val raw = player.currentPosition.coerceAtLeast(0)
        val actual = if (knownDurationMs > 0 && player.duration > 0 && raw >= player.duration && kotlin.math.abs(knownDurationMs - player.duration) <= 2) knownDurationMs else raw
        val pending = pendingPosition
        // ExoPlayer can clamp a paused seek-to-end to D-1ms. Preserve the explicit endpoint
        // so Add cannot accidentally create a 1ms clip after the user chose EOF.
        val explicitEnd = pending != null && pending == knownDurationMs && actual >= knownDurationMs - 16
        if (!explicitEnd && pending != null && (abs(actual - pending) <= 16 || System.nanoTime() >= pendingDeadlineNs)) pendingPosition = null
        mutableState.value = PlaybackState(
            positionMs = pendingPosition ?: actual,
            durationMs = knownDurationMs.takeIf { it > 0 } ?: player.duration.coerceAtLeast(0),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            failed = player.playerError != null,
            speed = player.playbackParameters.speed,
            framePositionMs = currentFrameMs() ?: -1,
        )
    }
}

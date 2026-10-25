package com.karaoketv.media

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player

/** What the lyrics follow: a real player, or a stopwatch in lyrics-only mode. */
interface PlaybackClock {
    val positionMs: Long
    val durationMs: Long?
    val isPlaying: Boolean
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
}

class PlayerClock(private val player: Player) : PlaybackClock {
    override val positionMs get() = player.currentPosition
    override val durationMs get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
    override val isPlaying get() = player.isPlaying
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))
}

class StopwatchClock(override val durationMs: Long?) : PlaybackClock {
    private var accumulated = 0L
    private var startedAt: Long? = null

    override val positionMs: Long
        get() {
            val running = startedAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L
            val pos = accumulated + running
            return durationMs?.let { minOf(pos, it) } ?: pos
        }

    override val isPlaying get() = startedAt != null && (durationMs == null || positionMs < durationMs)

    override fun play() {
        if (startedAt == null) startedAt = SystemClock.elapsedRealtime()
    }

    override fun pause() {
        accumulated = positionMs
        startedAt = null
    }

    override fun seekTo(positionMs: Long) {
        accumulated = positionMs.coerceAtLeast(0)
        if (startedAt != null) startedAt = SystemClock.elapsedRealtime()
    }
}

package com.karaoketv.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI

/**
 * Classic "karaoke" center-channel cancellation: lead vocals are usually mixed to the center,
 * so L-R removes them. Bass and kick are also centered, so the low end of the mid signal is
 * added back with a one-pole low-pass. Works on 16-bit stereo PCM; other formats pass through.
 */
@UnstableApi
class VocalRemover : BaseAudioProcessor() {
    @Volatile
    var enabled = false

    private var alpha = 0f
    private var bass = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val dt = 1f / inputAudioFormat.sampleRate
        val rc = 1f / (2f * PI.toFloat() * BASS_CUTOFF_HZ)
        alpha = dt / (rc + dt)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        if (!enabled) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= 4) {
                val l = inputBuffer.short.toFloat()
                val r = inputBuffer.short.toFloat()
                val side = (l - r) * 0.5f
                bass += alpha * ((l + r) * 0.5f - bass)
                output.putShort(clamp(side + bass))
                output.putShort(clamp(-side + bass))
            }
        }
        output.flip()
    }

    override fun onFlush() {
        bass = 0f
    }

    private fun clamp(v: Float): Short =
        v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

    private companion object {
        const val BASS_CUTOFF_HZ = 160f
    }
}

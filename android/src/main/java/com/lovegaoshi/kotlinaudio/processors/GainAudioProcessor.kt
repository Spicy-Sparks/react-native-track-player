package com.lovegaoshi.kotlinaudio.processors

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Applies a per-track linear output gain to the decoded PCM (loudness
 * normalization). Because it sits in the ExoPlayer audio pipeline it works for
 * every source type — HLS included — and, unlike a 0..1 player volume, it can
 * also boost (gain > 1). 1.0 is unity passthrough.
 */
@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var gain: Float = 1f

    fun setGain(value: Float) {
        gain = if (value.isFinite()) value.coerceIn(0f, 8f) else 1f
    }

    fun getGain(): Float = gain

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        ) {
            return inputAudioFormat
        }
        return AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val g = gain
        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size)

        if (g == 1f) {
            if (output !== inputBuffer) {
                output.put(inputBuffer)
            }
            output.flip()
            return
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (inputBuffer.hasRemaining()) {
                    val sample = inputBuffer.short
                    output.putShort(
                        (sample * g).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    )
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.hasRemaining()) {
                    output.putFloat(inputBuffer.float * g)
                }
            }
        }
        output.flip()
    }
}

package com.lovegaoshi.kotlinaudio.processors

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Applies a per-track linear output gain to the decoded PCM (loudness
 * normalization). Because it sits in the ExoPlayer audio pipeline it works for
 * every source type — HLS included — and, unlike a 0..1 player volume, it can
 * also boost (gain > 1). 1.0 is unity passthrough.
 *
 * It also measures how long the decoded audio has been silent, for the smart
 * crossfade: a track whose music ends before its file does can be blended out
 * of at the point it actually goes quiet. The level is read BEFORE the gain so
 * normalization does not move the threshold.
 */
@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var gain: Float = 1f

    fun setGain(value: Float) {
        gain = if (value.isFinite()) value.coerceIn(0f, 8f) else 1f
    }

    fun getGain(): Float = gain

    // Frames of continuous silence at the head of the decoded stream. Written
    // on the playback thread, read from the module; a stale read is harmless.
    @Volatile
    private var silentFrames: Long = 0

    /**
     * Milliseconds the decoded audio has been continuously silent, or -1 when
     * nothing has been measured yet. Measured where PCM is decoded, which runs
     * ahead of the speaker by the output buffer (well under a second), so it
     * can read silence slightly before it is heard.
     */
    fun getTrailingSilenceMs(): Long {
        val rate = inputAudioFormat.sampleRate
        if (rate <= 0) return -1
        return silentFrames * 1000 / rate
    }

    override fun onFlush() {
        // A seek or a new item: the silence measured so far belongs to audio
        // that will not be heard.
        silentFrames = 0
    }

    override fun onReset() {
        silentFrames = 0
    }

    private fun measureSilence(buffer: ByteBuffer) {
        val nCh = inputAudioFormat.channelCount
        if (nCh <= 0) return
        val start = buffer.position()
        val end = buffer.limit()
        var peak = 0f
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = start
                while (i + 1 < end) {
                    val v = abs(buffer.getShort(i).toFloat()) / 32768f
                    if (v > peak) peak = v
                    i += 2
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var i = start
                while (i + 3 < end) {
                    val v = abs(buffer.getFloat(i))
                    if (v > peak) peak = v
                    i += 4
                }
            }
            else -> return
        }
        val bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) 2 else 4
        val frames = (end - start) / (bytesPerSample * nCh)
        silentFrames = if (peak < SILENCE_PEAK) silentFrames + frames else 0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        ) {
            return inputAudioFormat
        }
        return AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        measureSilence(inputBuffer)
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

    companion object {
        // -48 dBFS peak: below the noise floor of a mastered fade-out, above
        // dither and codec noise on digital silence.
        private const val SILENCE_PEAK = 0.004f
    }
}

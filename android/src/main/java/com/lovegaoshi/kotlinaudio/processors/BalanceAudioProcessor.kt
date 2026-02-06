package com.lovegaoshi.kotlinaudio.processors

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@UnstableApi
class BalanceAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var balance: Float = 0f

    fun setBalance(value: Float) {
        balance = value.coerceIn(-1f, 1f)
    }

    fun getBalance(): Float = balance

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        ) {
            return inputAudioFormat
        }
        return AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bal = balance
        val leftGain = if (bal <= 0f) 1f else 1f - bal
        val rightGain = if (bal >= 0f) 1f else 1f + bal

        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size)

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (inputBuffer.hasRemaining()) {
                    val left = inputBuffer.short
                    val right = inputBuffer.short
                    output.putShort((left * leftGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                    output.putShort((right * rightGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.hasRemaining()) {
                    val left = inputBuffer.float
                    val right = inputBuffer.float
                    output.putFloat(left * leftGain)
                    output.putFloat(right * rightGain)
                }
            }
        }
        output.flip()
    }
}

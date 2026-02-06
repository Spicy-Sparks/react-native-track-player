package com.lovegaoshi.kotlinaudio.player.components

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.lovegaoshi.kotlinaudio.processors.FFTEmitter
import com.lovegaoshi.kotlinaudio.processors.TeeListener

@UnstableApi
class APMRenderersFactory(
    context: Context,
    sampleRate: Int = 4096,
    emitter: FFTEmitter?,
    private val extraProcessors: Array<AudioProcessor> = emptyArray()
) : DefaultRenderersFactory(context) {
    val teeProcessor = if (sampleRate > 0 && emitter != null)
        TeeAudioProcessor(TeeListener(sampleRate, emitter)) else null

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? {
        val processors = if (teeProcessor != null)
            arrayOf(*extraProcessors, teeProcessor) else arrayOf(*extraProcessors)
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(processors)
            .build()
    }

}

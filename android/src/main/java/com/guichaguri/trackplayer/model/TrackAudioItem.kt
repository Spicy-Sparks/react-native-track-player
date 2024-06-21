package com.guichaguri.trackplayer.model

import android.graphics.Bitmap
import com.guichaguri.trackplayer.kotlinaudio.models.AudioItem
import com.guichaguri.trackplayer.kotlinaudio.models.AudioItemOptions
import com.guichaguri.trackplayer.kotlinaudio.models.MediaType

data class TrackAudioItem(
    val track: Track,
    override val type: MediaType,
    override var audioUrl: String,
    override var artist: String? = null,
    override var title: String? = null,
    override var albumTitle: String? = null,
    override val artwork: String? = null,
    override val duration: Long = 0,
    override val options: AudioItemOptions? = null,
    override var artworkBitmap: Bitmap? = null
): AudioItem
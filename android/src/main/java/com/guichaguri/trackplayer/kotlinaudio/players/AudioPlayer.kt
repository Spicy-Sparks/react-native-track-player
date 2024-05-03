package com.guichaguri.trackplayer.kotlinaudio.players

import android.content.Context
import com.doublesymmetry.kotlinaudio.players.BaseAudioPlayer
import com.guichaguri.trackplayer.kotlinaudio.models.BufferConfig
import com.guichaguri.trackplayer.kotlinaudio.models.CacheConfig
import com.guichaguri.trackplayer.kotlinaudio.models.PlayerConfig
import com.guichaguri.trackplayer.kotlinaudio.models.AAMediaSessionCallBack

class AudioPlayer(context: Context, playerConfig: PlayerConfig = PlayerConfig(), bufferConfig: BufferConfig? = null, cacheConfig: CacheConfig? = null, mediaSessionCallback: AAMediaSessionCallBack): BaseAudioPlayer(context, playerConfig, bufferConfig, cacheConfig, mediaSessionCallback)
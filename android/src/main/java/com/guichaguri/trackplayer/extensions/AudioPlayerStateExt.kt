package com.guichaguri.trackplayer.extensions

import com.guichaguri.trackplayer.kotlinaudio.models.AudioPlayerState
import com.guichaguri.trackplayer.model.State

val AudioPlayerState.asLibState: State
    get() {
        return when(this) {
            AudioPlayerState.LOADING -> State.Loading
            AudioPlayerState.READY -> State.Ready
            AudioPlayerState.BUFFERING -> State.Buffering
            AudioPlayerState.PAUSED -> State.Paused
            AudioPlayerState.PLAYING -> State.Playing
            AudioPlayerState.IDLE -> State.None
            AudioPlayerState.ENDED -> State.Ended
            AudioPlayerState.ERROR -> State.Error
            AudioPlayerState.STOPPED -> State.Stopped
        }
    }

package com.guichaguri.trackplayer.kotlinaudio.models

import android.os.Bundle

interface AAMediaSessionCallBack {
    fun handlePlayFromMediaId(mediaId: String?, extras: Bundle?)
    fun handlePlayFromSearch(query: String?, extras: Bundle?)
    fun handleSkipToQueueItem(id: Long)
    fun handleCustomActions(action: String?, extras: Bundle?)
}
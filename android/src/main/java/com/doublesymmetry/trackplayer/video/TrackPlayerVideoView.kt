package com.doublesymmetry.trackplayer.video

import android.content.Context
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.doublesymmetry.trackplayer.service.MusicService

/**
 * A simple wrapper that surfaces the ExoPlayer instance managed by RN Track Player
 * inside a Media3 [PlayerView] so that React Native can render the video track.
 */
class TrackPlayerVideoView(context: Context) : FrameLayout(context) {
    private val playerView: PlayerView = PlayerView(context).apply {
        useController = false
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    init {
        addView(playerView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Get the ExoPlayer instance from the running MusicService if available
        playerView.player = MusicService.instance?.audioPlayer?.exoPlayer
    }

    override fun onDetachedFromWindow() {
        playerView.player = null
        super.onDetachedFromWindow()
    }

    fun setResizeMode(mode: String?) {
        when (mode) {
            "cover" -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "stretch" -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            "none" -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT // contain default
        }
    }
}

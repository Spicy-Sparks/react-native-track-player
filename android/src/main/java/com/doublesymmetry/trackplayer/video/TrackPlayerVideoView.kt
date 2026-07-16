package com.doublesymmetry.trackplayer.video

import android.content.Context
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import com.doublesymmetry.trackplayer.service.MusicService

/**
 * Surfaces the ExoPlayer video track inside a **TextureView** rather than the
 * default SurfaceView that Media3's PlayerView uses.
 *
 * Why: a SurfaceView renders in its own dedicated window layer, so transforms
 * applied to ancestor views (e.g. a React Native 90° rotation used to present
 * a landscape video fullscreen on a portrait-locked screen) don't affect it —
 * the surrounding views rotate while the video stays upright. A TextureView
 * composites in the normal view hierarchy, so an ancestor transform rotates
 * the video pixels too.
 *
 * The video is wrapped in an [AspectRatioFrameLayout] so it stays letterboxed
 * (RESIZE_MODE_FIT by default), matching the old PlayerView behaviour.
 */
class TrackPlayerVideoView(context: Context) : FrameLayout(context) {
    private val contentFrame = AspectRatioFrameLayout(context).apply {
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }
    private val textureView = TextureView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private val videoListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyAspectRatio(videoSize)
        }
    }

    private fun applyAspectRatio(videoSize: VideoSize) {
        val ratio = if (videoSize.height == 0 || videoSize.width == 0) 0f
            else (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
        contentFrame.setAspectRatio(ratio)
    }

    init {
        contentFrame.addView(textureView)
        addView(
            contentFrame,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val player = MusicService.instance?.audioPlayer?.exoPlayer
        player?.setVideoTextureView(textureView)
        player?.addListener(videoListener)
        // Prime the aspect ratio from the already-known size, if any.
        player?.videoSize?.let { applyAspectRatio(it) }
    }

    override fun onDetachedFromWindow() {
        val player = MusicService.instance?.audioPlayer?.exoPlayer
        player?.clearVideoTextureView(textureView)
        player?.removeListener(videoListener)
        super.onDetachedFromWindow()
    }

    fun setResizeMode(mode: String?) {
        when (mode) {
            "cover" -> contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "stretch" -> contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            "none" -> contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else -> contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT // contain default
        }
    }
}

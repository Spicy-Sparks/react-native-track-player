package com.doublesymmetry.trackplayer.video

import android.content.Context
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import com.doublesymmetry.trackplayer.service.MusicService
import com.lovegaoshi.kotlinaudio.player.AudioPlayer

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
    companion object {
        // Views on screen, newest last. Fullscreen mounts its view before the
        // inline one goes away: the newest draws, and when it goes the one left
        // takes the surface back.
        private val shown = ArrayList<TrackPlayerVideoView>()

        /** Hands a player built now the views on screen: the newest one's surface, and their size listeners. */
        fun bindTo(audioPlayer: AudioPlayer) {
            shown.forEach { it.listenTo(audioPlayer) }
            audioPlayer.videoTextureView = shown.lastOrNull()?.textureView
        }
    }

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

    private var attached = false

    // On the forwarding player, so the listener follows a crossfade.
    private fun listenTo(audioPlayer: AudioPlayer) {
        audioPlayer.player.addListener(videoListener)
        // Prime the aspect ratio from the already-known size, if any.
        applyAspectRatio(audioPlayer.player.videoSize)
    }

    // On screen means attached to a window that is showing. Home and the lock
    // screen hide the window but leave the view attached, and a video nobody
    // sees must not keep streaming at full quality behind the music.
    private fun updateShown() {
        val onScreen = attached && windowVisibility == View.VISIBLE
        if (onScreen == shown.contains(this)) return
        val audioPlayer = MusicService.instance?.audioPlayer
        if (onScreen) {
            shown.add(this)
            audioPlayer?.let {
                listenTo(it)
                // Taking the surface is what lifts the video off its smallest
                // rendition (AudioPlayer.videoTextureView).
                it.videoTextureView = textureView
            }
        } else {
            shown.remove(this)
            audioPlayer?.let {
                it.player.removeListener(videoListener)
                // The last view gone leaves no surface, and the video drops back
                // to its smallest rendition.
                if (it.videoTextureView === textureView) it.videoTextureView = shown.lastOrNull()?.textureView
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateShown()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateShown()
    }

    override fun onDetachedFromWindow() {
        attached = false
        updateShown()
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

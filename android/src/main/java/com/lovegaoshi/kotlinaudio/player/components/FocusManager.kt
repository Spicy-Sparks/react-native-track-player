package com.lovegaoshi.kotlinaudio.player.components

import android.content.Context
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.lovegaoshi.kotlinaudio.models.AudioPlayerState
import com.lovegaoshi.kotlinaudio.models.PlayerOptions

/** The two system calls [FocusManager] makes, behind a seam so the policy runs on a plain JVM. */
interface AudioFocusGateway {
    /** Asks for AUDIOFOCUS_GAIN; returns an AudioManager.AUDIOFOCUS_REQUEST_* result. */
    fun request(willPauseWhenDucked: Boolean): Int

    /** Gives the focus last asked for back; returns an AudioManager.AUDIOFOCUS_REQUEST_* result. */
    fun abandon(): Int
}

private class SystemAudioFocusGateway(
    private val context: Context,
    private val listener: AudioManager.OnAudioFocusChangeListener,
) : AudioFocusGateway {
    private var focus: AudioFocusRequestCompat? = null

    override fun request(willPauseWhenDucked: Boolean): Int {
        val manager = ContextCompat.getSystemService(context, AudioManager::class.java)
        val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(listener)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(willPauseWhenDucked)
            .build()
        focus = request
        return if (manager != null) {
            AudioManagerCompat.requestAudioFocus(manager, request)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }

    override fun abandon(): Int {
        val manager = ContextCompat.getSystemService(context, AudioManager::class.java)
        val request = focus
        return if (manager != null && request != null) {
            AudioManagerCompat.abandonAudioFocusRequest(manager, request)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }
}

class FocusManager(
    private val gateway: AudioFocusGateway,
    options: PlayerOptions,
) {
    constructor(
        context: Context,
        listener: AudioManager.OnAudioFocusChangeListener,
        options: PlayerOptions,
    ) : this(SystemAudioFocusGateway(context, listener), options)

    /**
     * Whether the system counts us as the focus holder. A transient loss (a call, a navigation
     * prompt) leaves it true: the system keeps us in its stack and gives the focus back with
     * AUDIOFOCUS_GAIN. A permanent loss ([onFocusLostPermanently]) makes it false: the system has
     * dropped us, nothing will come back, and the next play has to ask again.
     */
    var hasAudioFocus = false
        private set
    var alwaysPauseOnInterruption = options.alwaysPauseOnInterruption

    /**
     * Whether the output is ducked for an AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK. A duck can only
     * last while the focus is ours: whatever ends it (GAIN, a permanent loss, giving the focus back
     * on stop) restores the volume. A transient loss on top of a duck leaves it on until GAIN.
     */
    var ducked = false
        private set(value) {
            if (field == value) return
            field = value
            onDuckChanged?.invoke(value)
        }

    /** Called with the new value whenever [ducked] changes; the player scales its volume. */
    var onDuckChanged: ((Boolean) -> Unit)? = null

    fun requestAudioFocus() {
        if (hasAudioFocus) return
        hasAudioFocus = gateway.request(alwaysPauseOnInterruption) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonAudioFocusIfHeld() {
        if (!hasAudioFocus) return
        // Count the focus as given back whatever the result: kept true, a failed abandon made
        // every later request return early, and the player could then play without the focus.
        // Asking again with the same listener replaces any entry the system still holds.
        gateway.abandon()
        hasAudioFocus = false
        // Out of the focus stack, no GAIN will come to end a duck.
        ducked = false
    }

    /**
     * Keeps the focus in step with the player.
     *
     * The focus used to be asked for only on the way into READY. A resume from pause never goes
     * through READY (ExoPlayer stays in STATE_READY while paused), so after a permanent loss —
     * another music app, a video, an ad — Play brought the sound back without the focus: the app
     * then played over everything else and never paused for anyone again, until the player was
     * recreated. Entering PLAYING without the focus now asks for it.
     *
     * When ExoPlayer handles the focus itself ([exoPlayerHandlesFocus], autoHandleInterruptions
     * on), its own client asks on every play; a second request from this app on top of it would
     * take the focus from ExoPlayer's client, which pauses on that loss. So PLAYING asks only when
     * this manager is the one handling the focus.
     */
    fun onPlayerStateChanged(state: AudioPlayerState, exoPlayerHandlesFocus: Boolean) {
        when (state) {
            AudioPlayerState.IDLE,
            AudioPlayerState.STOPPED,
            AudioPlayerState.ERROR -> abandonAudioFocusIfHeld()
            AudioPlayerState.READY -> requestAudioFocus()
            AudioPlayerState.PLAYING -> if (!exoPlayerHandlesFocus) requestAudioFocus()
            else -> {}
        }
    }

    /** AUDIOFOCUS_LOSS: the system has dropped us from its focus stack. No GAIN follows. */
    fun onFocusLostPermanently() {
        hasAudioFocus = false
        ducked = false
    }

    /** AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, when the player ducks rather than pauses. */
    fun onDuck() {
        ducked = true
    }

    /** AUDIOFOCUS_GAIN: the focus is ours again after a transient loss, at full volume. */
    fun onFocusGained() {
        hasAudioFocus = true
        ducked = false
    }
}

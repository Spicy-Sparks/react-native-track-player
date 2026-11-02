package com.lovegaoshi.kotlinaudio.player.components

import android.content.Context
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.lovegaoshi.kotlinaudio.models.AudioPlayerState
import com.lovegaoshi.kotlinaudio.models.FocusChangeData
import com.lovegaoshi.kotlinaudio.models.PlayerOptions

/** The two system calls [FocusManager] makes, behind a seam so the policy runs on a plain JVM. */
interface AudioFocusGateway {
    /** Asks for AUDIOFOCUS_GAIN; returns an AudioManager.AUDIOFOCUS_REQUEST_* result. */
    fun request(willPauseWhenDucked: Boolean): Int

    /** Gives the focus last asked for back; returns an AudioManager.AUDIOFOCUS_REQUEST_* result. */
    fun abandon(): Int

    /**
     * Whether a call is up (ringing, in a phone call or a VoIP one): the one time a refused request
     * means "do not play". Android 15 also refuses every request from an app that is neither on
     * screen nor running a foreground service, which is how a background Play starts (media3
     * promotes the service only once playback starts): that refusal must not stop it.
     */
    fun callActive(): Boolean
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

    override fun callActive(): Boolean {
        val manager = ContextCompat.getSystemService(context, AudioManager::class.java) ?: return false
        // RINGTONE, IN_CALL, IN_COMMUNICATION and the call-screening/redirect modes of newer
        // releases: anything but NORMAL is a call in some state.
        return manager.mode != AudioManager.MODE_NORMAL && manager.mode != AudioManager.MODE_INVALID
    }
}

/**
 * What the player does about an audio-focus change: whether it pauses, and what JS hears.
 */
data class FocusReaction(val pause: Boolean, val report: FocusChangeData)

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
     * Whether the focus is ours right now: granted and not lent to anybody. False after any loss
     * that pauses (permanent or transient) and before the first grant. While it is false a start
     * has to ask again ([requestAudioFocus]), as ExoPlayer's own focus handling does (under the
     * old contract, not [strict], a transient loss is not asked about again).
     */
    var hasAudioFocus = false
        private set

    /**
     * A transient loss (a call, a voice note, an assistant) lent the focus out: the system keeps us
     * in its stack and will hand it back with AUDIOFOCUS_GAIN. A permanent loss drops us from the
     * stack instead, and nothing comes back.
     *
     * Kept apart from [hasAudioFocus] because the two mean different things to the player: being
     * in the stack is not leave to play. Before, a transient loss left "has the focus" true, so a
     * play during a call never asked for anything and the music came out over the call.
     */
    var lentOut = false
        private set

    var alwaysPauseOnInterruption = options.alwaysPauseOnInterruption

    /**
     * The focus contract an app opts into once its JS side handles what comes with it
     * (RemoteDuck `denied` and `playWhenReady`; updateOptions `android.strictAudioFocus`):
     * - a start during a call does not happen: the request is refused, and JS hears `denied`;
     * - a start while a transient loss has the focus lent out asks for it again (and is refused
     *   during a call), where it used to play over whoever had it;
     * - a transient loss pauses a track meant to play, also while it is still buffering.
     * Off, the player keeps the old contract: an app whose JS reads `paused` off its own state
     * would see a buffering track paused under it and never resume it.
     */
    var strict = false

    /**
     * Whether the output is ducked for an AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK. A duck can only
     * last while the focus is ours: whatever ends it (GAIN, a permanent loss, giving the focus back
     * on stop, a request granted after a transient loss) restores the volume. A transient loss on
     * top of a duck leaves it on until one of those.
     */
    var ducked = false
        private set(value) {
            if (field == value) return
            field = value
            onDuckChanged?.invoke(value)
        }

    /** Called with the new value whenever [ducked] changes; the player scales its volume. */
    var onDuckChanged: ((Boolean) -> Unit)? = null

    /**
     * Asks for the focus unless it is already ours. Returns whether it is ours afterwards: false
     * means the system refused (during a call it refuses every media request), and the player
     * must not start. A refusal changes nothing else: if a transient loss had lent the focus out,
     * we are still in the stack and the GAIN that ends it still comes.
     */
    fun requestAudioFocus(): Boolean {
        // Lent out counts as ours under the old contract: it never asked during a transient loss.
        if (hasAudioFocus || (lentOut && !strict)) return true
        val granted = gateway.request(alwaysPauseOnInterruption) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            hasAudioFocus = true
            lentOut = false
            // On top of the stack again: whoever made us duck is no longer above us.
            ducked = false
        }
        return granted
    }

    /**
     * Whether the player may start now, asking for the focus if it is not ours. Only a request
     * refused while a call is up stops it, and only under the [strict] contract; any other refusal
     * (Android 15's, of a Play from the background before the service is promoted) lets it start,
     * and PLAYING asks again.
     */
    fun mayStart(): Boolean {
        if (requestAudioFocus()) return true
        return !(strict && gateway.callActive())
    }

    fun abandonAudioFocusIfHeld() {
        // Lent out is still in the stack: leaving it there would bring a GAIN to a stopped player.
        if (!hasAudioFocus && !lentOut) return
        // Count the focus as given back whatever the result: kept true, a failed abandon made
        // every later request return early, and the player could then play without the focus.
        // Asking again with the same listener replaces any entry the system still holds.
        gateway.abandon()
        hasAudioFocus = false
        lentOut = false
        // Out of the focus stack, no GAIN will come to end a duck.
        ducked = false
    }

    /**
     * Keeps the focus in step with the player. Returns false when the player must not play: it
     * wants to (READY with playWhenReady, or PLAYING) and [mayStart] said no. The player then
     * pauses and reports [refusal].
     *
     * The focus used to be asked for only on the way into READY. A resume from pause never goes
     * through READY (ExoPlayer stays in STATE_READY while paused), so after a permanent loss —
     * another music app, a video, an ad — Play brought the sound back without the focus: the app
     * then played over everything else and never paused for anyone again, until the player was
     * recreated. Entering PLAYING without the focus now asks for it.
     *
     * READY asks only when the player is about to play. A track prepared paused (restored at
     * launch, loaded behind a pause) used to take the focus from whatever else was playing.
     *
     * When ExoPlayer handles the focus itself ([exoPlayerHandlesFocus], autoHandleInterruptions
     * on), its own client asks on every play and refuses to start without it; a second request
     * from this app on top of it would take the focus from ExoPlayer's client, which pauses on
     * that loss. So in that mode this manager only keeps the listener registered (READY, as
     * before), never asks on PLAYING and never refuses.
     */
    fun onPlayerStateChanged(
        state: AudioPlayerState,
        exoPlayerHandlesFocus: Boolean,
        playWhenReady: Boolean,
    ): Boolean {
        when (state) {
            AudioPlayerState.IDLE,
            AudioPlayerState.STOPPED,
            AudioPlayerState.ERROR -> abandonAudioFocusIfHeld()
            AudioPlayerState.READY -> when {
                exoPlayerHandlesFocus -> if (!lentOut) requestAudioFocus()
                playWhenReady -> return mayStart()
            }
            AudioPlayerState.PLAYING -> if (!exoPlayerHandlesFocus) return mayStart()
            else -> {}
        }
        return true
    }

    /**
     * What JS hears when a start is refused: paused, and whether the refusal is final. It is not
     * when a transient loss had lent the focus out — the GAIN that ends it still comes, and the
     * music that loss interrupted may still resume then. Otherwise we are out of the stack and
     * nothing will come back.
     */
    fun refusal(): FocusChangeData = FocusChangeData(
        isPaused = true,
        isFocusLostPermanently = !lentOut,
        playWhenReady = true,
        denied = true,
    )

    /**
     * The policy for a change of focus delivered by the system. [playWhenReady] is the player's
     * intent at that moment: a track still BUFFERING is not playing yet but is meant to, and a
     * pause decided on [isPlaying] let it start over the call the moment its data arrived. The old
     * contract (not [strict]) still decides on [isPlaying].
     */
    fun onAudioFocusChange(focusChange: Int, playWhenReady: Boolean, isPlaying: Boolean): FocusReaction? =
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Dropped from the stack. No GAIN follows, so this also ends a duck.
                hasAudioFocus = false
                lentOut = false
                ducked = false
                FocusReaction(pause = true, report = FocusChangeData(true, true, playWhenReady))
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> lendOut(playWhenReady, isPlaying)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                if (alwaysPauseOnInterruption) {
                    lendOut(playWhenReady, isPlaying)
                } else {
                    // Only quieter: the focus stays ours, and playing on asks for nothing.
                    ducked = true
                    FocusReaction(pause = false, report = FocusChangeData(true, false, playWhenReady))
                }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                lentOut = false
                // Ends any duck, also one a transient loss interrupted (a navigation prompt, then
                // a call): that loss used to forget the duck and leave the volume at 0.2.
                ducked = false
                // The player does not start by itself: whether to resume is JS's call (the user
                // may have paused meanwhile, the headphones may be gone).
                FocusReaction(pause = false, report = FocusChangeData(false, false, playWhenReady))
            }
            else -> null
        }

    private fun lendOut(playWhenReady: Boolean, isPlaying: Boolean): FocusReaction {
        hasAudioFocus = false
        lentOut = true
        val pause = if (strict) playWhenReady else isPlaying
        return FocusReaction(pause = pause, report = FocusChangeData(true, false, playWhenReady))
    }
}
